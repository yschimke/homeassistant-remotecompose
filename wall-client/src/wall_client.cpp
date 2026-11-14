#include "wall_client.h"

#include <ArduinoJson.h>

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <set>

namespace tzwall {

static const char *const API = "/api/terrazzo/wall/v1";
static constexpr int kLayoutTimeoutMs = 15000;
static constexpr int kBlobTimeoutMs = 20000;
static constexpr int kActionTimeoutMs = 10000;
static constexpr int kTimerMs = 30000;        // wait=0: poll this often
static constexpr int kMaxBackoffMs = 60000;
static constexpr const char *kPictureSuffix = ".picture_url";

// Query values: card ids, cursors and view paths are [A-Za-z0-9._-], but a dashboard's
// url_path and a palette are the user's.
static std::string encode(const std::string &s) {
  static const char *hex = "0123456789ABCDEF";
  std::string out;
  for (unsigned char c : s) {
    if (isalnum(c) || c == '-' || c == '_' || c == '.' || c == '~' || c == ',') {
      out += (char) c;
    } else {
      out += '%';
      out += hex[c >> 4];
      out += hex[c & 15];
    }
  }
  return out;
}

static std::string text_of(JsonVariantConst v) {
  if (v.isNull()) return "nan";
  if (v.is<bool>()) return v.as<bool>() ? "1" : "0";
  if (v.is<long long>()) return std::to_string(v.as<long long>());
  if (v.is<double>()) {
    const double d = v.as<double>();
    if (std::isnan(d)) return "nan";
    // The player keeps a float: the shortest text that reads back as the same float.
    char b[32];
    const float f = (float) d;
    for (int digits = 6; digits <= 9; digits++) {
      snprintf(b, sizeof b, "%.*g", digits, d);
      if (strtof(b, nullptr) == f) break;
    }
    return b;
  }
  if (v.is<const char *>()) return v.as<const char *>();
  return {};   // an object or array: not a slot value
}

std::string scalar_text(const char *json_scalar) {
  JsonDocument doc;
  if (deserializeJson(doc, json_scalar)) return {};
  return text_of(doc.as<JsonVariantConst>());
}

// The name a document declares a slot under: the wall API's `light.s0.state` is the document's
// `USER:light.s0.state` (RemoteCompose's user domain, as the web embed pushes it); a shared value
// (`HA:sun.sun.elevation`) is its own domain already.
std::string document_name(const std::string &name) {
  if (name.rfind("HA:", 0) == 0 || name.rfind("USER:", 0) == 0) return name;
  return "USER:" + name;
}

static bool ends_with(const std::string &s, const char *suffix) {
  const size_t n = strlen(suffix);
  return s.size() >= n && s.compare(s.size() - n, n, suffix) == 0;
}

WallClient::WallClient(Transport &http, Player &player, Config config)
    : http_(http), player_(player), config_(std::move(config)) {
  config_.wait_s = std::max(0, std::min(55, config_.wait_s));
  if (config_.dashboard.empty()) config_.dashboard = "default";
}

// The reason is already on the status line.
int WallClient::fail() {
  failures_ = std::min(failures_ + 1, 6);
  return std::min(kMaxBackoffMs, 2500 << (failures_ - 1));   // 2.5 s, 5 s, … 60 s
}

static std::string http_reason(const Response &r) {
  if (r.status == 0) return "can't reach Home Assistant";
  if (r.status == 401) return "Home Assistant refused the token";
  if (r.status == 404) {
    JsonDocument doc;
    if (!deserializeJson(doc, r.body) && doc["message"].is<const char *>())
      return doc["message"].as<std::string>();
    return "not found: is the Terrazzo integration installed?";
  }
  if (r.status == 503) return "no card design server (install the Terrazzo Card Designs add-on)";
  JsonDocument doc;
  if (!deserializeJson(doc, r.body) && doc["message"].is<const char *>())
    return "HTTP " + std::to_string(r.status) + ": " + doc["message"].as<std::string>();
  return "HTTP " + std::to_string(r.status);
}

// Picks this config's view (or card) out of a grid: the cards, in reading order.
static bool pick_cards(JsonVariantConst layout, const Config &config, std::vector<Card> &out,
                       int &view_index) {
  JsonArrayConst views = layout["views"];
  if (views.isNull() || views.size() == 0) return false;
  int want = -1;
  if (config.card.empty()) {
    char *end = nullptr;
    const long n = strtol(config.view.c_str(), &end, 10);
    if (!config.view.empty() && *end == '\0') want = n < 0 ? (int) views.size() + (int) n : (int) n;   // -1: the last
    for (size_t i = 0; want < 0 && i < views.size(); i++)
      if (config.view == (views[i]["path"] | "")) want = (int) i;
    if (want < 0 || want >= (int) views.size()) want = 0;
  }
  out.clear();
  for (size_t vi = 0; vi < views.size(); vi++) {
    if (want >= 0 && (int) vi != want) continue;
    for (JsonVariantConst section : views[vi]["sections"].as<JsonArrayConst>()) {
      for (JsonVariantConst c : section["cards"].as<JsonArrayConst>()) {
        Card card;
        card.id = c["id"] | "";
        if (card.id.empty() || (!config.card.empty() && card.id != config.card)) continue;
        card.type = c["type"] | "";
        card.w = c["w"] | 0;
        card.h = c["h"] | 0;
        out.push_back(std::move(card));
        view_index = (int) vi;
      }
    }
  }
  if (want >= 0) view_index = want;
  return true;
}

// `default` isn't a url_path: it's the entry /dashboards marks `"default": true` (the
// integration's chosen dashboard, else Overview, `lovelace`). Looked up once.
bool WallClient::resolve_dashboard() {
  {
    std::lock_guard<std::mutex> g(lock_);
    if (!dash_.empty()) return true;
    if (config_.dashboard != "default") {
      dash_ = config_.dashboard;
      return true;
    }
  }
  Response r = http_.get(std::string(API) + "/dashboards", kLayoutTimeoutMs, "");
  if (r.status != 200) {
    player_.status(http_reason(r));
    return false;
  }
  JsonDocument doc;
  if (deserializeJson(doc, r.body)) {
    player_.status("the dashboard list isn't JSON");
    return false;
  }
  std::string chosen;
  bool found_default = false;
  std::vector<std::pair<std::string, std::string>> list;
  for (JsonVariantConst d : doc["dashboards"].as<JsonArrayConst>()) {
    const std::string path = d["url_path"] | "";
    if (path.empty()) continue;
    list.emplace_back(path, std::string(d["title"] | path.c_str()));
    if (!found_default && (chosen.empty() || (d["default"] | false))) chosen = path;
    found_default |= (d["default"] | false);
  }
  if (chosen.empty()) {
    player_.status("this user can see no dashboards");
    return false;
  }
  std::lock_guard<std::mutex> g(lock_);
  dash_ = chosen;
  set_dashboards_locked(std::move(list));
  return true;
}

bool WallClient::load_dashboards() {
  {
    std::lock_guard<std::mutex> g(lock_);
    if (!dashboards_.empty()) return true;
  }
  Response r = http_.get(std::string(API) + "/dashboards", kLayoutTimeoutMs, "");
  JsonDocument doc;
  if (r.status != 200 || deserializeJson(doc, r.body)) {
    player_.status("dashboards: " + http_reason(r));
    return false;
  }
  std::vector<std::pair<std::string, std::string>> list;
  for (JsonVariantConst d : doc["dashboards"].as<JsonArrayConst>()) {
    const std::string path = d["url_path"] | "";
    if (!path.empty()) list.emplace_back(path, std::string(d["title"] | path.c_str()));
  }
  std::lock_guard<std::mutex> g(lock_);
  set_dashboards_locked(std::move(list));
  return !dashboards_.empty();
}

bool WallClient::switch_place(int delta, bool dashboard) {
  std::lock_guard<std::mutex> one(switch_lock_);
  if (!load_dashboards()) return false;
  std::vector<std::pair<std::string, std::string>> order;   // the dashboards as they were
  int at = 0;
  bool to_dashboard = false;
  {
    std::lock_guard<std::mutex> g(lock_);
    order = dashboards_;
    for (int i = 0; i < (int) order.size(); i++)
      if (order[i].first == dash_) at = i;
    JsonDocument layout;
    const int views = deserializeJson(layout, layout_json_) ? 0 : (int) layout["views"].size();
    const int view = view_index_ + (delta < 0 ? -1 : 1);
    if (!dashboard && views > 0 && view >= 0 && view < views) {
      config_.view = std::to_string(view);   // another view of this dashboard
    } else {
      // The next (or previous) dashboard, on its first (or last) view.
      const int n = (int) dashboards_.size();
      int at = 0;
      for (int i = 0; i < n; i++)
        if (dashboards_[i].first == dash_) at = i;
      dash_ = dashboards_[(size_t) (((at + (delta < 0 ? -1 : 1)) % n + n) % n)].first;
      config_.view = delta < 0 && !dashboard ? "-1" : "0";
      layout_json_.clear();
      view_titles_.clear();
      to_dashboard = true;
    }
    clear_place_locked();
  }
  // A dashboard whose layout is refused (now out of the list): on past it, in the same
  // direction, through the dashboards as they were.
  const int n = (int) order.size();
  for (int step = 1;; step++) {
    if (load_place()) return true;
    std::lock_guard<std::mutex> g(lock_);
    if (!to_dashboard || !refused_.count(dash_) || step >= n) return false;
    const std::string next = order[(size_t) (((at + (delta < 0 ? -1 : 1) * (step + 1)) % n + n) % n)].first;
    if (refused_.count(next)) continue;
    dash_ = next;
    layout_json_.clear();
    view_titles_.clear();
    clear_place_locked();
  }
}

bool WallClient::switch_dashboard(const std::string &url_path) {
  std::lock_guard<std::mutex> one(switch_lock_);
  if (!load_dashboards()) return false;
  std::string from;
  {
    std::lock_guard<std::mutex> g(lock_);
    bool known = false;
    for (auto &d : dashboards_) known |= d.first == url_path;
    if (!known) return false;
    from = dash_;
    dash_ = url_path;
    config_.view = "0";
    layout_json_.clear();
    view_titles_.clear();
    clear_place_locked();
  }
  if (load_place()) return true;
  // Its layout refused (now out of the list): back where the panel was.
  {
    std::lock_guard<std::mutex> g(lock_);
    if (!refused_.count(url_path) || from.empty() || from == url_path) return false;
    dash_ = from;
    config_.view = "0";
    layout_json_.clear();
    view_titles_.clear();
    clear_place_locked();
  }
  load_place();
  return false;
}

void WallClient::clear_place_locked() {
  place_++;   // a layout fetched for the place before is stale: fetch_layout drops it
  relayout_ = true;
  from_cache_ = true;   // a layout fetched before will do: the values' etag says if it's old
  settled_ = false;
  cards_.clear();
  current_ = 0;
  cursor_.clear();
  shown_design_.clear();
}

// The place just chosen, loaded now. The poll in flight asked for the old place: its answer is
// dropped (poll_values compares paths).
bool WallClient::refuses(int status) {
  // Not the token (401, 403), not a timeout or a busy server (408, 429): those pass.
  return status >= 400 && status < 500 && status != 401 && status != 403 && status != 408 && status != 429;
}

void WallClient::refuse_locked(const std::string &url_path) {
  refused_.insert(url_path);
  set_dashboards_locked(std::move(dashboards_));
}

void WallClient::set_dashboards_locked(std::vector<std::pair<std::string, std::string>> list) {
  list.erase(std::remove_if(list.begin(), list.end(), [&](auto &d) { return refused_.count(d.first) > 0; }),
             list.end());
  dashboards_ = std::move(list);
}

bool WallClient::load_place() {
  if (!fetch_layout() || !poll_values()) return false;
  const bool all = fetch_designs();
  std::lock_guard<std::mutex> g(lock_);
  settled_ = all && !cards_.empty();
  return true;
}

const std::vector<uint8_t> *WallClient::blob_locked(const std::string &sha) {
  auto in_use = blobs_.find(sha);
  if (in_use != blobs_.end()) return &in_use->second;
  auto spare = spare_.find(sha);
  if (spare == spare_.end()) return nullptr;
  spare_bytes_ -= spare->second.size();
  spare_order_.erase(std::find(spare_order_.begin(), spare_order_.end(), sha));
  auto &kept = blobs_[sha] = std::move(spare->second);
  spare_.erase(spare);
  return &kept;
}

void WallClient::keep_spare_locked(const std::string &sha, std::vector<uint8_t> rc) {
  if (rc.empty() || rc.size() > kSpareBytes || blobs_.count(sha) || spare_.count(sha)) return;
  while (spare_bytes_ + rc.size() > kSpareBytes && !spare_order_.empty()) {
    auto oldest = spare_.find(spare_order_.front());
    spare_bytes_ -= oldest->second.size();
    spare_.erase(oldest);
    spare_order_.erase(spare_order_.begin());
  }
  spare_bytes_ += rc.size();
  spare_order_.push_back(sha);
  spare_[sha] = std::move(rc);
}

std::string WallClient::prefetch(const std::function<bool()> &waiting) {
  auto interrupted = [&] { return waiting && waiting(); };
  std::string dash, key, query;
  int view = 0;
  bool need_layout = false;
  {
    std::lock_guard<std::mutex> g(lock_);
    if (!settled_ || !screens_mode() || dash_.empty()) return "";
    need_layout = dashboards_.empty();
  }
  if (need_layout) return load_dashboards() ? "dashboards" : "";   // which there are, first
  {
    std::lock_guard<std::mutex> g(lock_);
    // Nearest first: this dashboard's other views, then the dashboards either side, outwards.
    std::vector<std::pair<std::string, int>> places;
    const int views = (int) view_titles_.size();
    for (int d = 1; d < views; d++) places.emplace_back(dash_, (view_index_ + d) % views);
    const int n = (int) dashboards_.size();
    int at = 0;
    for (int i = 0; i < n; i++)
      if (dashboards_[i].first == dash_) at = i;
    for (int d = 1; d < n; d++) {
      const int i = (at + (d % 2 ? (d + 1) / 2 : n - d / 2)) % n;
      if (dashboards_[i].first != dash_ &&
          std::none_of(places.begin(), places.end(), [&](auto &p) { return p.first == dashboards_[i].first; }))
        places.emplace_back(dashboards_[i].first, 0);
    }
    for (auto &[d, v] : places) {
      const std::string k = d + "#" + std::to_string(v);
      if (ahead_done_.count(k)) continue;
      dash = d, view = v, key = k;
      break;
    }
    if (key.empty()) return "";
    need_layout = dash != dash_ && !layouts_.count(dash);
    query = display_query_locked();
  }
  std::string what = key;
  // Its layout, for a switch to start from.
  if (need_layout) {
    Response r = http_.get(std::string(API) + "/dashboards/" + encode(dash), kLayoutTimeoutMs, "");
    std::lock_guard<std::mutex> g(lock_);
    if (r.status != 200) {
      ahead_done_.insert(key);   // not again until the place on screen changes
      if (refuses(r.status)) refuse_locked(dash);
      return what + ": layout HTTP " + std::to_string(r.status);
    }
    layouts_[dash] = std::move(r.body);
    what += ": layout";
  }
  if (interrupted()) return what + " (paused for a tap)";
  // Its screens, for the design of the first. The values are for now: not kept, as a switch
  // shows live ones.
  Response r = http_.get(std::string(API) + "/values/" + encode(dash) + "?screens=" + std::to_string(view) +
                             "&wait=0" + query,
                         kLayoutTimeoutMs, "");
  JsonDocument doc;
  if (r.status != 200 || interrupted()) {
    // Refused or failed: not again. A tap waiting: the place is fetched again next time.
    if (r.status == 200) return what + " (paused for a tap)";
    std::lock_guard<std::mutex> g(lock_);
    ahead_done_.insert(key);
    return what + ": values HTTP " + std::to_string(r.status);
  }
  {
    std::lock_guard<std::mutex> g(lock_);
    ahead_done_.insert(key);
  }
  if (r.status != 200 || deserializeJson(doc, r.body)) return what + ": values HTTP " + std::to_string(r.status);
  r.body.clear();
  const std::string first = doc["screens"][0] | "";
  JsonVariantConst card = doc["cards"][first];
  const std::string sha = card["design"] | "";
  if (sha.empty()) return what + ": no design";
  const std::string path = card["rc"] | (std::string(API) + "/blobs/" + sha + ".rc");
  doc.clear();
  {
    std::lock_guard<std::mutex> g(lock_);
    if (blobs_.count(sha) || spare_.count(sha)) return what + ", design kept";
  }
  if (interrupted()) {   // its design next time
    std::lock_guard<std::mutex> g(lock_);
    ahead_done_.erase(key);
    return what + " (paused for a tap)";
  }
  Response blob = http_.get(path, kBlobTimeoutMs, "");
  if (blob.status != 200 || blob.body.empty()) return what + ": design HTTP " + std::to_string(blob.status);
  std::lock_guard<std::mutex> g(lock_);
  keep_spare_locked(sha, std::vector<uint8_t>(blob.body.begin(), blob.body.end()));
  return what + ", design " + std::to_string(blob.body.size() / 1024) + " KB";
}

WallClient::Nav WallClient::nav() const {
  std::lock_guard<std::mutex> g(lock_);
  return Nav{dashboards_, dash_, view_titles_, view_index_, current_, cards_.size()};
}

void WallClient::reshow() {
  std::lock_guard<std::mutex> g(lock_);
  if (current_ < cards_.size()) show_current_locked(true, {});
}

std::string WallClient::place() const {
  std::lock_guard<std::mutex> g(lock_);
  std::string dash = dash_;
  for (auto &[path, title] : dashboards_)
    if (path == dash_) dash = title;
  std::string view;
  JsonDocument layout;
  if (!deserializeJson(layout, layout_json_)) {
    JsonVariantConst v = layout["views"][view_index_];
    view = v["title"] | "";
  }
  std::string out = dash;
  if (!view.empty()) out += " \u00b7 " + view;
  if (!cards_.empty())
    out += " \u00b7 " + std::to_string(current_ + 1) + "/" + std::to_string(cards_.size());
  return out;
}

std::string WallClient::dashboard() const {
  std::lock_guard<std::mutex> g(lock_);
  return dash_;
}

bool WallClient::fetch_layout() {
  if (!resolve_dashboard()) return false;
  std::string etag, dash, cached;
  Config config;
  uint32_t place;
  {
    std::lock_guard<std::mutex> g(lock_);
    etag = layout_etag_.empty() || cards_.empty() ? "" : "\"" + layout_etag_ + "\"";
    dash = dash_;
    config = config_;   // its view, read with the place: a switch may change both meanwhile
    place = place_;
    // Just switched: the layout this dashboard had, or one fetched ahead, without a request.
    if (from_cache_) {
      from_cache_ = false;
      auto it = layouts_.find(dash);
      if (!layout_json_.empty()) cached = layout_json_;
      else if (it != layouts_.end()) cached = it->second;
    }
  }
  Response r;
  if (!cached.empty()) {
    r.status = 200;
    r.body = std::move(cached);
  } else {
    r = http_.get(std::string(API) + "/dashboards/" + encode(dash), kLayoutTimeoutMs, etag);
  }
  if (r.status == 304) {
    std::lock_guard<std::mutex> g(lock_);
    if (place == place_) relayout_ = false;
    return true;
  }
  if (r.status != 200) {
    player_.status(http_reason(r));
    if (refuses(r.status)) {
      std::lock_guard<std::mutex> g(lock_);
      refuse_locked(dash);
    }
    return false;
  }
  JsonDocument doc;
  if (deserializeJson(doc, r.body)) {
    player_.status("the dashboard's layout isn't JSON");
    return false;
  }
  std::vector<Card> cards;
  int view = 0;
  if (!pick_cards(doc.as<JsonVariantConst>(), config, cards, view)) {
    player_.status("the dashboard has no views");
    return false;
  }
  if (cards.empty()) {
    player_.status(config.card.empty() ? "this view has no cards" : "no card " + config.card);
    return false;
  }
  std::vector<std::string> titles;
  for (JsonVariantConst v : doc["views"].as<JsonArrayConst>()) titles.emplace_back(v["title"] | "");
  std::lock_guard<std::mutex> g(lock_);
  layouts_[dash] = r.body;
  // Switched while this was in flight: the new place is loaded (or loading) already.
  if (place != place_) return true;
  view_titles_ = std::move(titles);
  // A screen at a time: the first poll answers with the view's screens.
  if (screens_mode()) {
    const bool same_view = view == view_index_ && !layout_json_.empty();
    if (!same_view) cards_.clear(), current_ = 0;
    view_index_ = view;
    layout_etag_ = doc["etag"] | "";
    relayout_ = false;
    dashboard_url_path_ = doc["dashboard"] | "";
    layout_json_ = std::move(r.body);
    cursor_.clear();
    return true;
  }
  // Keep what's known of a card that's still here (its design, values) across a relayout.
  for (Card &c : cards)
    for (const Card &old : cards_)
      if (old.id == c.id) {
        c.design = old.design, c.rc = old.rc, c.error = old.error, c.values = old.values;
        break;
      }
  const std::string was = current_ < cards_.size() ? cards_[current_].id : "";
  cards_ = std::move(cards);
  current_ = 0;
  for (size_t i = 0; i < cards_.size(); i++)
    if (cards_[i].id == was) current_ = i;
  view_index_ = view;
  layout_etag_ = doc["etag"] | "";
  relayout_ = false;
  dashboard_url_path_ = doc["dashboard"] | "";
  layout_json_ = std::move(r.body);
  cursor_.clear();   // a full answer for the new set of cards
  return true;
}

std::string WallClient::values_path() const {
  std::string p = std::string(API) + "/values/" + encode(dash_);
  if (screens_mode()) {
    p += "?screens=" + std::to_string(view_index_);
  } else {
    std::string ids;
    for (const Card &c : cards_) ids += (ids.empty() ? "" : ",") + c.id;
    p += "?cards=" + encode(ids);
  }
  if (!cursor_.empty()) p += "&since=" + encode(cursor_);
  // The first answer at once; then hold the request until something changes.
  p += "&wait=" + std::to_string(cursor_.empty() ? 0 : config_.wait_s);
  return p + display_query_locked();
}

// How the design server should make designs for this display: the values query's tail.
std::string WallClient::display_query_locked() const {
  std::string p;
  if (config_.style != "classic") p += "&style=" + encode(config_.style);
  if (config_.dark) p += "&dark=1";
  // A small player: the design server makes each design for this display, its sizes numbers
  // rather than expressions over density and font size. The player lays documents out in dp
  // and scales to the panel itself, so the display is always density 1.
  p += "&density=1&font_scale=1";
  // One card on the whole screen: each design's canvas is the screen, so it lays out at exactly
  // the screen's size.
  if (config_.width_dp > 0 && config_.height_dp > 0)
    p += "&width=" + std::to_string(config_.width_dp) + "&height=" + std::to_string(config_.height_dp);
  if (config_.picture_max > 0) p += "&picture_max=" + std::to_string(config_.picture_max);
  return p;
}

bool WallClient::poll_values() {
  std::string path;
  {
    std::lock_guard<std::mutex> g(lock_);
    path = values_path();
  }
  const bool waits = path.find("&wait=0") == std::string::npos;
  Response r = http_.get(path, (waits ? config_.wait_s : 0) * 1000 + 15000, "");
  if (r.status != 200) {
    player_.status(http_reason(r));
    return false;
  }
  JsonDocument doc;
  if (deserializeJson(doc, r.body)) {
    player_.status("the values answer isn't JSON");
    return false;
  }
  std::lock_guard<std::mutex> g(lock_);
  if (path != values_path()) return true;   // paged to another view meanwhile: ask again
  const std::string etag = doc["etag"] | "";
  if (!etag.empty() && etag != layout_etag_) {
    relayout_ = true;      // edited: fetch the grid next
    ahead_done_.clear();   // and what was fetched ahead may be out of date
  }
  cursor_ = doc["cursor"] | "";
  bool design_changed = false;
  if (doc["screens"].is<JsonArrayConst>()) {
    take_screens_locked(doc["screens"].as<JsonArrayConst>());
    design_changed = true;
  }
  std::vector<std::string> changed;
  for (JsonPairConst kv : doc["cards"].as<JsonObjectConst>()) {
    auto it = std::find_if(cards_.begin(), cards_.end(), [&](const Card &c) { return c.id == kv.key().c_str(); });
    if (it == cards_.end()) continue;
    JsonObjectConst a = kv.value().as<JsonObjectConst>();
    const bool current = (size_t) (it - cards_.begin()) == current_;
    if (a["hidden"] | false) {   // a conditional hidden by its conditions: paged past
      it->hidden = true;
      it->error.clear(), it->design.clear(), it->rc.clear(), it->values.clear();
      design_changed |= current;
      continue;
    }
    it->hidden = false;
    if (a["error"].is<const char *>()) {
      it->error = a["error"].as<std::string>();
      it->design.clear(), it->rc.clear(), it->values.clear();
      design_changed |= current;
      continue;
    }
    it->error.clear();
    if (a["design"].is<const char *>()) {   // a whole card: its values are all of them
      it->design = a["design"].as<std::string>();
      it->rc = a["rc"] | (std::string(API) + "/blobs/" + it->design + ".rc");
      it->values.clear();
      it->pictures.clear();
      design_changed |= current;
    }
    for (JsonPairConst v : a["values"].as<JsonObjectConst>()) {
      std::string name = v.key().c_str();
      if (ends_with(name, kPictureSuffix)) {   // the device's picture loader's, not a document slot
        it->pictures[name.substr(0, name.size() - strlen(kPictureSuffix))] = text_of(v.value());
        continue;
      }
      std::string value = text_of(v.value());
      auto old = it->values.find(name);
      if (old != it->values.end() && old->second == value) continue;
      it->values[name] = value;
      if (current) changed.push_back(name);
    }
  }
  failures_ = 0;
  if (skip_hidden_locked(1)) design_changed = true, changed.clear();
  if (design_changed || !changed.empty()) show_current_locked(design_changed, changed);
  return true;
}

// The view's screens, in order, keeping what's known of one still here and the one on screen.
void WallClient::take_screens_locked(JsonArrayConst ids) {
  std::vector<Card> cards;
  for (JsonVariantConst id : ids) {
    Card card;
    card.id = id.as<std::string>();
    card.type = "screen";
    for (const Card &old : cards_)
      if (old.id == card.id) card = old;
    cards.push_back(std::move(card));
  }
  const std::string was = current_ < cards_.size() ? cards_[current_].id : "";
  cards_ = std::move(cards);
  current_ = 0;
  for (size_t i = 0; i < cards_.size(); i++)
    if (cards_[i].id == was) current_ = i;
}

// Moves off a hidden current card, or one with no design, to the next that shows in [delta]'s
// direction. True when it moved. With none it stays put (the last screen stands, with a status).
bool WallClient::skip_hidden_locked(int delta) {
  const int n = (int) cards_.size();
  auto skipped = [](const Card &c) { return c.hidden || !c.error.empty(); };
  if (current_ >= cards_.size() || !skipped(cards_[current_])) return false;
  for (int i = 1; i < n; i++) {
    const size_t next = (size_t) ((((int) current_ + delta * i) % n + n) % n);
    if (!skipped(cards_[next])) {
      current_ = next;
      return true;
    }
  }
  return false;
}

bool WallClient::fetch_designs() {
  std::set<std::string> wanted;
  std::vector<std::pair<std::string, std::string>> missing;   // sha, path
  {
    std::lock_guard<std::mutex> g(lock_);
    for (const Card &c : cards_) {
      if (c.design.empty()) continue;
      wanted.insert(c.design);
      if (!blobs_.count(c.design) &&
          std::none_of(missing.begin(), missing.end(), [&](auto &m) { return m.first == c.design; }))
        missing.emplace_back(c.design, c.rc);
    }
    // Designs are shared and content-addressed: those this view no longer uses are spare.
    for (auto it = blobs_.begin(); it != blobs_.end();) {
      if (wanted.count(it->first)) {
        ++it;
        continue;
      }
      const std::string sha = it->first;
      std::vector<uint8_t> rc = std::move(it->second);
      it = blobs_.erase(it);
      keep_spare_locked(sha, std::move(rc));
    }
    // Those fetched ahead, or kept from a place before: no request.
    for (auto it = missing.begin(); it != missing.end();)
      it = blob_locked(it->first) != nullptr ? missing.erase(it) : std::next(it);
    if (current_ < cards_.size() && shown_design_ != cards_[current_].design) show_current_locked(true, {});
  }
  bool all = true;
  for (auto &[sha, path] : missing) {
    Response r = http_.get(path, kBlobTimeoutMs, "");
    if (r.status != 200 || r.body.empty()) {
      player_.status("design " + sha.substr(0, 8) + ": " + http_reason(r));
      all = false;
      continue;
    }
    std::lock_guard<std::mutex> g(lock_);
    blobs_[sha].assign(r.body.begin(), r.body.end());
    if (current_ < cards_.size() && cards_[current_].design == sha) show_current_locked(true, {});
  }
  return all;
}

// Under lock_. A new design goes to the screen with every value of its card; otherwise just
// the slots that changed.
void WallClient::show_current_locked(bool design_changed, const std::vector<std::string> &changed) {
  if (current_ >= cards_.size()) return;
  const Card &c = cards_[current_];
  if (c.hidden) {
    player_.status("every card is hidden by its conditions");
    return;
  }
  if (!c.error.empty()) {
    player_.status(c.id + ": " + c.error);
    return;
  }
  const std::vector<uint8_t> *blob = blob_locked(c.design);
  if (blob == nullptr) return;   // fetch_designs shows it once it's here
  if (design_changed || shown_design_ != c.design) {
    // Values first: the player keeps them (sticky) and the new document starts with them, so its
    // first frame shows live data, not the bake.
    for (auto &[name, value] : c.values) player_.set_named(document_name(name), value);
    player_.show(*blob, c.id + " " + c.type);
    shown_design_ = c.design;
    // Laying the next screen out ahead (preload_next_locked) halves a page's first frame, but a
    // second document and its layout took the panel's free PSRAM to nothing with pictures
    // showing, and it crashed: not done until it fits.
  } else {
    for (const std::string &name : changed) player_.set_named(document_name(name), c.values.at(name));
  }
  player_.status("");
}

void WallClient::preload_next_locked() {
  const int n = (int) cards_.size();
  for (int step = 1; step < n; step++) {
    const Card &c = cards_[(size_t) ((((int) current_ + direction_ * step) % n + n) % n)];
    if (c.hidden || !c.error.empty()) continue;   // paged past
    const std::vector<uint8_t> *blob = blob_locked(c.design);
    if (blob == nullptr) return;   // not here yet
    std::vector<std::pair<std::string, std::string>> values;
    for (auto &[name, value] : c.values) values.emplace_back(document_name(name), value);
    player_.preload(*blob, c.id + " " + c.type, values);
    return;
  }
}

int WallClient::step() {
  bool need_layout;
  {
    std::lock_guard<std::mutex> g(lock_);
    need_layout = relayout_ || layout_json_.empty() || (cards_.empty() && !screens_mode());
  }
  if (need_layout && !fetch_layout()) return fail();
  if (!poll_values()) return fail();
  const bool all = fetch_designs();
  {
    std::lock_guard<std::mutex> g(lock_);
    settled_ = all && !cards_.empty();
  }
  if (all) {
    // A round with every design here: a status from an earlier one (a design that didn't
    // arrive) is over, unless the card on screen has its own.
    std::lock_guard<std::mutex> g(lock_);
    if (current_ < cards_.size() && cards_[current_].error.empty() && !cards_[current_].hidden &&
        blobs_.count(cards_[current_].design))
      player_.status("");
  }
  failures_ = 0;
  return config_.wait_s == 0 ? kTimerMs : 0;
}

void WallClient::page(int delta) {
  std::lock_guard<std::mutex> g(lock_);
  if (cards_.size() < 2) return;
  const int n = (int) cards_.size();
  current_ = (size_t) ((((int) current_ + delta) % n + n) % n);
  direction_ = delta < 0 ? -1 : 1;
  skip_hidden_locked(direction_);
  show_current_locked(true, {});
}

std::string WallClient::current_card() const {
  std::lock_guard<std::mutex> g(lock_);
  return current_ < cards_.size() ? cards_[current_].id : "";
}

std::string WallClient::picture_url(const std::string &card_id, const std::string &slot) const {
  std::lock_guard<std::mutex> g(lock_);
  for (const Card &c : cards_) {
    if (c.id != card_id) continue;
    auto it = c.pictures.find(slot);
    return it == c.pictures.end() ? "" : it->second;
  }
  return "";
}

int WallClient::post_action(const std::string &card_id, const std::string &action) {
  std::string json = "{}";   // a tap on the card itself
  if (!action.empty()) {
    JsonDocument body;
    body["action"] = action;
    json.clear();
    serializeJson(body, json);
  }
  const std::string dash = dashboard();
  if (dash.empty()) return 0;   // no layout yet: nothing on screen to tap
  Response r = http_.post_json(std::string(API) + "/actions/" + encode(dash) + "/" + encode(card_id),
                               json, kActionTimeoutMs);
  if (r.status != 200) {
    player_.status("tap: " + http_reason(r));
    return r.status;
  }
  // The state change comes back through the poll. A Navigate within this dashboard switches
  // view; MoreInfo and Url have nothing to show on a panel.
  JsonDocument doc;
  if (deserializeJson(doc, r.body)) return r.status;
  JsonVariantConst a = doc["action"];
  if (std::string(a["kind"] | "") != "Navigate" || !config_.card.empty()) return r.status;
  std::string path = a["path"] | "";
  std::lock_guard<std::mutex> g(lock_);
  const std::string prefix = "/" + dashboard_url_path_ + "/";
  if (dashboard_url_path_.empty() || path.compare(0, prefix.size(), prefix) != 0) return r.status;
  config_.view = path.substr(prefix.size());
  JsonDocument layout;
  std::vector<Card> cards;
  int view = view_index_;
  if (deserializeJson(layout, layout_json_) || !pick_cards(layout.as<JsonVariantConst>(), config_, cards, view) ||
      cards.empty())
    return r.status;
  if (screens_mode()) cards.clear();   // the next poll answers with this view's screens
  for (Card &c : cards)
    for (const Card &old : cards_)
      if (old.id == c.id) c = old;
  cards_ = std::move(cards);
  view_index_ = view;
  current_ = 0;
  cursor_.clear();   // the next poll is a full one for this view's cards
  shown_design_.clear();
  show_current_locked(true, {});
  return r.status;
}

std::vector<Card> WallClient::cards() const {
  std::lock_guard<std::mutex> g(lock_);
  return cards_;
}

std::string WallClient::cursor() const {
  std::lock_guard<std::mutex> g(lock_);
  return cursor_;
}

int WallClient::view_index() const {
  std::lock_guard<std::mutex> g(lock_);
  return view_index_;
}

}  // namespace tzwall
