#include "terrazzo_wall.h"

#ifdef USE_ESP_IDF

#include <algorithm>
#include <cmath>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iterator>
#include <map>
#include <memory>
#include <mutex>

#include "board/board.h"
#include "esp_http_client.h"
#ifdef USE_TERRAZZO_SNAPSHOT
#include <cstdlib>
#include "esp_http_server.h"
#endif
#include "esp_timer.h"
#include <cstdio>
#include <thorvg.h>
#include "esp_heap_caps.h"
#include "esphome/components/network/util.h"
#include "esphome/core/hal.h"
#ifdef USE_API
#include "esphome/components/api/api_server.h"
#endif
#include "esphome/core/log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "kobentovg/ThorvgPaintContext.h"
#include "pack_source.h"
#include "nvs.h"
#include "rccore/operations/LayoutOperations.h"
#include "rcstage/stage.h"
#include "sdkconfig.h"
#include "lwip/sockets.h"
#ifdef CONFIG_MBEDTLS_CERTIFICATE_BUNDLE
#include "esp_crt_bundle.h"
#endif
#include "esp_jpeg_dec.h"
#ifdef USE_CAMERA
#include "esp_jpeg_enc.h"
#endif

namespace esphome {
namespace terrazzo_wall {

static const char *const TAG = "terrazzo_wall";

// The rasteriser's notes from before the last reboot: what it refused (an array, a text run)
// just before a crash, whose log was lost with it (patched into ThorVG, kobentovg-damage.patch).
extern "C" const char *tvg_take_notes();
static std::string s_last_boot_notes;

// ── HTTP: esp_http_client, one handle per request (the two tasks call at once) ──

class IdfTransport : public ::tzwall::Transport {
 public:
  IdfTransport(std::string base, std::string token)
      : base_(std::move(base)), auth_(token.empty() ? "" : "Bearer " + token) {
    while (!this->base_.empty() && this->base_.back() == '/') this->base_.pop_back();
  }

  ::tzwall::Response get(const std::string &path, int timeout_ms, const std::string &inm) override {
    return this->request_(HTTP_METHOD_GET, path, nullptr, timeout_ms, inm);
  }
  ::tzwall::Response post_json(const std::string &path, const std::string &json, int timeout_ms) override {
    return this->request_(HTTP_METHOD_POST, path, &json, timeout_ms, "");
  }

 protected:
  static esp_err_t on_event_(esp_http_client_event_t *e) {
    if (e->event_id == HTTP_EVENT_ON_HEADER && strcasecmp(e->header_key, "ETag") == 0)
      static_cast<std::string *>(e->user_data)->assign(e->header_value);
    return ESP_OK;
  }

  ::tzwall::Response request_(esp_http_client_method_t method, const std::string &path,
                                     const std::string *body, int timeout_ms, const std::string &inm) {
    ::tzwall::Response r;
    const std::string url = this->base_ + path;
    esp_http_client_config_t cfg = {};
    cfg.url = url.c_str();
    cfg.method = method;
    cfg.timeout_ms = timeout_ms;
    cfg.event_handler = on_event_;
    cfg.user_data = &r.etag;
    cfg.buffer_size = 2048;
#ifdef CONFIG_MBEDTLS_CERTIFICATE_BUNDLE
    if (url.compare(0, 8, "https://") == 0) cfg.crt_bundle_attach = esp_crt_bundle_attach;
#endif
    esp_http_client_handle_t c = esp_http_client_init(&cfg);
    if (c == nullptr) return r;
    if (!this->auth_.empty()) esp_http_client_set_header(c, "Authorization", this->auth_.c_str());
    if (!inm.empty()) esp_http_client_set_header(c, "If-None-Match", inm.c_str());
    if (body != nullptr) esp_http_client_set_header(c, "Content-Type", "application/json");
    const int length = body != nullptr ? (int) body->size() : 0;
    if (esp_http_client_open(c, length) == ESP_OK &&
        (length == 0 || esp_http_client_write(c, body->data(), length) == length) &&
        esp_http_client_fetch_headers(c) >= 0) {
      r.status = esp_http_client_get_status_code(c);
      // The body grows only into memory that's there: a failed allocation here isn't an
      // exception but a write through null (the panel's PSRAM runs out before its heap says so).
      const int64_t length = esp_http_client_get_content_length(c);
      if (length > 0 && !reserve_(r.body, (size_t) length)) {
        ESP_LOGW(TAG, "%s: no room for %lld bytes", path.c_str(), (long long) length);
        r.status = 0;
      } else {
        char buf[1024];
        int n;
        while ((n = esp_http_client_read(c, buf, sizeof buf)) > 0 && r.body.size() < this->max_body_) {
          if (r.body.size() + n > r.body.capacity() && !reserve_(r.body, (r.body.size() + n) * 2)) {
            ESP_LOGW(TAG, "%s: no room past %u bytes", path.c_str(), (unsigned) r.body.size());
            r.status = 0;
            r.body.clear();
            break;
          }
          r.body.append(buf, n);
        }
      }
    }
    esp_http_client_cleanup(c);
    if (r.body.size() > 2048)
      ESP_LOGD(TAG, "%s: HTTP %d, %u bytes | psram free %u", path.c_str(), r.status,
               (unsigned) r.body.size(), (unsigned) heap_caps_get_free_size(MALLOC_CAP_SPIRAM));
    return r;
  }

  // [s] made able to hold [bytes] without failing: false (and [s] as it was) when no free block
  // could take it, with a margin for what the panel draws meanwhile. Asked by a trial
  // allocation, not heap_caps_get_largest_free_block: that walks the whole heap with interrupts
  // off (2.6 ms on this panel), and the LCD's scan-out slips.
  static bool reserve_(std::string &s, size_t bytes) {
    if (bytes <= s.capacity()) return true;
    constexpr size_t kMargin = 48 * 1024;
    if (heap_caps_get_free_size(MALLOC_CAP_DEFAULT) < bytes + kMargin) return false;
    void *trial = heap_caps_malloc(bytes + 64, MALLOC_CAP_DEFAULT);
    if (trial == nullptr) return false;
    heap_caps_free(trial);
    s.reserve(bytes);
    return s.capacity() >= bytes;
  }

  std::string base_, auth_;

 public:
  size_t max_body_ = SIZE_MAX;
};

// ── The screen: Kobento's rcstage ──

static TerrazzoWall *s_wall = nullptr;
static QueueHandle_t s_actions = nullptr;   // ActionMsg*: taps for the action task
static constexpr int64_t kMenuIdleUs = 20 * 1000000LL;   // the menu closes after this untouched

struct ActionMsg {
  std::string card, action;
  int place = 0;   // not a tap: go to the next (+1) or previous (-1) view or dashboard
  bool dashboard = false;   // with place: a whole dashboard, not a view
  std::string to;  // not a tap: go to this dashboard (url_path), on its first view
  int screen = 0;  // not a tap: page this many screens on (from Home Assistant: off the main loop)
};

class StagePlayer : public ::tzwall::Player {
 public:
  StagePlayer(bool fit, const rcstage::Hooks &hooks) : fit_(fit), hooks_(hooks) {}

  // rcstage starts on the splash, or on the first card when there isn't one.
  bool start(const uint8_t *doc, size_t size) {
    std::lock_guard<std::mutex> g(this->lock_);
    this->started_ = rcstage::start(this->hooks_, doc, size, "splash", true);
    return this->started_;
  }

  void show(const std::vector<uint8_t> &rc, const std::string &name) override {
    {
      std::lock_guard<std::mutex> g(this->lock_);
      if (!this->started_) {
        this->started_ = rcstage::start(this->hooks_, rc.data(), rc.size(), name.c_str(), this->fit_);
        if (!this->started_) ESP_LOGW(TAG, "%s: the player couldn't load it", name.c_str());
        return;
      }
    }
    rcstage::show(rc, name, this->fit_);
  }

  void set_named(const std::string &name, const std::string &value) override { rcstage::setNamed(name, value); }

  void preload(const std::vector<uint8_t> &rc, const std::string &name,
               const std::vector<std::pair<std::string, std::string>> &values) override {
    if (this->started_) rcstage::preload(rc, name, this->fit_, values);
  }

  void status(const std::string &text) override {
    std::lock_guard<std::mutex> g(this->lock_);
    if (text == this->last_) return;
    this->last_ = text;
    if (text.empty()) {
      ESP_LOGI(TAG, "online");
    } else {
      ESP_LOGW(TAG, "%s", text.c_str());
    }
    this->banner_locked_();
  }

  // The menu's banner, over any status while it's open; empty closes it.
  void menu(const std::string &title, const std::string &detail) {
    std::lock_guard<std::mutex> g(this->lock_);
    this->menu_title_ = title, this->menu_detail_ = detail;
    this->banner_locked_();
  }

 protected:
  bool fit_;
  rcstage::Hooks hooks_;
  std::mutex lock_;
  bool started_ = false;
  std::string last_;   // the last status
  std::string menu_title_, menu_detail_;

  void banner_locked_() {
    if (!this->started_) return;
    if (!this->menu_title_.empty())
      rcstage::setBanner(this->menu_title_, this->menu_detail_, 0xFF03A9F4);
    else
      rcstage::setBanner(this->last_.empty() ? "" : "Home Assistant", this->last_, this->last_.empty() ? 0 : 0xFFFFC107);
  }
};

static StagePlayer *s_player = nullptr;

// ── Pictures ──
//
// A design names a picture by its slot (`rc-slot://camera.s0/entity_picture`); the long poll
// says where the card on screen has it (`camera.s0.picture_url`). The player asks for one as it
// paints, at the size it shows it: answered with what's held, and the fetch task gets it from
// Home Assistant (scaled and cropped, as JPEG), then has the player draw again. Only the JPEG is
// kept: each frame that draws over a picture's box decodes it there again, 16 rows at a time,
// straight into the frame (ThorvgPaintContext's direct pictures). A picture on screen is fetched
// again each minute (an unchanged one answers 304), and at once after a tap on its card. Only the
// screen showing keeps its pictures. The document never changes for a picture: only its slot's
// URL is in it.
class PictureSource : public kobentovg::ThorvgPaintContext::BitmapSource {
 public:
  static constexpr int64_t kRefreshUs = 60 * 1000000LL;
  static constexpr int64_t kFreshEveryUs = 5 * 1000000LL;   // a tap's fetch, at most this often
  static constexpr int64_t kRetryUs = 10 * 1000000LL;       // after a fetch that failed
  static constexpr size_t kMaxBytes = 128 * 1024;            // one JPEG

  PictureSource(std::string url, std::string token, ::tzwall::WallClient *client, std::atomic<bool> *screen_on)
      : http_(std::move(url), std::move(token)), client_(client), screen_on_(screen_on) {
    this->http_.max_body_ = kMaxBytes;
  }

  bool picture(const std::string &, int, int, uint32_t &, std::vector<uint32_t> &, int &, int &) override {
    return false;   // drawn directly
  }
  bool direct() const override { return true; }

  uint32_t pictureVersion(const std::string &url, int w, int h) override {
    std::string key;
    if (!key_of_(url, key)) return 0;
    // A layout's first frame can give a box no height yet: not one to fetch for.
    if (w < 16 || h < 16) return 0;
    std::lock_guard<std::mutex> g(this->lock_);
    const int64_t now = esp_timer_get_time();
    Entry &e = this->entries_[key];
    if (e.card.empty()) e.card = key.substr(0, key.find('|')), e.slot = key.substr(key.find('|') + 1);
    e.seen_us = now;
    if (std::abs(e.w - w) > 2 || std::abs(e.h - h) > 2) {
      ESP_LOGI(TAG, "picture %s box %dx%d -> %dx%d", key.c_str(), e.w, e.h, w, h);
      e.w = w, e.h = h;
      e.sized_us = now;   // a layout settling moves it a few times: fetched once it stays
      e.fetched_us = 0;   // another size: fetched again
      this->wake_();
    } else if (e.jpeg == nullptr) {
      this->wake_();
    }
    return e.jpeg == nullptr ? 0 : e.version;
  }

  // The picture into [box] of the frame, where it's [within]: decoded 16 rows at a time (the
  // only memory it takes: a strip of the JPEG's width), each row written where it lands. A JPEG
  // up to 7 pixels bigger than the box (it's fetched in whole blocks of 8) is cropped to it, at
  // its centre; one of another size (the box moved) is scaled to it.
  void drawPicture(const std::string &url, uint32_t, const int *box, const int *within, uint32_t *pixels,
                   int stride) override {
    std::string key;
    if (!key_of_(url, key)) return;
    std::shared_ptr<const std::string> jpeg;
    {
      std::lock_guard<std::mutex> g(this->lock_);
      auto it = this->entries_.find(key);
      if (it == this->entries_.end() || it->second.jpeg == nullptr) return;
      jpeg = it->second.jpeg;
    }
    const int64_t t0 = esp_timer_get_time();
    const int bw = box[2] - box[0], bh = box[3] - box[1];
    if (bw <= 0 || bh <= 0) return;
    jpeg_dec_config_t config = DEFAULT_JPEG_DEC_CONFIG();
    config.output_type = JPEG_PIXEL_FORMAT_RGB888;
    config.block_enable = true;
    jpeg_dec_handle_t dec = nullptr;
    if (jpeg_dec_open(&config, &dec) != JPEG_ERR_OK) return;
    jpeg_dec_io_t io = {};
    io.inbuf = reinterpret_cast<uint8_t *>(const_cast<char *>(jpeg->data()));
    io.inbuf_len = (int) jpeg->size();
    jpeg_dec_header_info_t info = {};
    int len = 0, count = 0;
    uint8_t *strip = nullptr;
    bool ok = jpeg_dec_parse_header(dec, &io, &info) == JPEG_ERR_OK && info.width > 0 && info.height > 0 &&
              jpeg_dec_get_outbuf_len(dec, &len) == JPEG_ERR_OK && len > 0 &&
              jpeg_dec_get_process_count(dec, &count) == JPEG_ERR_OK && count > 0;
    if (ok) {
      strip = static_cast<uint8_t *>(heap_caps_aligned_alloc(16, len, MALLOC_CAP_SPIRAM));
      ok = strip != nullptr;
    }
    if (ok) {
      const int jw = info.width, jh = info.height, rows = len / (3 * jw);
      const bool crop = jw >= bw && jw - bw < 8 && jh >= bh && jh - bh < 8;
      const int ox = crop ? (jw - bw) / 2 : 0, oy = crop ? (jh - bh) / 2 : 0;
      auto src_row = [&](int y) { return crop ? y - box[1] + oy : (int) ((int64_t) (y - box[1]) * jh / bh); };
      std::vector<uint16_t> cols((size_t) std::max(0, within[2] - within[0]));
      for (int x = within[0]; x < within[2]; x++)
        cols[x - within[0]] = (uint16_t) (crop ? x - box[0] + ox : (int) ((int64_t) (x - box[0]) * jw / bw));
      int y = within[1];
      for (int i = 0; ok && i < count && y < within[3]; i++) {
        io.outbuf = strip;
        ok = jpeg_dec_process(dec, &io) == JPEG_ERR_OK;
        const int r0 = i * rows, r1 = std::min(jh, r0 + rows);
        for (; ok && y < within[3]; y++) {
          const int sr = src_row(y);
          if (sr >= r1) break;   // in a later strip
          if (sr < r0) continue;
          const uint8_t *in = strip + (size_t) (sr - r0) * jw * 3;
          uint32_t *out = pixels + (size_t) y * stride;
          for (int x = within[0]; x < within[2]; x++) {
            const uint8_t *p = in + 3 * cols[x - within[0]];
            out[x] = 0xFF000000u | (uint32_t(p[0]) << 16) | (uint32_t(p[1]) << 8) | p[2];
          }
        }
      }
    }
    if (strip != nullptr) heap_caps_free(strip);
    jpeg_dec_close(dec);
    this->draw_us_ += esp_timer_get_time() - t0;
    this->draws_++;
  }

  // Picture drawing since the last call: how many, and the microseconds they took.
  void take_draw_stats(int &draws, int64_t &us) {
    draws = this->draws_.exchange(0);
    us = this->draw_us_.exchange(0);
  }

  // A tap on [card]: its pictures fetched now, past both caches.
  void refresh(const std::string &card) {
    std::lock_guard<std::mutex> g(this->lock_);
    bool any = false;
    for (auto &kv : this->entries_)
      if (kv.second.card == card) kv.second.fresh = true, any = true;
    if (any) this->wake_();
  }

  void start() { xTaskCreatePinnedToCore(task_, "wall_pics", 6144, this, 1, &this->task_handle_, 0); }

 protected:
  struct Entry {
    std::string card, slot, url, etag;   // url: the picture_url it was fetched from
    int w = 0, h = 0;                    // the size it's drawn at
    std::shared_ptr<const std::string> jpeg;
    uint32_t version = 0;
    int64_t fetched_us = 0, fresh_us = 0, failed_us = 0, seen_us = 0, sized_us = 0;
    bool fresh = false;
  };

  static constexpr int64_t kKeepUs = 60 * 1000000LL;   // another screen's pictures, after it went
  static constexpr size_t kKeepEntries = 16;
  static constexpr int64_t kSettleUs = 400 * 1000;      // a size held this long is the one to fetch
  std::atomic<bool> settling_{false};                    // a picture's size is still moving

  // A picture_url's `v`: which picture of its entity (it moves with its URL in Home Assistant).
  static std::string version_of(const std::string &url) {
    const size_t at = url.find("v=");
    return at == std::string::npos ? "" : url.substr(at + 2, url.find('&', at) - at - 2);
  }

  std::atomic<int> draws_{0};
  std::atomic<int64_t> draw_us_{0};

  // A picture's entry: the card showing and the slot (`rc-slot://camera.s0/entity_picture`).
  static bool key_of_(const std::string &url, std::string &key) {
    static const std::string kScheme = "rc-slot://";
    if (url.compare(0, kScheme.size(), kScheme) != 0) return false;
    const std::string slot = url.substr(kScheme.size(), url.find('/', kScheme.size()) - kScheme.size());
    std::string card = rcstage::currentName();
    card = card.substr(0, card.find(' '));   // documents are named "<card id> <type>"
    key = card + "|" + slot;
    return true;
  }

  IdfTransport http_;
  ::tzwall::WallClient *client_;
  std::atomic<bool> *screen_on_;
  std::mutex lock_;
  std::map<std::string, Entry> entries_;
  TaskHandle_t task_handle_ = nullptr;

  void wake_() {
    if (this->task_handle_ != nullptr) xTaskNotifyGive(this->task_handle_);
  }

  static void task_(void *self) {
    auto *source = static_cast<PictureSource *>(self);
    while (true) {
      ulTaskNotifyTake(pdTRUE, pdMS_TO_TICKS(source->settling_ ? 100 : 5000));
      source->settling_ = false;
      while (source->fetch_one_()) {
      }
    }
  }

  // One picture due on the screen showing fetched; false when none is.
  bool fetch_one_() {
    std::string card = rcstage::currentName();
    card = card.substr(0, card.find(' '));
    const int64_t now = esp_timer_get_time();
    std::string key, path, etag, why;
    bool fresh = false, reused = false;
    {
      std::lock_guard<std::mutex> g(this->lock_);
      // Another screen's pictures: kept a while, for a screen shown again or one sharing them;
      // dropped once it's been gone a minute, or the oldest first past kKeepEntries.
      for (auto it = this->entries_.begin(); it != this->entries_.end();)
        it = it->second.card == card || now - it->second.seen_us < kKeepUs ? std::next(it) : this->entries_.erase(it);
      while (this->entries_.size() > kKeepEntries) {
        auto oldest = this->entries_.end();
        for (auto it = this->entries_.begin(); it != this->entries_.end(); ++it)
          if (it->second.card != card && (oldest == this->entries_.end() || it->second.seen_us < oldest->second.seen_us))
            oldest = it;
        if (oldest == this->entries_.end()) break;
        this->entries_.erase(oldest);
      }
      if (!this->screen_on_->load()) return false;   // a dark screen: nothing refreshed
      for (auto &kv : this->entries_) {
        Entry &e = kv.second;
        if (e.card != card) continue;   // only the screen showing is fetched
        const std::string url = this->client_->picture_url(e.card, e.slot);
        if (url.empty() || e.w <= 0 || e.h <= 0) continue;
        if (e.failed_us != 0 && now - e.failed_us < kRetryUs) continue;   // not straight back after a failure
        if (now - e.sized_us < kSettleUs) {
          this->settling_ = true;   // its layout is still settling: looked at again shortly
          continue;
        }
        const bool tapped = e.fresh && now - e.fresh_us >= kFreshEveryUs;
        if (e.jpeg != nullptr && url == e.url && now - e.fetched_us < kRefreshUs && !tapped) continue;
        key = kv.first;
        fresh = tapped;
        e.fresh = false;
        if (tapped) e.fresh_us = now;
        // The same picture at the same size on a screen shown lately (screens overlap): its bytes,
        // not another fetch.
        if (e.jpeg == nullptr && !version_of(url).empty()) {
          const Entry *same = nullptr;
          for (auto &o : this->entries_)
            if (&o.second != &e && o.second.jpeg != nullptr && version_of(o.second.url) == version_of(url) &&
                std::abs(o.second.w - e.w) <= 2 && std::abs(o.second.h - e.h) <= 2)
              same = &o.second;
          if (same != nullptr) {
            e.jpeg = same->jpeg, e.etag = same->etag, e.url = url, e.fetched_us = same->fetched_us;
            e.version++;
            reused = true;
            continue;
          }
        }
        why = e.jpeg == nullptr ? "new" : url != e.url ? "moved" : e.fetched_us == 0 ? "resized" : tapped ? "tapped" : "due";
        e.fetched_us = now;   // not asked again until it's due, whatever this fetch does
        if (url != e.url) e.etag.clear();
        e.url = url;
        etag = e.etag;
        // Whole blocks of 8, as the strip decoder needs: cropped to the box as it's drawn.
        const int w = (e.w + 7) / 8 * 8, h = (e.h + 7) / 8 * 8;
        why += " " + std::to_string(w) + "x" + std::to_string(h);
        path = url + (url.find('?') == std::string::npos ? "?" : "&") + "w=" + std::to_string(w) +
               "&h=" + std::to_string(h) + "&fit=cover&format=jpeg" + (fresh ? "&fresh=1" : "");
        break;
      }
    }
    if (reused) rcstage::redraw();   // pictures it had already
    if (key.empty()) return false;
    const int64_t t0 = esp_timer_get_time();
    ::tzwall::Response r = this->http_.get(path, 10000, etag);
    if (r.status == 304) {
      ESP_LOGD(TAG, "picture %s (%s): unchanged", key.c_str(), why.c_str());
      return true;   // as it was
    }
    if (r.status != 200 || r.body.empty()) {
      ESP_LOGW(TAG, "picture %s: HTTP %d", key.c_str(), r.status);
      std::lock_guard<std::mutex> g(this->lock_);
      auto it = this->entries_.find(key);
      if (it != this->entries_.end()) it->second.failed_us = esp_timer_get_time();
      return true;   // the next one, if any; this one again in kRetryUs
    }
    const size_t size = r.body.size();
    auto jpeg = std::make_shared<const std::string>(std::move(r.body));
    {
      std::lock_guard<std::mutex> g(this->lock_);
      auto it = this->entries_.find(key);
      if (it == this->entries_.end()) return true;   // its screen went meanwhile
      it->second.jpeg = std::move(jpeg);
      it->second.etag = r.etag;
      it->second.failed_us = 0;
      it->second.version++;
    }
    ESP_LOGI(TAG, "picture %s (%s): %u bytes in %d ms", key.c_str(), why.c_str(), (unsigned) size,
             (int) ((esp_timer_get_time() - t0) / 1000));
    rcstage::redraw();
    return true;
  }
};

static PictureSource *s_pictures = nullptr;

// The sockets open now, each as where it's bound and what it's connected to ("6053<-192.168.0.141"
// for a client of the panel's, "->192.168.0.212:8123" for a request of its own, "listen 3232",
// "udp 32768"): what holds them when one can't be had.
static int open_sockets(std::string *list) {
  int n = 0;
  for (int fd = LWIP_SOCKET_OFFSET; fd < LWIP_SOCKET_OFFSET + CONFIG_LWIP_MAX_SOCKETS; fd++) {
    int type = 0;
    socklen_t len = sizeof type;
    if (getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &len) != 0) continue;
    n++;
    if (list == nullptr) continue;
    sockaddr_in self = {}, peer = {};
    socklen_t sl = sizeof self, pl = sizeof peer;
    getsockname(fd, reinterpret_cast<sockaddr *>(&self), &sl);
    char ip[16] = "";
    const bool connected = getpeername(fd, reinterpret_cast<sockaddr *>(&peer), &pl) == 0;
    if (connected) inet_ntoa_r(peer.sin_addr, ip, sizeof ip);
    const unsigned port = ntohs(self.sin_port), to = ntohs(peer.sin_port);
    char one[48];
    if (type != SOCK_STREAM)
      snprintf(one, sizeof one, "udp %u", port);
    else if (!connected)
      snprintf(one, sizeof one, "listen %u", port);
    else if (port == 6053 || port == 3232 || port == 8080)
      snprintf(one, sizeof one, "%u<-%s", port, ip);
    else
      snprintf(one, sizeof one, "->%s:%u", ip, to);
    *list += (list->empty() ? "" : ", ") + std::string(one);
  }
  return n;
}

static void post_action(const std::string &card, const std::string &action) {
  if (s_actions == nullptr || card.empty()) return;
  auto *msg = new ActionMsg{card, action};
  if (xQueueSend(s_actions, &msg, 0) != pdTRUE) {
    ESP_LOGW(TAG, "tap dropped: %u waiting", (unsigned) uxQueueMessagesWaiting(s_actions));
    delete msg;
  }
}

// The panel's interrupt runs on the core that starts it, and with bounce buffers it copies the
// frame out of PSRAM (some 32 MB/s at 16 MHz): start it on core 0, away from the player's core 1.
static bool display_init_on_core0() {
  struct Start {
    SemaphoreHandle_t done;
    bool ok;
  } start{xSemaphoreCreateBinary(), false};
  xTaskCreatePinnedToCore(
      [](void *arg) {
        auto *s = static_cast<Start *>(arg);
        s->ok = board::displayInit();
        xSemaphoreGive(s->done);
        vTaskDelete(nullptr);
      },
      "lcd_init", 4096, &start, 5, nullptr, 0);
  xSemaphoreTake(start.done, portMAX_DELAY);
  vSemaphoreDelete(start.done);
  return start.ok;
}

void TerrazzoWall::setup() {
  s_wall = this;
  s_last_boot_notes = tvg_take_notes();
  ESP_LOGI(TAG, "setup on core %d; starting the panel on core 0", (int) xPortGetCoreID());
  if (!display_init_on_core0()) {
    ESP_LOGE(TAG, "the display didn't start");
    this->mark_failed();
    return;
  }
  rccore::setLayoutAnimationEnabled(this->layout_animations_);
  rcstage::Hooks hooks;
  // A document's tap: the `ha` host action carries an HaAction string for the server.
  hooks.namedAction = [](const std::string &name, int, const std::string &value) {
    if (s_wall->menu_open()) ESP_LOGD(TAG, "named action %s: the menu is open", name.c_str());
    else if (name == "ha") post_action(s_wall->client_->current_card(), value);
    else ESP_LOGD(TAG, "named action %s = %s (not ours)", name.c_str(), value.c_str());
  };
  hooks.longPress = [] { s_wall->toggle_menu(); };
  hooks.tap = [](float x, float y) {
    if (s_wall->menu_open()) s_wall->menu_tap(x, y);
  };
  hooks.navTap = [](int index) { s_wall->nav_tap_(index); };
  hooks.sideTap = [](float at) { s_wall->side_tap_(at); };
  hooks.tick = [] {
    if (int step = board::buttonPressed()) s_wall->page_(step);
    s_wall->screen_tick_();
    s_wall->nav_tick_();
    if (s_wall->menu_open() && esp_timer_get_time() - s_wall->menu_touched_us_ > kMenuIdleUs) s_wall->toggle_menu();
  };

  // Text from glyph packs: those built in, those fetched before (kept in NVS), and from here on
  // whatever a run lacks, fetched from the design server.
  this->load_stored_glyphs_();
  static ::tzwall::PackSource<kobentovg::ThorvgPaintContext> glyph_source(this->glyphs_);
  kobentovg::ThorvgPaintContext::setGlyphSource(&glyph_source);
  ESP_LOGI(TAG, "glyph packs: %u glyphs in %u styles", (unsigned) this->glyphs_.glyph_count(),
           (unsigned) this->glyphs_.style_count());

  // Each design is made with the screen as its canvas, in dp (the player scales dp to pixels).
  this->config_.width_dp = (int) (board::kWidth / board::kDensity);
  this->config_.height_dp = (int) (board::kHeight / board::kDensity);
  if (this->navigation_) {
    // The bar takes the bottom of the screen: designs are made for what's above it.
    rcstage::setNavHeight(kNavHeightDp);
    this->config_.height_dp -= kNavHeightDp;
  }
  if (this->scroll_bar_ && this->config_.screens) {
    // The scroll bar takes the right of the screen: designs are made for what's left of it.
    rcstage::setSideWidth(kScrollBarDp);
    this->config_.width_dp -= kScrollBarDp;
  }
  this->awake_since_us_ = esp_timer_get_time();
  auto *player = new StagePlayer(this->fit_, hooks);
  s_player = player;
  this->player_.reset(player);
  this->http_ = std::make_unique<IdfTransport>(this->url_, this->token_);
  this->client_ = std::make_unique<::tzwall::WallClient>(*this->http_, *this->player_, this->config_);
  // Pictures (cameras, images) the designs name by slot: fetched as they're drawn.
  s_pictures = new PictureSource(this->url_, this->token_, this->client_.get(), &this->screen_on_);
  kobentovg::ThorvgPaintContext::setBitmapSource(s_pictures);
  s_pictures->start();
  // Home Assistant reads a select's options when it connects, right after this setup: fetch
  // the dashboards now (Wi-Fi is up by AFTER_WIFI), so it sees them the first time.
  if (this->idle_return_number_ != nullptr) this->idle_return_number_->restore(this->idle_return_default_);
  else this->idle_return_min_ = this->idle_return_default_;
  if (this->dashboard_select_ != nullptr) {
    const bool listed = this->client_->load_dashboard_list();
    this->publish_to_ha_();
    ESP_LOGI(TAG, "dashboards for Home Assistant: %u%s", (unsigned) this->option_titles_.size(),
             listed ? "" : " (the list didn't load: offered once it does)");
  }
  s_actions = xQueueCreate(8, sizeof(ActionMsg *));
  // The hooks above may run from here on.
  if (this->splash_ != nullptr && !player->start(this->splash_, this->splash_size_))
    ESP_LOGW(TAG, "the splash didn't load; starting on the first card");
  // Core 0: rcstage's player task owns core 1.
  xTaskCreatePinnedToCore(poll_task_, "wall_poll", 8192, this, 2, &this->poll_handle_, 0);
  xTaskCreatePinnedToCore(action_task_, "wall_tap", 6144, this, 3, nullptr, 0);
  // Glyphs no pack has (another size, an emoji): from the design server, through Home Assistant
  // unless the YAML names one.
  xTaskCreatePinnedToCore(glyph_task_, "wall_glyphs", 6144, this, 1, nullptr, 0);
  // Memory: a line whenever free PSRAM moves by 8 KB, or internal RAM by 4 KB (checked every
  // 250 ms), so a log shows what the panel did as it ran short. Only the free counters: the
  // largest free block is a walk of the whole heap under a spinlock, interrupts off, and on
  // core 0 that starves the LCD's bounce-buffer refills (2.6 ms measured, against some 0.5 ms
  // of bounce buffer): the picture jumps.
  xTaskCreatePinnedToCore(
      [](void *) {
        size_t last_free = 0, last_internal = 0;
        while (true) {
          const size_t free = heap_caps_get_free_size(MALLOC_CAP_SPIRAM);
          const size_t internal = heap_caps_get_free_size(MALLOC_CAP_INTERNAL);
          if (free + 8192 < last_free || last_free + 8192 < free || internal + 4096 < last_internal ||
              last_internal + 4096 < internal) {
            ESP_LOGI(TAG, "mem psram %uK min %uK int %uK min %uK", (unsigned) (free >> 10),
                     (unsigned) (heap_caps_get_minimum_free_size(MALLOC_CAP_SPIRAM) >> 10),
                     (unsigned) (internal >> 10),
                     (unsigned) (heap_caps_get_minimum_free_size(MALLOC_CAP_INTERNAL) >> 10));
            last_free = free;
            last_internal = internal;
          }
          // Sockets: every change near the limit logged with who holds them; the peak each minute.
          static int last_sockets = -1, peak = 0;
          static int64_t peak_at = 0;
          const int sockets = open_sockets(nullptr);
          peak = std::max(peak, sockets);
          if (sockets != last_sockets && sockets >= CONFIG_LWIP_MAX_SOCKETS - 3) {
            std::string list;
            open_sockets(&list);
            ESP_LOGI(TAG, "sockets %d of %d: %s", sockets, CONFIG_LWIP_MAX_SOCKETS, list.c_str());
          }
          last_sockets = sockets;
          if (esp_timer_get_time() - peak_at > 60 * 1000000LL) {
            ESP_LOGI(TAG, "sockets %d of %d open, at most %d this minute", sockets, CONFIG_LWIP_MAX_SOCKETS, peak);
            peak = sockets;
            peak_at = esp_timer_get_time();
          }
          int draws = 0;
          int64_t us = 0;
          if (s_pictures != nullptr) s_pictures->take_draw_stats(draws, us);
          if (draws > 0)
            ESP_LOGI(TAG, "pictures drawn %d in %d ms (%d ms each)", draws, (int) (us / 1000),
                     (int) (us / 1000 / draws));
          vTaskDelay(pdMS_TO_TICKS(250));
        }
      },
      "wall_mem", 4096, nullptr, 1, nullptr, 0);
  this->start_snapshot_server_();
}

// ── Glyph packs fetched: what no pack has, from the design server, kept in NVS ──

static const char *const GLYPH_NVS = "tz_glyphs";

// Packs fetched earlier, unless the firmware's packs changed since (then they're dropped: they
// were fetched for what the old ones lacked).
void TerrazzoWall::load_stored_glyphs_() {
  nvs_handle_t h;
  if (nvs_open(GLYPH_NVS, NVS_READWRITE, &h) != ESP_OK) return;
  uint32_t config = 0, count = 0;
  if (nvs_get_u32(h, "config", &config) != ESP_OK || config != this->glyph_config_) {
    nvs_erase_all(h);
    nvs_set_u32(h, "config", this->glyph_config_);
    nvs_commit(h);
    nvs_close(h);
    return;
  }
  nvs_get_u32(h, "count", &count);
  for (uint32_t i = 0; i < count; i++) {
    char key[12];
    snprintf(key, sizeof key, "p%u", (unsigned) i);
    size_t size = 0;
    if (nvs_get_blob(h, key, nullptr, &size) != ESP_OK || size == 0) continue;
    if (this->glyph_bytes_ + size > kMaxFetchedGlyphBytes) break;
    auto *data = static_cast<uint8_t *>(malloc(size));   // kept: the packs point into it
    if (data == nullptr) break;
    if (nvs_get_blob(h, key, data, &size) == ESP_OK && this->glyphs_.add(data, size)) {
      this->glyph_bytes_ += size;
    } else {
      free(data);
    }
  }
  this->glyph_stored_ = count;
  nvs_close(h);
  ESP_LOGI(TAG, "glyph packs: %u fetched before (%u bytes)", (unsigned) count, (unsigned) this->glyph_bytes_);
}

// One pack fetched: kept for the player (never freed: glyphs point into it) and in NVS.
bool TerrazzoWall::keep_glyphs_(const std::string &body) {
  if (this->glyph_bytes_ + body.size() > kMaxFetchedGlyphBytes) return false;
  auto *data = static_cast<uint8_t *>(malloc(body.size()));
  if (data == nullptr) return false;
  memcpy(data, body.data(), body.size());
  if (!this->glyphs_.add(data, body.size())) {
    free(data);
    return false;
  }
  this->glyph_bytes_ += body.size();
  nvs_handle_t h;
  if (nvs_open(GLYPH_NVS, NVS_READWRITE, &h) == ESP_OK) {
    char key[12];
    snprintf(key, sizeof key, "p%u", (unsigned) this->glyph_stored_);
    if (nvs_set_blob(h, key, data, body.size()) == ESP_OK &&
        nvs_set_u32(h, "count", this->glyph_stored_ + 1) == ESP_OK && nvs_commit(h) == ESP_OK)
      this->glyph_stored_++;
    nvs_close(h);
  }
  return true;
}

void TerrazzoWall::glyph_task_(void *self) {
  auto *wall = static_cast<TerrazzoWall *>(self);
  // The design server named in the YAML, else Home Assistant's route to its add-on (the wall
  // token's): whichever answers, the panel asks for what it draws (4-bit coverage, 32-bit
  // colour for emoji) and takes the packs it's given.
  const bool direct = !wall->glyph_server_.empty();
  IdfTransport server(direct ? wall->glyph_server_ : wall->url_, direct ? "" : wall->token_);
  server.max_body_ = 64 * 1024;
  const std::string route = direct ? "/designs/v1/fonts/glyphs?" : "/api/terrazzo/wall/v1/glyphs?";
  while (true) {
    vTaskDelay(pdMS_TO_TICKS(2000));
    if (!network::is_connected()) continue;
    for (auto &want : wall->glyphs_.take_wants()) {
      if (wall->glyph_bytes_ >= kMaxFetchedGlyphBytes) break;   // full: ThorVG draws the rest
      // A request's characters fit a URL: a few dozen at a time.
      for (size_t at = 0; at < want.code_points.size(); at += 48) {
        ::tzwall::GlyphPacks::Want part{want.style, {}};
        part.code_points.assign(want.code_points.begin() + at,
                                want.code_points.begin() + std::min(want.code_points.size(), at + 48));
        const auto r = server.get(route + ::tzwall::glyph_query(part, wall->glyph_bits_), 10000, "");
        if (r.status == 200) {
          const bool kept = wall->keep_glyphs_(r.body);
          // Measured again with them: an emoji's width wasn't known when the card was laid out.
          if (kept && wall->client_) wall->client_->reshow();
          ESP_LOGI(TAG, "glyphs: face %d at %d px, %u characters: %u bytes%s", part.style.face,
                   part.style.px, (unsigned) part.code_points.size(), (unsigned) r.body.size(),
                   kept ? "" : " (not kept)");
        } else if (r.status == 404 && !direct) {
          // Home Assistant's add-on has no glyph route yet: text the panel's packs lack draws
          // with ThorVG (an emoji as nothing) until it does.
          ESP_LOGW(TAG, "glyphs: the design server has no glyph route (update the Terrazzo Card Designs add-on)");
        } else if (r.status == 0 || r.status >= 500) {
          // Not answered: asked again a while later.
          ESP_LOGW(TAG, "glyphs: the design server didn't answer (HTTP %d)", r.status);
          wall->glyphs_.retry(part);
          vTaskDelay(pdMS_TO_TICKS(30000));
        } else {
          ESP_LOGD(TAG, "glyphs: HTTP %d for face %d at %d px", r.status, part.style.face, part.style.px);
        }
      }
    }
  }
}

#ifdef USE_TERRAZZO_SNAPSHOT
// `GET /snapshot.bmp?step=N`: the last presented frame as a 24-bit BMP, every Nth pixel (1-8,
// default 1). Read while the player runs, so a frame mid-swap may tear; it's a debugging aid.
// `X-Card` names the card on screen.
// The document on screen, as the player loaded it: to replay it on a host renderer.
static esp_err_t doc_handler(httpd_req_t *req) {
  const auto bytes = rcstage::docBytes();
  if (!bytes || bytes->empty()) {
    httpd_resp_send_err(req, HTTPD_404_NOT_FOUND, "no document on screen");
    return ESP_OK;
  }
  const std::string name = rcstage::currentName();
  httpd_resp_set_type(req, "application/octet-stream");
  httpd_resp_set_hdr(req, "X-Document", name.c_str());
  return httpd_resp_send(req, reinterpret_cast<const char *>(bytes->data()), (ssize_t) bytes->size());
}

static esp_err_t snapshot_handler(httpd_req_t *req) {
  const uint16_t *fb = board::frameBuffer();
  if (fb == nullptr) {
    httpd_resp_send_err(req, HTTPD_404_NOT_FOUND, "this board can't read its screen back");
    return ESP_OK;
  }
  int step = 1;
  char query[32], value[8];
  if (httpd_req_get_url_query_str(req, query, sizeof(query)) == ESP_OK &&
      httpd_query_key_value(query, "step", value, sizeof(value)) == ESP_OK)
    step = std::max(1, std::min(8, atoi(value)));
  const int w = board::kWidth / step, h = board::kHeight / step;
  const int stride = (w * 3 + 3) & ~3;
  const uint32_t image = (uint32_t) stride * h, file = 54 + image;
  uint8_t header[54] = {'B', 'M'};
  auto put32 = [&](int at, uint32_t v) {
    for (int i = 0; i < 4; i++) header[at + i] = (uint8_t) (v >> (8 * i));
  };
  put32(2, file);
  put32(10, 54);
  put32(14, 40);
  put32(18, (uint32_t) w);
  put32(22, (uint32_t) -h);   // top-down rows
  header[26] = 1;             // planes
  header[28] = 24;            // bits per pixel
  put32(34, image);
  const std::string card = s_wall->current_card();
  httpd_resp_set_type(req, "image/bmp");
  httpd_resp_set_hdr(req, "Cache-Control", "no-store");
  httpd_resp_set_hdr(req, "X-Card", card.c_str());
  if (httpd_resp_send_chunk(req, (const char *) header, sizeof(header)) != ESP_OK) return ESP_FAIL;
  constexpr int kRows = 8;
  auto *buf = static_cast<uint8_t *>(malloc((size_t) stride * kRows));
  if (buf == nullptr) return ESP_FAIL;
  esp_err_t err = ESP_OK;
  for (int y0 = 0; y0 < h && err == ESP_OK; y0 += kRows) {
    const int rows = std::min(kRows, h - y0);
    memset(buf, 0, (size_t) stride * rows);
    for (int r = 0; r < rows; r++) {
      const uint16_t *src = fb + (size_t) (y0 + r) * step * board::kWidth;
      uint8_t *dst = buf + (size_t) r * stride;
      for (int x = 0; x < w; x++) {
        const uint16_t v = src[x * step];
        dst[x * 3 + 0] = (uint8_t) ((v & 31) << 3);
        dst[x * 3 + 1] = (uint8_t) (((v >> 5) & 63) << 2);
        dst[x * 3 + 2] = (uint8_t) (((v >> 11) & 31) << 3);
      }
    }
    err = httpd_resp_send_chunk(req, (const char *) buf, (ssize_t) stride * rows);
  }
  free(buf);
  if (err != ESP_OK) return ESP_FAIL;
  return httpd_resp_send_chunk(req, nullptr, 0);
}

void TerrazzoWall::start_snapshot_server_() {
  if (this->snapshot_port_ == 0) return;
  httpd_config_t config = HTTPD_DEFAULT_CONFIG();
  config.server_port = this->snapshot_port_;
  config.ctrl_port = 32770;   // not the default, which ESPHome's own web server may hold
  config.core_id = 0;
  config.stack_size = 6144;
  config.max_open_sockets = 2;
  httpd_handle_t server = nullptr;
  if (httpd_start(&server, &config) != ESP_OK) {
    ESP_LOGW(TAG, "the snapshot server didn't start on port %u", this->snapshot_port_);
    return;
  }
  static const httpd_uri_t uri = {.uri = "/snapshot.bmp", .method = HTTP_GET, .handler = snapshot_handler, .user_ctx = nullptr};
  httpd_register_uri_handler(server, &uri);
  static const httpd_uri_t doc = {.uri = "/doc.rc", .method = HTTP_GET, .handler = doc_handler, .user_ctx = nullptr};
  httpd_register_uri_handler(server, &doc);
}
#else
void TerrazzoWall::start_snapshot_server_() {}
#endif

void TerrazzoWall::poll_task_(void *self) {
  auto *wall = static_cast<TerrazzoWall *>(self);
  while (true) {
    if (!network::is_connected()) {
      vTaskDelay(pdMS_TO_TICKS(1000));
      continue;
    }
    const int next_ms = wall->client_->step();
    // A wait the main loop cuts short when Home Assistant is back (loop()), so a retry backed off
    // while it restarted doesn't keep the old screens up for up to a minute after.
    // Its retries then start again from the shortest wait.
    if (ulTaskNotifyTake(pdTRUE, pdMS_TO_TICKS(next_ms > 0 ? next_ms : 10)) != 0) wall->client_->retry_soon();
  }
}

void TerrazzoWall::action_task_(void *self) {
  auto *wall = static_cast<TerrazzoWall *>(self);
  ActionMsg *msg;
  bool ahead = true;   // more may be fetched ahead
  while (true) {
    // Between taps, the places a switch may go to are fetched ahead, a place a round: a tap
    // waits for one round at most. Once all are here, a check a while later.
    if (xQueueReceive(s_actions, &msg, pdMS_TO_TICKS(ahead ? 500 : 10000)) != pdTRUE) {
      if (!network::is_connected()) continue;
      const std::string what =
          wall->client_->prefetch([] { return uxQueueMessagesWaiting(s_actions) > 0; });
      ahead = !what.empty();
      if (ahead) ESP_LOGI(TAG, "ahead: %s", what.c_str());
      continue;
    }
    if (!msg->to.empty()) {
      const int64_t t0 = esp_timer_get_time();
      const bool ok = wall->client_->switch_dashboard(msg->to);
      ESP_LOGI(TAG, "to %s in %d ms%s", wall->client_->place().c_str(), (int) ((esp_timer_get_time() - t0) / 1000),
               ok ? "" : " (not loaded)");
      delete msg;
      continue;
    }
    if (msg->screen != 0) {
      wall->page_(msg->screen);
      delete msg;
      continue;
    }
    if (msg->place != 0) {
      const int64_t t0 = esp_timer_get_time();
      const bool ok = wall->client_->switch_place(msg->place, msg->dashboard);
      ESP_LOGI(TAG, "to %s in %d ms%s", wall->client_->place().c_str(), (int) ((esp_timer_get_time() - t0) / 1000),
               ok ? "" : " (not loaded)");
      if (wall->menu_open()) wall->show_menu_();
      delete msg;
      continue;
    }
    if (s_pictures != nullptr) s_pictures->refresh(msg->card);   // a tap shows its pictures as they are now
    const int status = wall->client_->post_action(msg->card, msg->action);
    ESP_LOGD(TAG, "tap %s: HTTP %d", msg->card.c_str(), status);
    delete msg;
  }
}

// The screen timeout, on the player task: backlight off once nobody has touched it for the
// timeout; a touch on a dark screen only wakes it (swallowed: no tap, no long press).
void TerrazzoWall::screen_tick_() {
  const int64_t now = esp_timer_get_time();
  const rcstage::TouchInfo touch = rcstage::lastTouch();
  int request = this->screen_request_.exchange(0);
  if (!this->screen_on_ && touch.down) {
    rcstage::swallowTouch();
    request = 1;
  }
  if (touch.atUs > this->awake_since_us_) this->awake_since_us_ = touch.atUs;
  if (request > 0) {
    this->awake_since_us_ = now;
    if (!this->screen_on_) {
      board::setBrightness(100);
      this->screen_on_ = true;
      ESP_LOGI(TAG, "screen on");
      rcstage::redraw();   // its pictures, not fetched while it was dark, are asked for again
    }
    return;
  }
  const bool timed_out = this->screen_timeout_ms_ > 0 &&
                         now - this->awake_since_us_ > (int64_t) this->screen_timeout_ms_ * 1000;
  if (this->screen_on_ && (request < 0 || timed_out)) {
    board::setBrightness(0);
    this->screen_on_ = false;
    ESP_LOGI(TAG, "screen off");
  }
}

// Raster throughput on this board, logged (it runs a few seconds, off the player's frames):
// rcstage's own bench on the frame buffer in PSRAM, then the same fills drawn as 16-line strips
// in internal RAM and timed for a whole screen, to see what rendering in strips would buy.
static void bench_task(void *) {
  char buf[1024] = {0};
  FILE *out = fmemopen(buf, sizeof buf - 1, "w");
  if (out != nullptr) {
    FILE *was = stdout;
    stdout = out;
    rcstage::bench();
    fflush(out);
    stdout = was;
    fclose(out);
    char *line = strtok(buf, "\n");
    while (line != nullptr) {
      ESP_LOGI(TAG, "psram frame: %s", line);
      line = strtok(nullptr, "\n");
    }
  }
  ESP_LOGI(TAG, "internal RAM: %u free, largest block %u", (unsigned) heap_caps_get_free_size(MALLOC_CAP_INTERNAL),
           (unsigned) heap_caps_get_largest_free_block(MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT));
  for (int lines : {16, 8, 4}) {
    const int w = board::kWidth, strips = board::kHeight / lines;
    auto *px = static_cast<uint32_t *>(heap_caps_malloc((size_t) w * lines * 4, MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT));
    if (px == nullptr) {
      ESP_LOGW(TAG, "bench: no internal block for %d-line strips (%u bytes)", lines, (unsigned) (w * lines * 4));
      continue;
    }
    {
      std::unique_ptr<tvg::SwCanvas> c(tvg::SwCanvas::gen());
      c->target(px, w, w, lines, tvg::ColorSpace::ARGB8888);
      auto run = [&](const char *name, bool clear, int alpha, int rects) {
        const int64_t t0 = esp_timer_get_time();
        for (int s = 0; s < strips; s++) {
          c->remove();
          for (int k = 0; k < rects; k++) {
            auto r = tvg::Shape::gen();
            r->appendRect(0, 0, (float) w, (float) lines);
            r->fill(40, 80, 160, alpha);
            c->add(r);
          }
          c->draw(clear);
          c->sync();
        }
        ESP_LOGI(TAG, "internal %2d-line strips: %-22s %6.1f ms a screen", lines, name,
                 (esp_timer_get_time() - t0) / 1000.0f);
      };
      const int64_t t0 = esp_timer_get_time();
      for (int s = 0; s < strips; s++) memset(px, 0, (size_t) w * lines * 4);
      ESP_LOGI(TAG, "internal %2d-line strips: %-22s %6.1f ms a screen", lines, "memset",
               (esp_timer_get_time() - t0) / 1000.0f);
      run("clear only", true, 255, 0);
      run("opaque rect, no clear", false, 255, 1);
      run("blended rect (a=128)", false, 128, 1);
      run("4 blended rects", false, 128, 4);
    }
    heap_caps_free(px);
  }
  vTaskDelete(nullptr);
}

void TerrazzoWall::bench() { xTaskCreatePinnedToCore(bench_task, "wall_bench", 8192, nullptr, 1, nullptr, 0); }

void TerrazzoWall::page_(int delta) {
  if (this->client_) {
    const int64_t t0 = esp_timer_get_time();
    this->client_->page(delta);
    ESP_LOGI(TAG, "page %+d: handed over in %d ms", delta, (int) ((esp_timer_get_time() - t0) / 1000));
  }
  if (this->menu_open_) this->show_menu_();
}

void TerrazzoWall::toggle_menu() {
  this->menu_open_ = !this->menu_open_;
  this->menu_touched_us_ = esp_timer_get_time();
  if (this->menu_open_) {
    this->show_menu_();
  } else if (s_player != nullptr) {
    s_player->menu("", "");
  }
}

void TerrazzoWall::show_menu_() {
  if (s_player == nullptr || !this->client_) return;
  s_player->menu(this->client_->place(),
                 "\u2039 back  \u00b7  middle: next view (top), next dashboard (bottom)  \u00b7  on \u203a  \u00b7  hold: close");
}

void TerrazzoWall::menu_tap(float x_dp, float y_dp) {
  this->menu_touched_us_ = esp_timer_get_time();
  const float third = this->config_.width_dp / 3.0f;
  if (x_dp < third) {
    this->page_(-1);
  } else if (x_dp > 2 * third) {
    this->page_(1);
  } else {
    this->switch_(1, y_dp > this->config_.height_dp / 2.0f);   // the bottom half: a dashboard
  }
}

void TerrazzoWall::switch_(int delta, bool dashboard) {
  if (s_actions == nullptr) return;
  if (s_player != nullptr && this->menu_open_)   // the menu's banner; not over a closed menu
    s_player->menu(this->client_->place(), dashboard ? "loading the next dashboard\u2026" : "loading the next view\u2026");
  auto *msg = new ActionMsg{"", "", delta, dashboard};
  if (xQueueSend(s_actions, &msg, 0) != pdTRUE) delete msg;
}

// ── Navigation bar ──

// ‹, then a tab per dashboard (at most kTabs, around the current one), then ›. The current
// dashboard's tab names its view too, when it has more than one. Every 300 ms: cheap, and
// rcstage redraws only when the items differ.
void TerrazzoWall::nav_tick_() {
  if ((!this->navigation_ && !this->scroll_bar_) || !this->client_) return;
  const int64_t now = esp_timer_get_time();
  if (now - this->nav_checked_us_ < 300000) return;
  this->nav_checked_us_ = now;
  const auto nav = this->client_->nav();
  // The scroll bar's thumb: the screen showing, of the view's.
  if (this->scroll_bar_ && nav.screens > 0)
    rcstage::setSideThumb((float) nav.screen / nav.screens, 1.0f / nav.screens);
  if (!this->navigation_) return;
  constexpr int kTabs = 6;
  const int n = (int) nav.dashboards.size();
  int at = 0;
  for (int i = 0; i < n; i++)
    if (nav.dashboards[i].first == nav.dashboard) at = i;
  const int first = n <= kTabs ? 0 : std::max(0, std::min(at - kTabs / 2, n - kTabs));
  const int last = std::min(n, first + kTabs);
  std::vector<rcstage::NavItem> items;
  std::vector<std::string> targets;
  items.push_back({"\u2039", 0.5f, false});
  targets.emplace_back("<");
  for (int i = first; i < last; i++) {
    const bool current = nav.dashboards[i].first == nav.dashboard;
    std::string label = nav.dashboards[i].second;
    if (current && nav.views.size() > 1 && nav.view >= 0 && nav.view < (int) nav.views.size() &&
        !nav.views[nav.view].empty())
      label += " \u00b7 " + nav.views[nav.view];
    items.push_back({label, current && nav.views.size() > 1 ? 1.6f : 1.0f, current});
    targets.push_back(nav.dashboards[i].first);
  }
  items.push_back({"\u203a", 0.5f, false});
  targets.emplace_back(">");
  this->nav_targets_ = std::move(targets);
  rcstage::setNavBar(std::move(items));
}

void TerrazzoWall::queue_page_(int delta) {
  if (s_actions == nullptr) return this->page_(delta);
  auto *msg = new ActionMsg{"", "", 0, false, "", delta};
  if (xQueueSend(s_actions, &msg, 0) != pdTRUE) delete msg;
}

// A tap on the scroll bar: above the thumb up a screen, below it down one; none past either end.
void TerrazzoWall::side_tap_(float at) {
  if (!this->client_) return;
  const auto nav = this->client_->nav();
  if (nav.screens <= 1) return;
  const float top = (float) nav.screen / nav.screens, bottom = top + 1.0f / nav.screens;
  if (at < top && nav.screen > 0) this->page_(-1);
  else if (at > bottom && nav.screen + 1 < nav.screens) this->page_(1);
}

void TerrazzoWall::nav_tap_(int index) {
  if (index < 0 || index >= (int) this->nav_targets_.size() || s_actions == nullptr) return;
  const std::string &target = this->nav_targets_[index];
  if (target == "<" || target == ">") {
    this->page_(target == "<" ? -1 : 1);
    return;
  }
  const auto nav = this->client_->nav();
  ActionMsg *msg;
  if (target != nav.dashboard) {
    msg = new ActionMsg{"", "", 0, false, target};   // another dashboard
  } else if (nav.views.size() > 1 && nav.view + 1 < (int) nav.views.size()) {
    msg = new ActionMsg{"", "", 1, false};   // this one's next view
  } else if (nav.views.size() > 1) {
    msg = new ActionMsg{"", "", 0, false, target};   // past its last view: its first
  } else {
    return;   // the one on screen already
  }
  if (xQueueSend(s_actions, &msg, 0) != pdTRUE) delete msg;
}

// ── Home Assistant ──

void DashboardSelect::control(size_t index) { this->parent_->show_dashboard_option(index); }

void ScreenSwitch::write_state(bool on) {
  this->parent_->note_control();
  if (on) this->parent_->wake();
  else this->parent_->sleep();
}

void IdleReturnNumber::restore(float initial) {
  this->pref_ = this->make_entity_preference<float>();
  float minutes = initial;
  if (!this->pref_.load(&minutes) || std::isnan(minutes)) minutes = initial;
  this->parent_->set_idle_return(minutes);
  this->publish_state(minutes);
}

void IdleReturnNumber::control(float minutes) {
  this->pref_.save(&minutes);
  this->parent_->set_idle_return(minutes);
  this->publish_state(minutes);
}

void WallButton::press_action() {
  this->parent_->note_control();
  switch (this->kind_) {
    case NEXT_SCREEN: this->parent_->next_card(); break;
    case PREVIOUS_SCREEN: this->parent_->previous_card(); break;
    case NEXT_VIEW: this->parent_->next_view(); break;
    case PREVIOUS_VIEW: this->parent_->previous_view(); break;
  }
}

void TerrazzoWall::show_dashboard_option(size_t index) {
  if (index >= this->option_paths_.size() || s_actions == nullptr || !this->client_) return;
  const std::string &path = this->option_paths_[index];
  this->note_control();
  this->home_dashboard_ = path;   // what Home Assistant chose is where an idle panel returns
  if (path == this->client_->nav().dashboard) return;   // there already
  auto *msg = new ActionMsg{"", "", 0, false, path};
  if (xQueueSend(s_actions, &msg, 0) != pdTRUE) delete msg;
}

// The main loop, twice a second: the select's options and state and the place, as they are.
// Entities publish from here only (ESPHome's API runs on this task).
void TerrazzoWall::loop() {
  const uint32_t now = millis();
  if (now - this->ha_checked_ms_ < 500 || !this->client_) return;
  this->ha_checked_ms_ = now;
#ifdef USE_API
  // Home Assistant's ESPHome integration connects as it starts: Home Assistant is back, so a
  // poll backing off from its restart goes now. Only Home Assistant subscribes to states: a log
  // viewer connected through the restart doesn't hide it.
  const bool ha =
      api::global_api_server != nullptr && api::global_api_server->is_connected_with_state_subscription();
  if (ha && !this->ha_connected_ && this->poll_handle_ != nullptr) {
    ESP_LOGI(TAG, "Home Assistant connected: polling now");
    xTaskNotifyGive(this->poll_handle_);
  }
  this->ha_connected_ = ha;
#endif
  this->publish_to_ha_();
  this->idle_return_check_();
}

// Back home after a while without a touch or a Home Assistant control: the dashboard Home
// Assistant last chose (else the one the panel started on), its first view and screen. Main
// loop; the switch itself runs on the tap task.
void TerrazzoWall::idle_return_check_() {
  const auto nav = this->client_->nav();
  if (this->home_dashboard_.empty()) this->home_dashboard_ = nav.dashboard;   // where it started
  if (this->idle_return_min_ <= 0 || this->home_dashboard_.empty() || nav.dashboard.empty() ||
      s_actions == nullptr)
    return;
  const int64_t now = esp_timer_get_time();
  const int64_t used = std::max(rcstage::lastTouch().atUs, this->controlled_us_.load());
  if (now - std::max(used, this->awake_since_us_.load()) < (int64_t) (this->idle_return_min_ * 60e6f)) return;
  if (nav.dashboard == this->home_dashboard_ && nav.view == 0 && nav.screen == 0) return;   // home
  ESP_LOGI(TAG, "idle for %.0f min: back to %s", this->idle_return_min_, this->home_dashboard_.c_str());
  this->note_control();   // once; again only after another idle stretch
  auto *msg = new ActionMsg{"", "", 0, false, this->home_dashboard_};
  if (xQueueSend(s_actions, &msg, 0) != pdTRUE) delete msg;
}

void TerrazzoWall::publish_to_ha_() {
  const auto nav = this->client_->nav();
  std::string title, place;
  for (auto &[path, t] : nav.dashboards)
    if (path == nav.dashboard) title = t;
  if (this->dashboard_select_ != nullptr) {
    std::vector<std::string> paths, titles;
    for (auto &[path, t] : nav.dashboards) paths.push_back(path), titles.push_back(t);
    if (titles != this->option_titles_) {
      this->option_paths_ = std::move(paths);
      this->option_titles_ = std::move(titles);
      FixedVector<const char *> options;
      options.init(this->option_titles_.size());
      for (auto &t : this->option_titles_) options.push_back(t.c_str());
      this->dashboard_select_->traits.set_options(options);
      this->published_option_.clear();
#ifdef USE_API
      // Home Assistant reads a select's options only when it connects: ask it to reconnect, as
      // ESPHome does when its encryption key changes. Once a minute at most.
      if (api::global_api_server != nullptr && api::global_api_server->is_connected() &&
          (this->ha_reconnect_ms_ == 0 || millis() - this->ha_reconnect_ms_ > 60000)) {
        this->ha_reconnect_ms_ = millis();
        ESP_LOGI(TAG, "the dashboard list changed: asking Home Assistant to reconnect for it");
        for (auto &c : api::global_api_server->active_clients()) {
          api::DisconnectRequest req;
          if (!c->send_message(req)) ESP_LOGW(TAG, "a disconnect request didn't go out");
        }
      }
#endif
    }
    if (!title.empty() && title != this->published_option_) {
      this->published_option_ = title;
      this->dashboard_select_->publish_state(title.c_str());
    }
  }
  if (this->screen_switch_ != nullptr && (int) this->screen_on_.load() != this->published_screen_) {
    this->published_screen_ = this->screen_on_.load();
    this->screen_switch_->publish_state(this->screen_on_.load());
  }
  if (this->place_sensor_ != nullptr) {
    place = title.empty() ? nav.dashboard : title;
    if (nav.view >= 0 && nav.view < (int) nav.views.size() && !nav.views[nav.view].empty())
      place += " \u00b7 " + nav.views[nav.view];
    if (nav.screens > 1) place += " \u00b7 " + std::to_string(nav.screen + 1) + "/" + std::to_string(nav.screens);
    if (!nav.dashboard.empty() && place != this->published_place_) {
      this->published_place_ = place;
      this->place_sensor_->publish_state(place);
    }
  }
}

#ifdef USE_CAMERA
ScreenImage::~ScreenImage() { heap_caps_free(this->data_); }

namespace {
class ScreenImageReader : public camera::CameraImageReader {
 public:
  void set_image(std::shared_ptr<camera::CameraImage> image) override {
    this->image_ = std::move(image);
    this->offset_ = 0;
  }
  size_t available() const override { return this->image_ ? this->image_->get_data_length() - this->offset_ : 0; }
  uint8_t *peek_data_buffer() override { return this->image_->get_data_buffer() + this->offset_; }
  void consume_data(size_t consumed) override { this->offset_ += consumed; }
  void return_image() override { this->image_.reset(); }

 protected:
  std::shared_ptr<camera::CameraImage> image_;
  size_t offset_ = 0;
};
}  // namespace

camera::CameraImageReader *ScreenCamera::create_image_reader() { return new ScreenImageReader; }

void ScreenCamera::request_image(camera::CameraRequester requester) {
  this->asked_.fetch_or((uint8_t) (1u << requester));
  this->start_();
}

void ScreenCamera::start_() {
  if (this->asked_.load() == 0 || this->busy_.exchange(true)) return;
  // Core 0 at low priority: the player and the panel's scan-out come first.
  if (xTaskCreatePinnedToCore(encode_task_, "screen_jpeg", 6144, this, 1, nullptr, 0) != pdPASS) {
    ESP_LOGW(TAG, "screen capture: no room for its task");
    this->asked_.store(0);
    this->busy_.store(false);
  }
}

// A UI frame is ~20-30 KB at quality 70; the buffer is shrunk to the picture once it's made.
static constexpr size_t kJpegRoom = 64 * 1024;
// Left over for the player while a capture is held: a capture never takes the last of PSRAM.
static constexpr size_t kJpegMargin = 48 * 1024;

void ScreenCamera::encode_task_(void *self) {
  auto *cam = static_cast<ScreenCamera *>(self);
  const uint8_t requesters = cam->asked_.exchange(0);
  const int64_t start = esp_timer_get_time();
  std::shared_ptr<ScreenImage> image;
  const uint16_t *fb = board::frameBuffer();
  if (fb == nullptr) {
    ESP_LOGW(TAG, "screen capture: this board can't read its screen back");
  } else if (reinterpret_cast<uintptr_t>(fb) & 15) {
    ESP_LOGW(TAG, "screen capture: the frame buffer isn't 16-byte aligned");
  } else if (void *room = heap_caps_malloc(kJpegRoom + kJpegMargin, MALLOC_CAP_SPIRAM); room == nullptr) {
    // A trial allocation, not a heap walk: walking stalls the panel's scan-out.
    ESP_LOGW(TAG, "screen capture: no room for a picture now");
  } else {
    heap_caps_free(room);
    auto *out = static_cast<uint8_t *>(heap_caps_malloc(kJpegRoom, MALLOC_CAP_SPIRAM));
    jpeg_enc_config_t config = DEFAULT_JPEG_ENC_CONFIG();
    config.width = board::kWidth;
    config.height = board::kHeight;
    config.src_type = JPEG_PIXEL_FORMAT_RGB565_LE;
    config.subsampling = JPEG_SUBSAMPLE_420;
    config.quality = cam->quality_;
    jpeg_enc_handle_t encoder = nullptr;
    int size = 0;
    jpeg_error_t err = out == nullptr ? JPEG_ERR_NO_MEM : jpeg_enc_open(&config, &encoder);
    if (err == JPEG_ERR_OK) {
      // The frame on show, read as it is: a swap mid-encode can tear the picture.
      err = jpeg_enc_process(encoder, reinterpret_cast<const uint8_t *>(fb), board::kWidth * board::kHeight * 2,
                             out, (int) kJpegRoom, &size);
      jpeg_enc_close(encoder);
    }
    if (err == JPEG_ERR_OK && size > 0) {
      auto *fit = static_cast<uint8_t *>(heap_caps_realloc(out, size, MALLOC_CAP_SPIRAM));
      image = std::make_shared<ScreenImage>(fit != nullptr ? fit : out, (size_t) size, requesters);
      ESP_LOGD(TAG, "screen capture: %d KB in %d ms (stack left %u)", size / 1024,
               (int) ((esp_timer_get_time() - start) / 1000), (unsigned) uxTaskGetStackHighWaterMark(nullptr));
    } else {
      heap_caps_free(out);
      ESP_LOGW(TAG, "screen capture: the JPEG encoder failed (%d)", (int) err);
    }
  }
  {
    std::lock_guard<std::mutex> g(cam->done_lock_);
    cam->done_image_ = std::move(image);
    cam->done_ = true;
  }
  cam->busy_.store(false);
  vTaskDelete(nullptr);
}

// The API is the main loop's: listeners hear of a picture here, never from the encode task.
void ScreenCamera::loop() {
  std::shared_ptr<ScreenImage> image;
  {
    std::lock_guard<std::mutex> g(this->done_lock_);
    if (!this->done_) return;
    this->done_ = false;
    image = std::move(this->done_image_);
  }
  if (image) {
    std::shared_ptr<camera::CameraImage> shared = image;
    for (auto *listener : this->listeners_) listener->on_camera_image(shared);
  }
  this->start_();   // asked again while that one was being made
}

void ScreenCamera::dump_config() {
  ESP_LOGCONFIG(TAG, "Screen capture: JPEG quality %u", this->quality_);
}
#endif

void TerrazzoWall::dump_config() {
  ESP_LOGCONFIG(TAG,
                "Terrazzo wall display:\n"
                "  Home Assistant: %s\n"
                "  Dashboard: %s\n"
                "  View: %s\n"
                "  Card: %s\n"
                "  Style: %s%s\n"
                "  Long-poll wait: %d s\n"
                "  Screen timeout: %u s\n"
                "  Board: %s (%dx%d)",
                this->url_.c_str(), this->config_.dashboard.c_str(), this->config_.view.c_str(),
                this->config_.card.empty() ? "(every card in the view)" : this->config_.card.c_str(),
                this->config_.style.c_str(), this->config_.dark ? ", dark" : "", this->config_.wait_s,
                (unsigned) (this->screen_timeout_ms_ / 1000), board::kName,
                board::kWidth, board::kHeight);
  if (!s_last_boot_notes.empty())
    ESP_LOGCONFIG(TAG, "  Refused before the last reboot: %s", s_last_boot_notes.c_str());
  ESP_LOGCONFIG(TAG, "  Glyph packs: %u glyphs in %u styles; fetched: %s", (unsigned) this->glyphs_.glyph_count(),
                (unsigned) this->glyphs_.style_count(),
                this->glyph_server_.empty() ? "through Home Assistant" : this->glyph_server_.c_str());
  if (this->snapshot_port_ != 0)
    ESP_LOGCONFIG(TAG, "  Snapshot: http://<this device>:%u/snapshot.bmp (and /doc.rc)", this->snapshot_port_);
}

}  // namespace terrazzo_wall
}  // namespace esphome

#endif  // USE_ESP_IDF
