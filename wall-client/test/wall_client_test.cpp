// Host test for the wall display client: a scripted Home Assistant and a recording player.
// Run: wall-client/test/run.sh
#include "wall_client.h"

#include <cstdio>
#include <cstdlib>
#include <deque>
#include <functional>
#include <string>
#include <vector>

using namespace tzwall;

static int failures = 0;
#define CHECK(cond)                                                     \
  do {                                                                  \
    if (!(cond)) {                                                      \
      fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #cond); \
      failures++;                                                       \
    }                                                                   \
  } while (0)
#define CHECK_EQ(a, b)                                                  \
  do {                                                                  \
    auto _a = (a);                                                      \
    auto _b = (b);                                                      \
    if (!(_a == _b)) {                                                  \
      fprintf(stderr, "%s:%d: %s != %s\n", __FILE__, __LINE__, #a, #b); \
      failures++;                                                       \
    }                                                                   \
  } while (0)

struct Call {
  std::string method, path, body, if_none_match;
};

// Answers in order; each request is recorded. A request with no answer left gets a 599.
struct FakeHa : Transport {
  std::deque<std::pair<std::string, Response>> answers;   // expected path prefix, answer
  std::vector<Call> calls;
  // Runs once, as a GET for [on_get_path] arrives, before its answer is returned.
  std::string on_get_path;
  std::function<void(const std::string &)> on_get;
  void answer(const std::string &prefix, int status, const std::string &body, const std::string &etag = "") {
    answers.push_back({prefix, Response{status, body, etag}});
  }
  Response next(const std::string &path) {
    if (answers.empty()) return {599, "", ""};
    auto [prefix, r] = answers.front();
    answers.pop_front();
    if (path.compare(0, prefix.size(), prefix) != 0) {
      fprintf(stderr, "unexpected request %s (wanted %s)\n", path.c_str(), prefix.c_str());
      failures++;
    }
    return r;
  }
  Response get(const std::string &path, int, const std::string &inm) override {
    calls.push_back({"GET", path, "", inm});
    Response r = next(path);
    if (on_get && path.compare(0, on_get_path.size(), on_get_path) == 0) {
      auto hook = std::move(on_get);
      on_get = nullptr;
      hook(path);
    }
    return r;
  }
  Response post_json(const std::string &path, const std::string &json, int) override {
    calls.push_back({"POST", path, json, ""});
    return next(path);
  }
};

struct FakePlayer : Player {
  std::vector<std::string> shown;            // the document names, in order
  std::vector<std::string> shown_bytes;
  std::vector<std::pair<std::string, std::string>> named;
  std::string last_status = "(none)";
  void show(const std::vector<uint8_t> &rc, const std::string &name) override {
    shown.push_back(name);
    shown_bytes.emplace_back(rc.begin(), rc.end());
  }
  void set_named(const std::string &n, const std::string &v) override {
    // Every slot reaches the document in its domain; the checks below read the wall API's names.
    CHECK(n.rfind("USER:", 0) == 0 || n.rfind("HA:", 0) == 0);
    named.push_back({n.rfind("USER:", 0) == 0 ? n.substr(5) : n, v});
  }
  void status(const std::string &t) override { last_status = t; }
  std::string value(const std::string &n) const {
    for (auto it = named.rbegin(); it != named.rend(); ++it)
      if (it->first == n) return it->second;
    return "(unset)";
  }
};

static const char *kLayout = R"({
  "dashboard": "wall-panel", "title": "Wall panel", "etag": "e1",
  "views": [
    {"index": 0, "path": "home", "sections": [
      {"title": "Kitchen", "cards": [
        {"id": "v0.s0.c0", "type": "tile", "x": 0, "y": 0, "w": 6, "h": 1, "entities": ["light.kitchen"]},
        {"id": "v0.s0.c1", "type": "tile", "x": 6, "y": 0, "w": 6, "h": 1, "entities": ["sensor.t"]}
      ]}]},
    {"index": 1, "path": "lights", "sections": [
      {"title": null, "cards": [
        {"id": "v1.c0", "type": "button", "w": 3, "h": 2, "entities": ["light.hall"]}
      ]}]}
  ]})";

// Overview first, but the integration's choice (wall-panel) is the default.
static const char *kList = R"({"dashboards": [
  {"url_path": "lovelace", "title": "Overview", "default": false},
  {"url_path": "wall-panel", "title": "Wall panel", "default": true}]})";

static const char *kFirstValues = R"({
  "cursor": "100-1", "etag": "e1", "reset": false,
  "cards": {
    "v0.s0.c0": {"design": "aaaa", "rc": "/api/terrazzo/wall/v1/blobs/aaaa.rc", "exact": true,
                 "values": {"light.s0.is_on": true, "light.s0.state": "On", "light.s0.brightness": 128,
                            "light.s0.level": 0.5, "light.s0.name": "Kitchen"}},
    "v0.s0.c1": {"design": "bbbb", "rc": "/api/terrazzo/wall/v1/blobs/bbbb.rc",
                 "values": {"sensor.s0.state": "21.5 °C", "sensor.s0.numeric_state": 21.5,
                            "sensor.s0.reading": null,
                            "camera.s0.picture_url": "/api/terrazzo/wall/v1/pictures/x?v=1"}}
  }})";

static void test_document_names() {
  CHECK_EQ(document_name("light.s0.state"), std::string("USER:light.s0.state"));
  CHECK_EQ(document_name("card.title"), std::string("USER:card.title"));
  CHECK_EQ(document_name("HA:sun.sun.elevation"), std::string("HA:sun.sun.elevation"));
  CHECK_EQ(document_name("USER:x"), std::string("USER:x"));
}

static void test_scalars() {
  CHECK_EQ(scalar_text("true"), std::string("1"));
  CHECK_EQ(scalar_text("false"), std::string("0"));
  CHECK_EQ(scalar_text("42"), std::string("42"));
  CHECK_EQ(scalar_text("-3"), std::string("-3"));
  CHECK_EQ(scalar_text("21.5"), std::string("21.5"));
  CHECK_EQ(scalar_text("0.1"), std::string("0.1"));   // the shortest text for the float
  CHECK_EQ(scalar_text("1234.5678"), std::string("1234.5678"));
  CHECK_EQ(scalar_text("1e30"), std::string("1e+30"));
  CHECK_EQ(scalar_text("null"), std::string("nan"));
  CHECK_EQ(scalar_text("\"On\""), std::string("On"));
}

static void test_first_load_and_updates() {
  FakeHa ha;
  FakePlayer player;
  Config config;
  config.dashboard = "default";
  WallClient client(ha, player, config);

  ha.answer("/api/terrazzo/wall/v1/dashboards", 200, kList);
  ha.answer("/api/terrazzo/wall/v1/dashboards/wall-panel", 200, kLayout, "\"e1\"");
  ha.answer("/api/terrazzo/wall/v1/values/wall-panel?cards=v0.s0.c0,v0.s0.c1&wait=0", 200, kFirstValues);
  ha.answer("/api/terrazzo/wall/v1/blobs/aaaa.rc", 200, "RC-A");
  ha.answer("/api/terrazzo/wall/v1/blobs/bbbb.rc", 200, "RC-B");
  CHECK_EQ(client.step(), 0);
  CHECK(ha.answers.empty());
  CHECK_EQ(client.cursor(), std::string("100-1"));
  // Every poll asks for designs made for this display.
  for (const Call &c : ha.calls)
    if (c.path.find("/values/") != std::string::npos)
      CHECK(c.path.find("&density=1&font_scale=1") != std::string::npos);

  // The first card is on screen once its design arrives, its values pushed before it.
  CHECK_EQ(player.shown.size(), (size_t) 1);
  CHECK_EQ(player.shown_bytes[0], std::string("RC-A"));
  CHECK_EQ(player.value("light.s0.is_on"), std::string("1"));
  CHECK_EQ(player.value("light.s0.brightness"), std::string("128"));
  CHECK_EQ(player.value("light.s0.level"), std::string("0.5"));
  CHECK_EQ(player.value("light.s0.name"), std::string("Kitchen"));
  CHECK_EQ(player.value("sensor.s0.state"), std::string("(unset)"));   // not the card on screen
  CHECK_EQ(player.last_status, std::string(""));

  // A long poll with the cursor: only the light's state changed.
  player.named.clear();
  ha.answer("/api/terrazzo/wall/v1/values/wall-panel?cards=v0.s0.c0,v0.s0.c1&since=100-1&wait=25", 200,
            R"({"cursor": "100-2", "etag": "e1", "cards": {
                 "v0.s0.c0": {"values": {"light.s0.is_on": false, "light.s0.state": "Off", "light.s0.name": "Kitchen"}}}})");
  CHECK_EQ(client.step(), 0);
  CHECK_EQ(player.shown.size(), (size_t) 1);   // same design: no reload
  CHECK_EQ(player.named.size(), (size_t) 2);   // the name didn't change
  CHECK_EQ(player.value("light.s0.is_on"), std::string("0"));
  CHECK_EQ(player.value("light.s0.state"), std::string("Off"));
  // The layout isn't fetched again while its etag holds.
  CHECK_EQ(ha.calls.back().path.find("/values/"), (size_t) 21);

  // Paging shows the second card from the cache, with its values; picture URLs stay out.
  player.named.clear();
  client.page(1);
  CHECK_EQ(client.current_card(), std::string("v0.s0.c1"));
  CHECK_EQ(player.shown_bytes.back(), std::string("RC-B"));
  CHECK_EQ(player.value("sensor.s0.numeric_state"), std::string("21.5"));
  CHECK_EQ(player.value("sensor.s0.reading"), std::string("nan"));
  CHECK_EQ(player.value("camera.s0.picture_url"), std::string("(unset)"));
  // ...but the picture loader has them, by card and slot.
  CHECK_EQ(client.picture_url("v0.s0.c1", "camera.s0"), std::string("/api/terrazzo/wall/v1/pictures/x?v=1"));
  CHECK_EQ(client.picture_url("v0.s0.c1", "camera.s1"), std::string(""));
  CHECK_EQ(client.picture_url("v0.s0.c0", "camera.s0"), std::string(""));
  client.page(1);   // wraps
  CHECK_EQ(client.current_card(), std::string("v0.s0.c0"));

  // The current card's design changes (an entity went unavailable): a new blob, a reload.
  const size_t shown = player.shown.size();
  ha.answer("/api/terrazzo/wall/v1/values/wall-panel?cards=v0.s0.c0,v0.s0.c1&since=100-2", 200,
            R"({"cursor": "100-3", "etag": "e1", "cards": {
                 "v0.s0.c0": {"design": "cccc", "rc": "/api/terrazzo/wall/v1/blobs/cccc.rc",
                              "values": {"light.s0.state": "Unavailable"}}}})");
  ha.answer("/api/terrazzo/wall/v1/blobs/cccc.rc", 200, "RC-C");
  CHECK_EQ(client.step(), 0);
  CHECK_EQ(player.shown.size(), shown + 1);
  CHECK_EQ(player.shown_bytes.back(), std::string("RC-C"));
  CHECK_EQ(player.value("light.s0.state"), std::string("Unavailable"));
  CHECK_EQ(client.cards()[0].values.count("light.s0.is_on"), (size_t) 0);   // a whole card replaces

  // The dashboard was edited: a new etag means a relayout (with If-None-Match) and a full poll.
  ha.answer("/api/terrazzo/wall/v1/values/wall-panel?cards=v0.s0.c0,v0.s0.c1&since=100-3", 200,
            R"({"cursor": "200-1", "etag": "e2", "reset": true, "cards": {}})");
  CHECK_EQ(client.step(), 0);
  ha.answer("/api/terrazzo/wall/v1/dashboards/wall-panel", 304, "");
  ha.answer("/api/terrazzo/wall/v1/values/wall-panel?cards=v0.s0.c0,v0.s0.c1&since=200-1", 200,
            R"({"cursor": "200-2", "etag": "e2", "cards": {}})");
  client.step();
  CHECK_EQ(ha.calls[ha.calls.size() - 2].if_none_match, std::string("\"e1\""));
}

static void test_errors_back_off() {
  FakeHa ha;
  FakePlayer player;
  WallClient client(ha, player, Config{});
  ha.answer("/api/terrazzo/wall/v1/dashboards", 401, "");
  CHECK_EQ(client.step(), 2500);
  CHECK_EQ(player.last_status, std::string("Home Assistant refused the token"));
  ha.answer("/api/terrazzo/wall/v1/dashboards", 0, "");
  CHECK_EQ(client.step(), 5000);
  CHECK_EQ(player.last_status, std::string("can't reach Home Assistant"));
  ha.answer("/api/terrazzo/wall/v1/dashboards", 200, kList);
  ha.answer("/api/terrazzo/wall/v1/dashboards/wall-panel", 200, kLayout);
  ha.answer("/api/terrazzo/wall/v1/values/", 503, R"({"message": "No design server"})");
  CHECK_EQ(client.step(), 10000);
  CHECK(player.last_status.find("design server") != std::string::npos);
  for (int i = 0; i < 6; i++) {
    ha.answer("/api/terrazzo/wall/v1/values/", 502, R"({"message": "boom"})");
    client.step();
  }
  ha.answer("/api/terrazzo/wall/v1/values/", 502, R"({"message": "boom"})");
  CHECK_EQ(client.step(), 60000);
  CHECK_EQ(player.last_status, std::string("HTTP 502: boom"));
  // Home Assistant is back: its next error waits the shortest time, not another minute.
  client.retry_soon();
  CHECK_EQ(client.step(), 2500);
}

static void test_card_errors_and_single_card() {
  FakeHa ha;
  FakePlayer player;
  Config config;
  config.dashboard = "wall panel";   // encoded in the path
  config.card = "v1.c0";
  WallClient client(ha, player, config);
  ha.answer("/api/terrazzo/wall/v1/dashboards/wall%20panel", 200, kLayout);
  ha.answer("/api/terrazzo/wall/v1/values/wall%20panel?cards=v1.c0&wait=0", 200,
            R"({"cursor": "1-1", "etag": "e1", "cards": {"v1.c0": {"error": "No design draws this custom:foo card yet."}}})");
  CHECK_EQ(client.step(), 0);
  CHECK_EQ(client.cards().size(), (size_t) 1);
  CHECK_EQ(client.view_index(), 1);
  CHECK(player.shown.empty());
  CHECK_EQ(player.last_status, std::string("v1.c0: No design draws this custom:foo card yet."));
}

static void test_hidden_cards_are_skipped() {
  FakeHa ha;
  FakePlayer player;
  WallClient client(ha, player, Config{});
  ha.answer("/api/terrazzo/wall/v1/dashboards", 200, kList);
  ha.answer("/api/terrazzo/wall/v1/dashboards/wall-panel", 200, kLayout);
  // The first card is a conditional its conditions hide: the second shows instead.
  ha.answer("/api/terrazzo/wall/v1/values/", 200, R"({"cursor": "1-1", "etag": "e1", "cards": {
              "v0.s0.c0": {"hidden": true},
              "v0.s0.c1": {"design": "bbbb", "values": {"sensor.s0.state": "20"}}}})");
  ha.answer("/api/terrazzo/wall/v1/blobs/bbbb.rc", 200, "RC-B");
  client.step();
  CHECK_EQ(client.current_card(), std::string("v0.s0.c1"));
  CHECK_EQ(player.shown_bytes.back(), std::string("RC-B"));
  client.page(1);   // the hidden one is paged past
  CHECK_EQ(client.current_card(), std::string("v0.s0.c1"));

  // It shows again, whole; then the one on screen hides and the first takes its place.
  ha.answer("/api/terrazzo/wall/v1/values/", 200, R"({"cursor": "1-2", "etag": "e1", "cards": {
              "v0.s0.c0": {"design": "aaaa", "values": {"light.s0.state": "On"}}}})");
  ha.answer("/api/terrazzo/wall/v1/blobs/aaaa.rc", 200, "RC-A");
  client.step();
  ha.answer("/api/terrazzo/wall/v1/values/", 200, R"({"cursor": "1-3", "etag": "e1", "cards": {
              "v0.s0.c1": {"hidden": true}}})");
  client.step();
  CHECK_EQ(client.current_card(), std::string("v0.s0.c0"));
  CHECK_EQ(player.shown_bytes.back(), std::string("RC-A"));

  // Every card hidden: the screen stands, with a status.
  ha.answer("/api/terrazzo/wall/v1/values/", 200, R"({"cursor": "1-4", "etag": "e1", "cards": {
              "v0.s0.c0": {"hidden": true}}})");
  client.step();
  CHECK_EQ(player.last_status, std::string("every card is hidden by its conditions"));
}

static void test_actions_and_navigate() {
  FakeHa ha;
  FakePlayer player;
  WallClient client(ha, player, Config{});
  ha.answer("/api/terrazzo/wall/v1/dashboards", 200, kList);
  ha.answer("/api/terrazzo/wall/v1/dashboards/wall-panel", 200, kLayout);
  ha.answer("/api/terrazzo/wall/v1/values/", 200, kFirstValues);
  ha.answer("/api/terrazzo/wall/v1/blobs/aaaa.rc", 200, "RC-A");
  ha.answer("/api/terrazzo/wall/v1/blobs/bbbb.rc", 200, "RC-B");
  client.step();

  // The document's string goes back verbatim, under "action".
  const std::string action = R"({"type":"ee.schimke.ha.rc.components.HaAction.Toggle","entityId":"light.s0"})";
  ha.answer("/api/terrazzo/wall/v1/actions/wall-panel/v0.s0.c0", 200,
            R"({"ran": "homeassistant.toggle", "action": {"kind": "Toggle", "entity_id": "light.kitchen"}})");
  CHECK_EQ(client.post_action(client.current_card(), action), 200);
  CHECK_EQ(ha.calls.back().method, std::string("POST"));
  CHECK_EQ(ha.calls.back().body,
           std::string(R"({"action":"{\"type\":\"ee.schimke.ha.rc.components.HaAction.Toggle\",\"entityId\":\"light.s0\"}"})"));

  // A tap on the card itself is an empty body.
  ha.answer("/api/terrazzo/wall/v1/actions/wall-panel/v0.s0.c0", 200, R"({"ran": null, "action": {"kind": "MoreInfo"}})");
  client.post_action("v0.s0.c0", "");
  CHECK_EQ(ha.calls.back().body, std::string("{}"));

  // A Navigate to another view of this dashboard switches the cards; elsewhere is ignored.
  ha.answer("/api/terrazzo/wall/v1/actions/", 200, R"({"ran": null, "action": {"kind": "Navigate", "path": "/other/x"}})");
  client.post_action("v0.s0.c0", "");
  CHECK_EQ(client.view_index(), 0);
  ha.answer("/api/terrazzo/wall/v1/actions/", 200, R"({"ran": null, "action": {"kind": "Navigate", "path": "/wall-panel/lights"}})");
  client.post_action("v0.s0.c0", "");
  CHECK_EQ(client.view_index(), 1);
  CHECK_EQ(client.current_card(), std::string("v1.c0"));
  CHECK_EQ(client.cursor(), std::string(""));
  ha.answer("/api/terrazzo/wall/v1/values/wall-panel?cards=v1.c0&wait=0", 200, R"({"cursor": "5-5", "etag": "e1", "cards": {}})");
  client.step();
  CHECK(ha.answers.empty());

  // A refused service.
  ha.answer("/api/terrazzo/wall/v1/actions/", 403, R"({"message": "Not allowed: light.toggle"})");
  CHECK_EQ(client.post_action("v1.c0", ""), 403);
  CHECK_EQ(player.last_status, std::string("tap: HTTP 403: Not allowed: light.toggle"));
}

static void test_view_by_path_and_timer() {
  FakeHa ha;
  FakePlayer player;
  Config config;
  config.view = "lights";
  config.wait_s = 0;   // battery: a timer, no hold
  WallClient client(ha, player, config);
  ha.answer("/api/terrazzo/wall/v1/dashboards", 200, kList);
  ha.answer("/api/terrazzo/wall/v1/dashboards/wall-panel", 200, kLayout);
  ha.answer("/api/terrazzo/wall/v1/values/wall-panel?cards=v1.c0&wait=0", 200, R"({"cursor": "1-1", "etag": "e1", "cards": {}})");
  CHECK_EQ(client.step(), 30000);
  ha.answer("/api/terrazzo/wall/v1/values/wall-panel?cards=v1.c0&since=1-1&wait=0", 200, R"({"cursor": "1-1", "etag": "e1", "cards": {}})");
  CHECK_EQ(client.step(), 30000);
}

static void test_default_dashboard() {
  // A named dashboard skips the list.
  {
    FakeHa ha;
    FakePlayer player;
    Config config;
    config.dashboard = "lovelace";
    WallClient client(ha, player, config);
    ha.answer("/api/terrazzo/wall/v1/dashboards/lovelace", 200, kLayout);
    ha.answer("/api/terrazzo/wall/v1/values/lovelace?", 200, R"({"cursor": "1-1", "etag": "e1", "cards": {}})");
    client.step();
    CHECK_EQ(ha.calls[0].path, std::string("/api/terrazzo/wall/v1/dashboards/lovelace"));
    CHECK_EQ(client.dashboard(), std::string("lovelace"));
  }
  // `default` with none marked: the first listed.
  {
    FakeHa ha;
    FakePlayer player;
    WallClient client(ha, player, Config{});
    ha.answer("/api/terrazzo/wall/v1/dashboards", 200, R"({"dashboards": [{"url_path": "lovelace"}, {"url_path": "x"}]})");
    ha.answer("/api/terrazzo/wall/v1/dashboards/lovelace", 200, kLayout);
    ha.answer("/api/terrazzo/wall/v1/values/lovelace?", 200, R"({"cursor": "1-1", "etag": "e1", "cards": {}})");
    client.step();
    CHECK_EQ(client.dashboard(), std::string("lovelace"));
    // Resolved once: the next layout fetch doesn't list again.
    CHECK(ha.answers.empty());
  }
  // No dashboards at all.
  {
    FakeHa ha;
    FakePlayer player;
    WallClient client(ha, player, Config{});
    ha.answer("/api/terrazzo/wall/v1/dashboards", 200, R"({"dashboards": []})");
    CHECK_EQ(client.step(), 2500);
    CHECK_EQ(player.last_status, std::string("this user can see no dashboards"));
    // A tap before any layout goes nowhere.
    CHECK_EQ(client.post_action("v0.c0", ""), 0);
  }
}

static void test_screen_size() {
  FakeHa ha;
  FakePlayer player;
  Config config;
  config.dashboard = "lovelace";
  config.width_dp = 800;
  config.height_dp = 480;
  config.screens = false;
  WallClient client(ha, player, config);
  ha.answer("/api/terrazzo/wall/v1/dashboards/lovelace", 200, kLayout);
  ha.answer("/api/terrazzo/wall/v1/values/lovelace?", 200, R"({"cursor": "1-1", "etag": "e1", "cards": {}})");
  client.step();
  // Each design is made with the screen as its canvas.
  CHECK(ha.calls.back().path.find("&density=1&font_scale=1&width=800&height=480") != std::string::npos);
}

static void test_screens() {
  FakeHa ha;
  FakePlayer player;
  Config config;
  config.dashboard = "wall-panel";
  config.width_dp = 800;
  config.height_dp = 480;
  WallClient client(ha, player, config);
  ha.answer("/api/terrazzo/wall/v1/dashboards/wall-panel", 200, kLayout);
  // The view's screens come with the first answer; a screen with no design is paged past.
  ha.answer("/api/terrazzo/wall/v1/values/wall-panel?screens=0&wait=0", 200, R"({"cursor": "1-1", "etag": "e1",
              "screens": ["v0.p0-0", "v0.p1-1"],
              "cards": {"v0.p0-0": {"error": "No design draws this entities card yet."},
                        "v0.p1-1": {"design": "aaaa", "values": {"light.s0.state": "On"}}}})");
  ha.answer("/api/terrazzo/wall/v1/blobs/aaaa.rc", 200, "RC-A");
  client.step();
  CHECK_EQ(client.cards().size(), (size_t) 2);
  CHECK_EQ(client.current_card(), std::string("v0.p1-1"));
  CHECK_EQ(player.shown_bytes.back(), std::string("RC-A"));
  CHECK_EQ(player.value("light.s0.state"), std::string("On"));

  // The screens change (one split in two): the one on screen stays, its design kept.
  ha.answer("/api/terrazzo/wall/v1/values/wall-panel?screens=0&since=1-1", 200, R"({"cursor": "1-2", "etag": "e1",
              "screens": ["v0.p0-0", "v0.p1-1", "v0.p2-2"],
              "cards": {"v0.p2-2": {"design": "bbbb", "values": {}}}})");
  ha.answer("/api/terrazzo/wall/v1/blobs/bbbb.rc", 200, "RC-B");
  client.step();
  CHECK_EQ(client.cards().size(), (size_t) 3);
  CHECK_EQ(client.current_card(), std::string("v0.p1-1"));
  client.page(1);
  CHECK_EQ(client.current_card(), std::string("v0.p2-2"));
  client.page(1);   // wraps past the one with no design
  CHECK_EQ(client.current_card(), std::string("v0.p1-1"));
}

static void test_a_switch_wins_over_a_layout_in_flight() {
  FakeHa ha;
  FakePlayer player;
  WallClient client(ha, player, Config{});
  ha.answer("/api/terrazzo/wall/v1/dashboards", 200, kList);
  ha.answer("/api/terrazzo/wall/v1/dashboards/wall-panel", 200, kLayout);
  // While the first dashboard's layout is in flight, another task switches to the other one.
  ha.on_get_path = "/api/terrazzo/wall/v1/dashboards/wall-panel";
  ha.on_get = [&](const std::string &) {
    ha.answer("/api/terrazzo/wall/v1/dashboards/lovelace", 200, kLayout);
    ha.answer("/api/terrazzo/wall/v1/values/lovelace?cards=v0.s0.c0,v0.s0.c1&wait=0", 200, R"({"cursor": "2-1", "cards": {}})");
    CHECK(client.switch_dashboard("lovelace"));
    // The old layout arrives after the switch: dropped, so the poll asks for the new place.
    ha.answer("/api/terrazzo/wall/v1/values/lovelace?cards=v0.s0.c0,v0.s0.c1&since=2-1", 200, R"({"cursor": "2-2", "cards": {}})");
  };
  client.step();
  CHECK_EQ(client.dashboard(), std::string("lovelace"));
  CHECK(ha.answers.empty());
}

static void test_an_empty_view_is_stepped_past() {
  static const char *layout = R"({
    "dashboard": "wall-panel", "title": "Wall panel", "etag": "e1",
    "views": [
      {"index": 0, "path": "home", "sections": [{"title": null, "cards": [
        {"id": "v0.c0", "type": "tile", "w": 6, "h": 1, "entities": ["light.a"]}]}]},
      {"index": 1, "path": "empty", "sections": [{"title": null, "cards": []}]},
      {"index": 2, "path": "more", "sections": [{"title": null, "cards": [
        {"id": "v2.c0", "type": "tile", "w": 6, "h": 1, "entities": ["light.b"]}]}]}
    ]})";
  FakeHa ha;
  FakePlayer player;
  WallClient client(ha, player, Config{});
  ha.answer("/api/terrazzo/wall/v1/dashboards", 200, kList);
  ha.answer("/api/terrazzo/wall/v1/dashboards/wall-panel", 200, layout);
  ha.answer("/api/terrazzo/wall/v1/values/wall-panel?cards=v0.c0", 200, R"({"cursor": "1-1", "cards": {}})");
  client.step();
  CHECK(!client.switch_place(1));   // the empty view: nothing to show, but the panel is there
  CHECK_EQ(client.view_index(), 1);
  ha.answer("/api/terrazzo/wall/v1/values/wall-panel?cards=v2.c0&wait=0", 200, R"({"cursor": "1-2", "cards": {}})");
  CHECK(client.switch_place(1));    // on past it, not back into it
  CHECK_EQ(client.view_index(), 2);
}

static void test_switching_views_and_dashboards() {
  FakeHa ha;
  FakePlayer player;
  WallClient client(ha, player, Config{});
  ha.answer("/api/terrazzo/wall/v1/dashboards", 200, kList);
  ha.answer("/api/terrazzo/wall/v1/dashboards/wall-panel", 200, kLayout);
  ha.answer("/api/terrazzo/wall/v1/values/wall-panel?cards=v0.s0.c0,v0.s0.c1", 200, R"({"cursor": "1-1", "cards": {}})");
  client.step();
  CHECK_EQ(client.place(), std::string("Wall panel \u00b7 1/2"));

  // On: the dashboard's next view, loaded at once (its layout is the one the panel has).
  ha.answer("/api/terrazzo/wall/v1/values/wall-panel?cards=v1.c0&wait=0", 200,
            R"({"cursor": "1-2", "cards": {"v1.c0": {"design": "cccc", "values": {}}}})");
  ha.answer("/api/terrazzo/wall/v1/blobs/cccc.rc", 200, "RC-C");
  CHECK(client.switch_place(1));
  CHECK_EQ(client.view_index(), 1);
  CHECK_EQ(player.shown_bytes.back(), std::string("RC-C"));

  // Past the last view: the next dashboard (wrapping to the first), on its first view.
  ha.answer("/api/terrazzo/wall/v1/dashboards/lovelace", 200, kLayout);
  ha.answer("/api/terrazzo/wall/v1/values/lovelace?cards=v0.s0.c0,v0.s0.c1&wait=0", 200, R"({"cursor": "2-1", "cards": {}})");
  CHECK(client.switch_place(1));
  CHECK_EQ(client.dashboard(), std::string("lovelace"));
  CHECK_EQ(client.view_index(), 0);

  // Back from the first view: the previous dashboard's last view, from the layout kept for it.
  ha.answer("/api/terrazzo/wall/v1/values/wall-panel?cards=v1.c0&wait=0", 200, R"({"cursor": "3-1", "cards": {}})");
  CHECK(client.switch_place(-1));
  CHECK_EQ(client.dashboard(), std::string("wall-panel"));
  CHECK_EQ(client.view_index(), 1);

  // A whole dashboard on, from a view that isn't the last: the next dashboard's first view.
  ha.answer("/api/terrazzo/wall/v1/values/wall-panel?cards=v0.s0.c0,v0.s0.c1&wait=0", 200, R"({"cursor": "4-1", "cards": {}})");
  CHECK(client.switch_place(-1));   // to view 0 of wall-panel
  CHECK_EQ(client.view_index(), 0);
  ha.answer("/api/terrazzo/wall/v1/values/lovelace?cards=v0.s0.c0,v0.s0.c1&wait=0", 200, R"({"cursor": "5-1", "cards": {}})");
  CHECK(client.switch_place(1, true));
  CHECK_EQ(client.dashboard(), std::string("lovelace"));
  CHECK_EQ(client.view_index(), 0);
  // And back a whole dashboard: the previous dashboard's first view, not its last.
  ha.answer("/api/terrazzo/wall/v1/values/wall-panel?cards=v0.s0.c0,v0.s0.c1&wait=0", 200, R"({"cursor": "6-1", "cards": {}})");
  CHECK(client.switch_place(-1, true));
  CHECK_EQ(client.dashboard(), std::string("wall-panel"));
  CHECK_EQ(client.view_index(), 0);

  // A navigation bar's state: the dashboards, the one in use, its views.
  auto nav = client.nav();
  CHECK_EQ(nav.dashboards.size(), (size_t) 2);
  CHECK_EQ(nav.dashboards[0].second, std::string("Overview"));
  CHECK_EQ(nav.dashboard, std::string("wall-panel"));
  CHECK_EQ(nav.views.size(), (size_t) 2);
  CHECK_EQ(nav.view, 0);

  // Straight to a dashboard by its url_path, on its first view; one this user can't see, not.
  ha.answer("/api/terrazzo/wall/v1/values/lovelace?cards=v0.s0.c0,v0.s0.c1&wait=0", 200, R"({"cursor": "7-1", "cards": {}})");
  CHECK(client.switch_dashboard("lovelace"));
  CHECK_EQ(client.dashboard(), std::string("lovelace"));
  CHECK_EQ(client.nav().dashboard, std::string("lovelace"));
  CHECK(!client.switch_dashboard("nope"));
  CHECK_EQ(client.dashboard(), std::string("lovelace"));
}

// Once the place on screen is loaded, the places around it are fetched ahead: a switch there then
// asks only for its live values.
static void test_prefetch() {
  FakeHa ha;
  FakePlayer player;
  Config config;
  config.dashboard = "wall-panel";
  config.width_dp = 800;
  config.height_dp = 432;
  WallClient client(ha, player, config);
  CHECK_EQ(client.prefetch(), std::string(""));   // nothing before the place on screen
  ha.answer("/api/terrazzo/wall/v1/dashboards/wall-panel", 200, kLayout);
  ha.answer("/api/terrazzo/wall/v1/values/wall-panel?screens=0&wait=0", 200, R"({"cursor": "1-1", "etag": "e1",
              "screens": ["v0.p0-0"], "cards": {"v0.p0-0": {"design": "aaaa", "values": {}}}})");
  ha.answer("/api/terrazzo/wall/v1/blobs/aaaa.rc", 200, "RC-A");
  client.step();
  CHECK_EQ(player.shown_bytes.back(), std::string("RC-A"));

  ha.answer("/api/terrazzo/wall/v1/dashboards", 200, kList);
  CHECK_EQ(client.prefetch(), std::string("dashboards"));
  // A tap waiting stops a round between requests; the place is fetched again, whole, next time.
  ha.answer("/api/terrazzo/wall/v1/values/wall-panel?screens=1&wait=0", 200, R"({"cursor": "1-2", "etag": "e1",
              "screens": ["v1.p0-0"], "cards": {"v1.p0-0": {"design": "cccc", "values": {}}}})");
  int asked = 0;   // a tap arrives during the first request
  CHECK_EQ(client.prefetch([&] { return ++asked > 1; }), std::string("wall-panel#1 (paused for a tap)"));
  // This dashboard's other view first; its values aren't kept, its first screen's design is.
  ha.answer("/api/terrazzo/wall/v1/values/wall-panel?screens=1&wait=0", 200, R"({"cursor": "1-2", "etag": "e1",
              "screens": ["v1.p0-0"], "cards": {"v1.p0-0": {"design": "cccc", "values": {"x": 1}}}})");
  ha.answer("/api/terrazzo/wall/v1/blobs/cccc.rc", 200, "RC-C");
  CHECK_EQ(client.prefetch(), std::string("wall-panel#1, design 0 KB"));
  // Then the other dashboard: its layout, and the design of its first view's first screen.
  ha.answer("/api/terrazzo/wall/v1/dashboards/lovelace", 200, kLayout);
  ha.answer("/api/terrazzo/wall/v1/values/lovelace?screens=0&wait=0", 200, R"({"cursor": "2-1", "etag": "e1",
              "screens": ["v0.p0-0"], "cards": {"v0.p0-0": {"design": "dddd", "values": {}}}})");
  ha.answer("/api/terrazzo/wall/v1/blobs/dddd.rc", 200, "RC-D");
  CHECK_EQ(client.prefetch(), std::string("lovelace#0: layout, design 0 KB"));
  CHECK_EQ(client.prefetch(), std::string(""));
  CHECK(ha.answers.empty());

  // A switch there: one request, for its live values; the design is shown with them.
  const size_t calls = ha.calls.size();
  ha.answer("/api/terrazzo/wall/v1/values/lovelace?screens=0&wait=0", 200, R"({"cursor": "3-1", "etag": "e1",
              "screens": ["v0.p0-0"], "cards": {"v0.p0-0": {"design": "dddd", "values": {"light.s0.state": "Off"}}}})");
  CHECK(client.switch_dashboard("lovelace"));
  CHECK_EQ(ha.calls.size(), calls + 1);
  CHECK_EQ(player.shown_bytes.back(), std::string("RC-D"));
  CHECK_EQ(player.value("light.s0.state"), std::string("Off"));
  // And back: the layout and design it had are kept.
  ha.answer("/api/terrazzo/wall/v1/values/wall-panel?screens=0&wait=0", 200, R"({"cursor": "4-1", "etag": "e1",
              "screens": ["v0.p0-0"], "cards": {"v0.p0-0": {"design": "aaaa", "values": {}}}})");
  CHECK(client.switch_dashboard("wall-panel"));
  CHECK_EQ(ha.calls.size(), calls + 2);
  CHECK_EQ(player.shown_bytes.back(), std::string("RC-A"));
}

// A dashboard the API can't lay out (the auto-generated Overview: 422) is left out from then on.
static void test_refused_dashboard() {
  FakeHa ha;
  FakePlayer player;
  Config config;
  config.dashboard = "wall-panel";
  config.width_dp = 800;
  config.height_dp = 432;
  WallClient client(ha, player, config);
  ha.answer("/api/terrazzo/wall/v1/dashboards/wall-panel", 200, kLayout);
  ha.answer("/api/terrazzo/wall/v1/values/wall-panel?screens=0&wait=0", 200, R"({"cursor": "1-1", "etag": "e1",
              "screens": ["v0.p0-0"], "cards": {"v0.p0-0": {"design": "aaaa", "values": {}}}})");
  ha.answer("/api/terrazzo/wall/v1/blobs/aaaa.rc", 200, "RC-A");
  client.step();
  ha.answer("/api/terrazzo/wall/v1/dashboards", 200, kList);
  CHECK_EQ(client.prefetch(), std::string("dashboards"));
  CHECK_EQ(client.nav().dashboards.size(), (size_t) 2);
  ha.answer("/api/terrazzo/wall/v1/values/wall-panel?screens=1&wait=0", 200, R"({"cursor": "1-2", "etag": "e1",
              "screens": ["v1.p0-0"], "cards": {"v1.p0-0": {"design": "aaaa", "values": {}}}})");
  CHECK_EQ(client.prefetch(), std::string("wall-panel#1, design kept"));
  ha.answer("/api/terrazzo/wall/v1/dashboards/lovelace", 422, R"({"message": "not a storage dashboard"})");
  CHECK_EQ(client.prefetch(), std::string("lovelace#0: layout HTTP 422"));
  const auto nav = client.nav();
  CHECK_EQ(nav.dashboards.size(), (size_t) 1);
  CHECK_EQ(nav.dashboards[0].first, std::string("wall-panel"));
  CHECK(!client.switch_dashboard("lovelace"));
  CHECK_EQ(client.prefetch(), std::string(""));
  CHECK(ha.answers.empty());
}

// A switch that lands on a dashboard whose layout is refused goes on past it, and doesn't
// strand the panel there.
static void test_switch_skips_refused() {
  FakeHa ha;
  FakePlayer player;
  Config config;
  config.dashboard = "wall-panel";
  config.width_dp = 800;
  config.height_dp = 432;
  WallClient client(ha, player, config);
  ha.answer("/api/terrazzo/wall/v1/dashboards/wall-panel", 200, kLayout);
  ha.answer("/api/terrazzo/wall/v1/values/wall-panel?screens=0&wait=0", 200, R"({"cursor": "1-1", "etag": "e1",
              "screens": ["v0.p0-0"], "cards": {"v0.p0-0": {"design": "aaaa", "values": {}}}})");
  ha.answer("/api/terrazzo/wall/v1/blobs/aaaa.rc", 200, "RC-A");
  client.step();
  // The next dashboard is Overview, whose layout is refused: on to the one after it (here,
  // wrapping round to wall-panel), its layout the one the panel has.
  ha.answer("/api/terrazzo/wall/v1/dashboards", 200, kList);
  ha.answer("/api/terrazzo/wall/v1/dashboards/lovelace", 422, R"({"message": "generated"})");
  ha.answer("/api/terrazzo/wall/v1/values/wall-panel?screens=0&wait=0", 200, R"({"cursor": "2-1", "etag": "e1",
              "screens": ["v0.p0-0"], "cards": {"v0.p0-0": {"design": "aaaa", "values": {}}}})");
  CHECK(client.switch_place(1, true));
  CHECK_EQ(client.dashboard(), std::string("wall-panel"));
  CHECK_EQ(client.nav().dashboards.size(), (size_t) 1);
  CHECK(ha.answers.empty());
}

int main() {
  test_screens();
  test_switching_views_and_dashboards();
  test_a_switch_wins_over_a_layout_in_flight();
  test_an_empty_view_is_stepped_past();
  test_prefetch();
  test_refused_dashboard();
  test_switch_skips_refused();
  test_default_dashboard();
  test_screen_size();
  test_scalars();
  test_document_names();
  test_first_load_and_updates();
  test_errors_back_off();
  test_card_errors_and_single_card();
  test_hidden_cards_are_skipped();
  test_actions_and_navigate();
  test_view_by_path_and_timer();
  if (failures) {
    fprintf(stderr, "%d check(s) failed\n", failures);
    return 1;
  }
  printf("wall_client_test: all passed\n");
  return 0;
}
