// ESPHome component: a Terrazzo wall display. Plays one Home Assistant dashboard view's cards
// on the board's screen with Kobento's RemoteCompose player (rcstage), live through the wall
// display API's long poll. One card at a time: a long press or the board's buttons page
// through them; a tap runs the card's own action in Home Assistant.
//
// The protocol is WallClient (wall_client.h); this file is the device glue: esp_http_client,
// rcstage, two FreeRTOS tasks (the poll, and taps so a tap never waits behind a long poll).
#pragma once

#ifdef USE_ESP_IDF

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "esphome/core/automation.h"
#include "esphome/core/component.h"
#include "esphome/core/helpers.h"
#include "esphome/components/button/button.h"
#include "esphome/components/number/number.h"
#include "esphome/components/switch/switch.h"
#include "esphome/core/preferences.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esphome/components/select/select.h"
#include "esphome/components/text_sensor/text_sensor.h"
#ifdef USE_CAMERA
#include "esphome/components/camera/camera.h"
#endif
#include "glyph_packs.h"
#include "wall_client.h"

namespace esphome {
namespace terrazzo_wall {

class TerrazzoWall;

// Home Assistant's view of the panel: the dashboard on show (a select whose options are the
// dashboards this user can see; choosing one goes there), where it is, and buttons to page.
class DashboardSelect : public select::Select, public Parented<TerrazzoWall> {
 protected:
  void control(size_t index) override;
};
class WallButton : public button::Button, public Parented<TerrazzoWall> {
 public:
  enum Kind { NEXT_SCREEN, PREVIOUS_SCREEN, NEXT_VIEW, PREVIOUS_VIEW };
  void set_kind(int kind) { this->kind_ = kind; }

 protected:
  void press_action() override;
  int kind_ = NEXT_SCREEN;
};

// The backlight: on wakes the screen, off puts it to sleep; it follows the screen timeout too.
class ScreenSwitch : public switch_::Switch, public Parented<TerrazzoWall> {
 protected:
  void write_state(bool on) override;
};
// Minutes without a touch or a Home Assistant control before the panel goes back to its home
// place (0: never). Kept across reboots.
class IdleReturnNumber : public number::Number, public Parented<TerrazzoWall> {
 public:
  void restore(float initial);

 protected:
  void control(float minutes) override;
  ESPPreferenceObject pref_;
};

#ifdef USE_CAMERA
// The screen as Home Assistant sees it: a camera whose picture is the frame on show, encoded to
// JPEG when someone asks (never otherwise), in a task of its own so the main loop doesn't wait.
class ScreenImage : public camera::CameraImage {
 public:
  ScreenImage(uint8_t *data, size_t length, uint8_t requesters)
      : data_(data), length_(length), requesters_(requesters) {}
  ~ScreenImage() override;
  uint8_t *get_data_buffer() override { return this->data_; }
  size_t get_data_length() override { return this->length_; }
  bool was_requested_by(camera::CameraRequester r) const override { return this->requesters_ & (1u << r); }

 protected:
  uint8_t *data_;
  size_t length_;
  uint8_t requesters_;
};

class ScreenCamera : public camera::Camera {
 public:
  void set_quality(uint8_t quality) { this->quality_ = quality; }
  void loop() override;
  void dump_config() override;
  void add_listener(camera::CameraListener *listener) override { this->listeners_.push_back(listener); }
  camera::CameraImageReader *create_image_reader() override;
  void request_image(camera::CameraRequester requester) override;
  // A stream is one picture per request: the screen rarely changes faster than it's asked for.
  void start_stream(camera::CameraRequester requester) override { this->request_image(requester); }
  void stop_stream(camera::CameraRequester requester) override {}

 protected:
  static void encode_task_(void *self);
  void start_();
  uint8_t quality_ = 70;
  std::vector<camera::CameraListener *> listeners_;
  std::atomic<uint8_t> asked_{0};      // requesters waiting for the next picture
  std::atomic<bool> busy_{false};      // an encode task is running
  std::mutex done_lock_;
  bool done_ = false;                  // the task finished: hand done_image_ (maybe none) over
  std::shared_ptr<ScreenImage> done_image_;
};
#endif

class TerrazzoWall : public Component {
 public:
  void set_url(const std::string &url) { this->url_ = url; }
  void set_token(const std::string &token) { this->token_ = token; }
  void set_dashboard(const std::string &d) { this->config_.dashboard = d; }
  void set_view(const std::string &v) { this->config_.view = v; }
  void set_card(const std::string &c) { this->config_.card = c; }
  void set_style(const std::string &s) { this->config_.style = s; }
  void set_dark(bool dark) { this->config_.dark = dark; }
  void set_wait(int seconds) { this->config_.wait_s = seconds; }
  void set_fit(bool fit) { this->fit_ = fit; }
  void set_screens(bool screens) { this->config_.screens = screens; }
  void set_navigation(bool on) { this->navigation_ = on; }
  void set_scroll_bar(bool on) { this->scroll_bar_ = on; }
  void set_picture_max(int percent) { this->config_.picture_max = percent; }
  void set_layout_animations(bool on) { this->layout_animations_ = on; }
  void set_screen_timeout(uint32_t ms) { this->screen_timeout_ms_ = ms; }
  void set_snapshot_port(uint16_t port) { this->snapshot_port_ = port; }
  void set_splash(const uint8_t *data, size_t size) { this->splash_ = data, this->splash_size_ = size; }
  // A glyph pack built into the firmware (glyph_packs.py), read where it lies (in the app image:
  // flash, or PSRAM on a board that runs from PSRAM, CONFIG_SPIRAM_XIP_FROM_PSRAM).
  void add_glyph_pack(const uint8_t *data, size_t size) { this->glyphs_.add(data, size); }
  // Where glyphs no pack has come from (the design server's root), at this depth; and what
  // identifies the built-in packs, so packs fetched for other ones are dropped.
  void set_glyph_server(const std::string &url) { this->glyph_server_ = url; }
  void set_glyph_bits(int bits) { this->glyph_bits_ = bits; }
  void set_glyph_config(uint32_t config) { this->glyph_config_ = config; }

  void setup() override;
  void loop() override;
  // Home Assistant entities (optional): see DashboardSelect.
  void set_dashboard_select(DashboardSelect *s) { this->dashboard_select_ = s; }
  void set_place_sensor(text_sensor::TextSensor *s) { this->place_sensor_ = s; }
  void set_screen_switch(ScreenSwitch *s) { this->screen_switch_ = s; }
  void set_idle_return_number(IdleReturnNumber *n) { this->idle_return_number_ = n; }
  // The default for the idle return, in minutes (Home Assistant's setting, once made, wins).
  void set_idle_return_default(float minutes) { this->idle_return_default_ = minutes; }
  void set_idle_return(float minutes) { this->idle_return_min_ = minutes; }
  // Something chose what the panel shows (Home Assistant): it counts as use, as a touch does.
  void note_control() { this->controlled_us_ = esp_timer_get_time(); }
  // Go to a dashboard by its option index in the select: the dashboards as last offered.
  void show_dashboard_option(size_t index);
  void dump_config() override;
  // After Wi-Fi: the first poll goes out once the network is up.
  // Just ahead of the API server (AFTER_WIFI): setup fetches the dashboard list, so Home
  // Assistant's first look at the select already has its options.
  float get_setup_priority() const override { return setup_priority::AFTER_WIFI + 1.0f; }

  // Automations: page through the view's cards; wake the screen or put it to sleep (a motion
  // sensor, a schedule).
  // From Home Assistant (a button, an action): paged on the action task, so the main loop, which
  // shares the player's core, never waits for a frame being drawn.
  void next_card() { this->queue_page_(1); }
  void previous_card() { this->queue_page_(-1); }
  void wake() { this->screen_request_ = 1; }
  // The panel menu (a long press): a banner saying where the panel is; while it's open a tap
  // on the left or right third pages back or on, one in the middle goes to the next view or
  // dashboard, and a card's own action doesn't run. A long press, or a while untouched, closes it.
  void toggle_menu();
  bool menu_open() const { return this->menu_open_; }
  void menu_tap(float x_dp, float y_dp);
  // Go to the next or previous dashboard this panel's user can see (its first view), or view.
  void next_dashboard() { this->switch_(1, true); }
  void previous_dashboard() { this->switch_(-1, true); }
  void next_view() { this->switch_(1, false); }
  void previous_view() { this->switch_(-1, false); }
  void sleep() { this->screen_request_ = -1; }
  // Raster throughput, logged: the player's own frame buffer (PSRAM) and strips in internal RAM.
  void bench();
  // The card on screen ("" before the first layout).
  std::string current_card() const { return this->client_ ? this->client_->current_card() : ""; }

 protected:
  void page_(int delta);
  void switch_(int delta, bool dashboard);
  void screen_tick_();   // player task
  // The navigation bar (player task): its items brought up to date, and a tap on one.
  void nav_tick_();
  void nav_tap_(int index);
  static void poll_task_(void *self);
  static void action_task_(void *self);
  void start_snapshot_server_();
  static void glyph_task_(void *self);
  void load_stored_glyphs_();
  bool keep_glyphs_(const std::string &body);

  std::string url_, token_;
  ::tzwall::Config config_;
  bool fit_ = true;
  bool layout_animations_ = false;
  bool navigation_ = true;
  bool scroll_bar_ = true;
  DashboardSelect *dashboard_select_ = nullptr;
  text_sensor::TextSensor *place_sensor_ = nullptr;
  ScreenSwitch *screen_switch_ = nullptr;
  IdleReturnNumber *idle_return_number_ = nullptr;
  float idle_return_default_ = 0, idle_return_min_ = 0;
  // Home: the dashboard last chosen from Home Assistant, else the one the panel started on. The
  // idle return goes back to its first view and screen.
  std::string home_dashboard_;
  std::atomic<int64_t> controlled_us_{0};   // the last Home Assistant control
  int published_screen_ = -1;
  void idle_return_check_();
  // The select's options (their strings must outlive it), as url_paths and titles.
  std::vector<std::string> option_paths_, option_titles_;
  std::string published_option_, published_place_;
  uint32_t ha_checked_ms_ = 0, ha_reconnect_ms_ = 0;
  TaskHandle_t poll_handle_ = nullptr;   // woken when Home Assistant connects
  bool ha_connected_ = false;
  void publish_to_ha_();
  static constexpr int kNavHeightDp = 48;
  static constexpr int kScrollBarDp = 24;
  void side_tap_(float at);   // player task: a tap on the scroll bar
  void queue_page_(int delta);
  // What each item of the bar does: "<" / ">" page, else a dashboard's url_path.
  std::vector<std::string> nav_targets_;
  int64_t nav_checked_us_ = 0;
  // Backlight off after this long without a touch (0: never). The first touch then only wakes it.
  uint32_t screen_timeout_ms_ = 5 * 60 * 1000;
  std::atomic<int> screen_request_{0};   // from automations: +1 wake, -1 sleep
  std::atomic<bool> menu_open_{false};
  std::atomic<int64_t> menu_touched_us_{0};   // the menu's last touch: it closes a while after
  void show_menu_();
  std::atomic<bool> screen_on_{true};
  std::atomic<int64_t> awake_since_us_{0};   // the last touch or wake (player task)
  uint16_t snapshot_port_ = 0;          // 0: no snapshot server
  const uint8_t *splash_ = nullptr;
  size_t splash_size_ = 0;

  ::tzwall::GlyphPacks glyphs_;
  std::string glyph_server_;
  int glyph_bits_ = 4;
  uint32_t glyph_config_ = 0;
  // Fetched packs, kept in PSRAM while the panel runs: bounded, as nothing on this panel may
  // grow without one (some 60-270 KB of PSRAM to spare).
  static constexpr size_t kMaxFetchedGlyphBytes = 64 * 1024;
  std::atomic<size_t> glyph_bytes_{0};
  uint32_t glyph_stored_ = 0;

  std::unique_ptr<::tzwall::Transport> http_;
  std::unique_ptr<::tzwall::Player> player_;
  std::unique_ptr<::tzwall::WallClient> client_;
};

template<typename... Ts> class NextCardAction : public Action<Ts...>, public Parented<TerrazzoWall> {
 public:
  void play(const Ts &...x) override { this->parent_->next_card(); }
};

template<typename... Ts> class BenchAction : public Action<Ts...>, public Parented<TerrazzoWall> {
 public:
  void play(const Ts &...x) override { this->parent_->bench(); }
};

template<typename... Ts> class WakeAction : public Action<Ts...>, public Parented<TerrazzoWall> {
 public:
  void play(const Ts &...x) override { this->parent_->wake(); }
};

template<typename... Ts> class SleepAction : public Action<Ts...>, public Parented<TerrazzoWall> {
 public:
  void play(const Ts &...x) override { this->parent_->sleep(); }
};

template<typename... Ts> class NextDashboardAction : public Action<Ts...>, public Parented<TerrazzoWall> {
 public:
  void play(const Ts &...x) override { this->parent_->next_dashboard(); }
};

template<typename... Ts> class PreviousDashboardAction : public Action<Ts...>, public Parented<TerrazzoWall> {
 public:
  void play(const Ts &...x) override { this->parent_->previous_dashboard(); }
};

template<typename... Ts> class NextViewAction : public Action<Ts...>, public Parented<TerrazzoWall> {
 public:
  void play(const Ts &...x) override { this->parent_->next_view(); }
};

template<typename... Ts> class PreviousViewAction : public Action<Ts...>, public Parented<TerrazzoWall> {
 public:
  void play(const Ts &...x) override { this->parent_->previous_view(); }
};

template<typename... Ts> class PreviousCardAction : public Action<Ts...>, public Parented<TerrazzoWall> {
 public:
  void play(const Ts &...x) override { this->parent_->previous_card(); }
};

}  // namespace terrazzo_wall
}  // namespace esphome

#endif  // USE_ESP_IDF
