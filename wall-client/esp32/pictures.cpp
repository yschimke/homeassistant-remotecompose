#include "pictures.h"

#include <algorithm>
#include <cstdlib>
#include <vector>

#include "esp_heap_caps.h"
#include "esp_jpeg_dec.h"
#include "esp_log.h"
#include "esp_timer.h"

namespace tzwall {

static const char *const TAG = "wall_pictures";

static constexpr int64_t kRefreshUs = 60 * 1000000LL;
static constexpr int64_t kFreshEveryUs = 5 * 1000000LL;   // a tap's fetch, at most this often
static constexpr int64_t kRetryUs = 10 * 1000000LL;       // after a fetch that failed
static constexpr int64_t kKeepUs = 60 * 1000000LL;        // another screen's pictures, after it went
static constexpr size_t kKeepEntries = 16;
static constexpr int64_t kSettleUs = 400 * 1000;           // a size held this long is the one to fetch
static constexpr size_t kMaxBytes = 128 * 1024;            // one JPEG

// A picture_url's `v`: which picture of its entity (it moves with its URL in Home Assistant).
static std::string version_of(const std::string &url) {
  const size_t at = url.find("v=");
  return at == std::string::npos ? "" : url.substr(at + 2, url.find('&', at) - at - 2);
}

PictureCache::PictureCache(std::string url, std::string token, WallClient &client, Hooks hooks)
    : http_(std::move(url), std::move(token)), client_(client), hooks_(std::move(hooks)) {
  http_.max_body = kMaxBytes;
}

void PictureCache::start(int core, int priority) {
  xTaskCreatePinnedToCore(task_, "wall_pics", 6144, this, priority, &handle_, core);
}

// A picture's entry: the card showing and the slot (`rc-slot://camera.s0/entity_picture`).
bool PictureCache::key_of_(const std::string &url, std::string &key) const {
  static const std::string kScheme = "rc-slot://";
  if (url.compare(0, kScheme.size(), kScheme) != 0) return false;
  const std::string slot = url.substr(kScheme.size(), url.find('/', kScheme.size()) - kScheme.size());
  key = hooks_.current_card() + "|" + slot;
  return true;
}

uint32_t PictureCache::version(const std::string &url, int w, int h) {
  std::string key;
  if (!key_of_(url, key)) return 0;
  // A layout's first frame can give a box no height yet: not one to fetch for.
  if (w < 16 || h < 16) return 0;
  std::lock_guard<std::mutex> g(lock_);
  const int64_t now = esp_timer_get_time();
  Entry &e = entries_[key];
  if (e.card.empty()) e.card = key.substr(0, key.find('|')), e.slot = key.substr(key.find('|') + 1);
  e.seen_us = now;
  if (std::abs(e.w - w) > 2 || std::abs(e.h - h) > 2) {
    e.w = w, e.h = h;
    e.sized_us = now;   // a layout settling moves it a few times: fetched once it stays
    e.fetched_us = 0;   // another size: fetched again
    wake_();
  } else if (e.jpeg == nullptr) {
    wake_();
  }
  return e.jpeg == nullptr ? 0 : e.version;
}

void PictureCache::draw(const std::string &url, const int *box, const int *within, uint32_t *pixels, int stride) {
  std::string key;
  if (!key_of_(url, key)) return;
  std::shared_ptr<const std::string> jpeg;
  {
    std::lock_guard<std::mutex> g(lock_);
    auto it = entries_.find(key);
    if (it == entries_.end() || it->second.jpeg == nullptr) return;
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
  draw_us_ += esp_timer_get_time() - t0;
  draws_++;
}

void PictureCache::take_draw_stats(int &draws, int64_t &us) {
  draws = draws_.exchange(0);
  us = draw_us_.exchange(0);
}

void PictureCache::refresh(const std::string &card) {
  std::lock_guard<std::mutex> g(lock_);
  bool any = false;
  for (auto &kv : entries_)
    if (kv.second.card == card) kv.second.fresh = true, any = true;
  if (any) wake_();
}

void PictureCache::wake_() {
  if (handle_ != nullptr) xTaskNotifyGive(handle_);
}

void PictureCache::task_(void *self) {
  auto *cache = static_cast<PictureCache *>(self);
  while (true) {
    ulTaskNotifyTake(pdTRUE, pdMS_TO_TICKS(cache->settling_ ? 100 : 5000));
    cache->settling_ = false;
    while (cache->fetch_one_()) {
    }
  }
}

// One picture due on the screen showing fetched; false when none is.
bool PictureCache::fetch_one_() {
  const std::string card = hooks_.current_card();
  const int64_t now = esp_timer_get_time();
  std::string key, path, etag;
  bool fresh = false, reused = false;
  {
    std::lock_guard<std::mutex> g(lock_);
    // Another screen's pictures: kept a while, for a screen shown again or one sharing them;
    // dropped once it's been gone a minute, or the oldest first past kKeepEntries.
    for (auto it = entries_.begin(); it != entries_.end();)
      it = it->second.card == card || now - it->second.seen_us < kKeepUs ? std::next(it) : entries_.erase(it);
    while (entries_.size() > kKeepEntries) {
      auto oldest = entries_.end();
      for (auto it = entries_.begin(); it != entries_.end(); ++it)
        if (it->second.card != card && (oldest == entries_.end() || it->second.seen_us < oldest->second.seen_us))
          oldest = it;
      if (oldest == entries_.end()) break;
      entries_.erase(oldest);
    }
    if (!hooks_.screen_on()) return false;
    for (auto &kv : entries_) {
      Entry &e = kv.second;
      if (e.card != card) continue;   // only the screen showing is fetched
      const std::string url = client_.picture_url(e.card, e.slot);
      if (url.empty() || e.w <= 0 || e.h <= 0) continue;
      if (e.failed_us != 0 && now - e.failed_us < kRetryUs) continue;
      if (now - e.sized_us < kSettleUs) {
        settling_ = true;   // its layout is still settling: looked at again shortly
        continue;
      }
      const bool tapped = e.fresh && now - e.fresh_us >= kFreshEveryUs;
      if (e.jpeg != nullptr && url == e.url && now - e.fetched_us < kRefreshUs && !tapped) continue;
      key = kv.first;
      fresh = tapped;
      e.fresh = false;
      if (tapped) e.fresh_us = now;
      // The same picture at the same size on a screen shown lately (screens overlap): its bytes.
      if (e.jpeg == nullptr && !version_of(url).empty()) {
        const Entry *same = nullptr;
        for (auto &o : entries_)
          if (&o.second != &e && o.second.jpeg != nullptr && version_of(o.second.url) == version_of(url) &&
              std::abs(o.second.w - e.w) <= 2 && std::abs(o.second.h - e.h) <= 2)
            same = &o.second;
        if (same != nullptr) {
          e.jpeg = same->jpeg, e.etag = same->etag, e.url = url, e.fetched_us = same->fetched_us;
          e.version++;
          reused = true;
          key.clear();
          continue;
        }
      }
      e.fetched_us = now;   // not asked again until it's due, whatever this fetch does
      if (url != e.url) e.etag.clear();
      e.url = url;
      etag = e.etag;
      // Whole blocks of 8, as the strip decoder needs: cropped to the box as it's drawn.
      const int w = (e.w + 7) / 8 * 8, h = (e.h + 7) / 8 * 8;
      path = url + (url.find('?') == std::string::npos ? "?" : "&") + "w=" + std::to_string(w) +
             "&h=" + std::to_string(h) + "&fit=cover&format=jpeg" + (fresh ? "&fresh=1" : "");
      break;
    }
  }
  if (reused) hooks_.redraw();
  if (key.empty()) return false;
  Response r = http_.get(path, 10000, etag);
  if (r.status == 304) return true;   // as it was
  if (r.status != 200 || r.body.empty()) {
    ESP_LOGW(TAG, "picture %s: HTTP %d", key.c_str(), r.status);
    std::lock_guard<std::mutex> g(lock_);
    auto it = entries_.find(key);
    if (it != entries_.end()) it->second.failed_us = esp_timer_get_time();
    return true;   // the next one, if any; this one again in kRetryUs
  }
  auto jpeg = std::make_shared<const std::string>(std::move(r.body));
  {
    std::lock_guard<std::mutex> g(lock_);
    auto it = entries_.find(key);
    if (it == entries_.end()) return true;   // its screen went meanwhile
    it->second.jpeg = std::move(jpeg);
    it->second.etag = r.etag;
    it->second.failed_us = 0;
    it->second.version++;
  }
  hooks_.redraw();
  return true;
}

}  // namespace tzwall
