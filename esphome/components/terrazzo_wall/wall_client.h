// A wall display API client (docs/architecture/wall-display-api.md): one dashboard view's cards,
// their designs (.rc, cached by sha) and live named values, shown one card at a time.
//
// Platform-neutral: HTTP comes through Transport and drawing through Player, so the same code
// runs on a device (its HTTP client and RemoteCompose player) and in the host test
// (wall-client/test/wall_client_test.cpp). JSON is ArduinoJson, as ESPHome's `json` component ships.
//
//   GET /dashboards/{d}                 the grid; again when its etag changes
//   GET /values/{d}?cards=…&since=…     long poll: designs and values, then what changed
//   GET /blobs/{sha}.rc                 each design not cached
//   POST /actions/{d}/{card}            a document's `ha` host action, verbatim
#pragma once

#include <cstdint>
#include <functional>
#include <map>
#include <mutex>
#include <set>
#include <string>
#include <vector>

#include <ArduinoJson.h>

namespace tzwall {

struct Response {
  int status = 0;      // HTTP status; 0 when the server couldn't be reached
  std::string body;
  std::string etag;    // the ETag header, quotes included
};

// HTTP to Home Assistant. Paths are under the HA base URL ("/api/terrazzo/wall/v1/…"); the
// transport adds `Authorization: Bearer <token>`. Called from more than one task at once.
class Transport {
 public:
  virtual ~Transport() = default;
  virtual Response get(const std::string &path, int timeout_ms, const std::string &if_none_match) = 0;
  virtual Response post_json(const std::string &path, const std::string &json, int timeout_ms) = 0;
};

// The screen. show() replaces the document; set_named() pushes one named slot (as text);
// status() is a one-line state for a banner or the log (empty: all good).
class Player {
 public:
  virtual ~Player() = default;
  virtual void show(const std::vector<uint8_t> &rc, const std::string &name) = 0;
  virtual void set_named(const std::string &name, const std::string &value) = 0;
  virtual void status(const std::string &text) = 0;
  // Lay [rc] out ahead, unseen, with its [values]: the next screen a page is likely to show, so a
  // show() of it is quick. A player that can't does nothing.
  virtual void preload(const std::vector<uint8_t> & /*rc*/, const std::string & /*name*/,
                       const std::vector<std::pair<std::string, std::string>> & /*values*/) {}
};

struct Config {
  std::string dashboard = "default";   // a url_path, or `default`: the one /dashboards marks default
  std::string view = "0";              // a view's path, or its index
  std::string card;                    // one card id only (a single-card device); empty: the view's
  std::string style = "classic";
  bool dark = false;
  int wait_s = 25;                     // long-poll hold, 0..55; 0 polls on a timer instead
  int width_dp = 0, height_dp = 0;     // the screen in dp: each design's canvas; 0 keeps the design's
  // Page through the view a screen at a time (the server packs its cards into screens), rather
  // than a card at a time. Needs the screen size; ignored for a single card.
  bool screens = true;
  int picture_max = 0;                 // the most of the screen one picture takes, in %; 0: no limit
};

struct Card {
  std::string id, type;
  int w = 0, h = 0;                    // its box on the 12-column grid
  std::string design, rc;              // sha and blob path; empty until resolved
  std::string error;                   // the server's per-card error, if any
  bool hidden = false;                 // a `conditional` whose conditions don't hold: not shown
  std::map<std::string, std::string> values;   // named slot -> value as text
  std::map<std::string, std::string> pictures; // slot (`camera.s0`) -> where its picture is
};

// The value of a JSON scalar as the player's named slots take it: booleans 1/0, integers with
// no point, floats with enough digits to round-trip a float, `null` (a number with no JSON form:
// unavailable, NaN) as "nan". Exposed for the test.
std::string scalar_text(const char *json_scalar);
// The name a document declares a wall API value under: `USER:` + it, unless it has a domain.
std::string document_name(const std::string &name);

class WallClient {
 public:
  WallClient(Transport &http, Player &player, Config config);

  // One round: fetch the layout when needed, one /values poll, the designs it names. Returns
  // how long to wait before the next round (ms): 0 after a good long poll, a back-off after
  // an error, the timer for wait=0. Run it from one task, in a loop.
  int step();
  // Home Assistant is back (it connected to the device): the next error backs off from the
  // shortest wait again, not from where a restart's errors left it. step()'s task.
  void retry_soon() { failures_ = 0; }

  // Page through the view's cards (+1 / -1). Any task; shows the cached design at once.
  void page(int delta);
  // The card on screen now (for an action), or "" before the first layout.
  std::string current_card() const;
  // Where card [card_id]'s slot [slot] (`camera.s0`) has its picture (the long poll's
  // `<slot>.picture_url`), or empty.
  std::string picture_url(const std::string &card_id, const std::string &slot) const;
  // Go to the next (+1) or previous (-1) view, past the dashboard's last to the next dashboard
  // this user can see; with [dashboard], straight to the next (or previous) dashboard's first
  // view. Blocking (it loads the new place's layout, values and designs at once, not after the
  // poll in flight): run it off the player task.
  bool switch_place(int delta, bool dashboard = false);
  // Go to this dashboard (a url_path of /dashboards), on its first view. Blocking, as above.
  bool switch_dashboard(const std::string &url_path);
  // What a navigation bar shows: the dashboards this user can see (url_path, title), the one
  // in use, its views' titles and the one showing. Cheap: no JSON is read.
  struct Nav {
    std::vector<std::pair<std::string, std::string>> dashboards;
    std::string dashboard;
    std::vector<std::string> views;
    int view = 0;
    size_t screen = 0, screens = 0;   // the screen (or card) on show, of how many
  };
  // The dashboards this user can see, fetched now if they aren't yet (blocking): for a list
  // that must be known early, such as a Home Assistant select's options.
  bool load_dashboard_list() { return resolve_dashboard() && load_dashboards(); }
  Nav nav() const;
  // Ahead of a switch, while nothing else is going on: where the panel may go next (this
  // dashboard's other views, then the other dashboards' first views, nearest first), its layout
  // and the design of its first screen, kept so that a switch there needs one request (its
  // live values) instead of three. Nothing until every design of the place on screen is here.
  // A few requests a call (blocking: run it off the player task, between taps). Returns what it
  // fetched ("" when there's nothing left to fetch).
  // [waiting]: asked between requests; true (a tap queued) stops the round there, unfinished
  // (that place is fetched again next time), so a tap waits for one request at most.
  std::string prefetch(const std::function<bool()> &waiting = {});
  // The card on screen, shown again from its design: its text measured again (glyphs it lacked
  // have arrived, and are as wide as they are now).
  void reshow();
  // Where the panel is, for a menu: "Climate · Rooms · 2/3".
  std::string place() const;
  // Post a document's `ha` host action for `card_id` (blocking: run it off the player task).
  // Follows a Navigate to another view of this dashboard. Returns the HTTP status.
  int post_action(const std::string &card_id, const std::string &action);

  // For the test and the log.
  std::vector<Card> cards() const;
  std::string cursor() const;
  int view_index() const;
  std::string dashboard() const;       // the url_path in use; "" until resolved

 private:
  bool resolve_dashboard();
  bool load_dashboards();
  bool fetch_layout();
  bool poll_values();
  bool fetch_designs();   // false when one didn't arrive (it's asked for again next round)
  void clear_place_locked();
  // A design by sha, from those in use or, moved back into use, from the spare ones.
  const std::vector<uint8_t> *blob_locked(const std::string &sha);
  void keep_spare_locked(const std::string &sha, std::vector<uint8_t> rc);
  std::string display_query_locked() const;
  // A dashboard whose layout the API refuses (an auto-generated one it can't lay out): left out
  // of the list from here on, so nothing offers or switches to it.
  static bool refuses(int status);
  void refuse_locked(const std::string &url_path);
  void set_dashboards_locked(std::vector<std::pair<std::string, std::string>> list);
  bool load_place();
  bool skip_hidden_locked(int delta);
  void show_current_locked(bool design_changed, const std::vector<std::string> &changed);
  // The screen a page in the last direction would show, laid out ahead.
  void preload_next_locked();
  std::string values_path() const;
  bool screens_mode() const { return config_.screens && config_.card.empty() && config_.height_dp > 0; }
  void take_screens_locked(JsonArrayConst ids);
  int fail();

  Transport &http_;
  Player &player_;
  Config config_;

  mutable std::mutex lock_;
  std::string layout_etag_;            // the grid's etag, for If-None-Match
  bool relayout_ = true;               // fetch the grid on the next step
  std::string layout_json_;            // the last grid, to re-pick a view without a fetch
  std::string dashboard_url_path_;     // the dashboard's real url_path (for a Navigate)
  std::string dash_;                   // the url_path asked for: config_.dashboard, `default` resolved
  // The dashboards this user can see, (url_path, title), in /dashboards' order: to switch.
  std::vector<std::pair<std::string, std::string>> dashboards_;
  std::mutex switch_lock_;             // one switch at a time
  std::vector<std::string> view_titles_;   // the dashboard's views, from its layout
  // Fetched ahead (prefetch): layouts by url_path, the places done ("path#view"), and designs
  // not on screen now, kept up to kSpareBytes (the oldest go first).
  std::map<std::string, std::string> layouts_;
  std::set<std::string> ahead_done_;
  std::set<std::string> refused_;      // url_paths whose layout was refused
  std::map<std::string, std::vector<uint8_t>> spare_;
  std::vector<std::string> spare_order_;
  size_t spare_bytes_ = 0;
  static constexpr size_t kSpareBytes = 256 * 1024;
  bool settled_ = false;               // every design of the place on screen is here
  bool from_cache_ = false;            // the next fetch_layout may take the layout it has
  uint32_t place_ = 0;                 // bumped by each switch (clear_place_locked)
  int view_index_ = 0;
  std::vector<Card> cards_;
  std::string cursor_;
  size_t current_ = 0;
  int direction_ = 1;   // the last page's: the way the next one likely goes
  std::string shown_design_;           // the design on screen: a value push is enough while it holds
  std::map<std::string, std::vector<uint8_t>> blobs_;   // sha -> .rc bytes
  int failures_ = 0;
};

}  // namespace tzwall
