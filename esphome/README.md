# ESPHome wall display (`terrazzo_wall`)

An ESPHome external component that turns a microcontroller with a touch screen into a Home
Assistant wall display. It reads a dashboard through the Terrazzo integration's
[wall display API](../docs/architecture/wall-display-api.md) and plays each card's
RemoteCompose design with the Kobento MCU player, which drives the panel directly.

- **One card at a time**, filling the screen, light on black. The design server makes each design
  for the panel (`density=1&font_scale=1&width=…&height=…`): sizes as numbers and the screen as its
  canvas, which Kobento's player needs.
- **Screen timeout**: the backlight goes off after `screen_timeout` (5 minutes) without a touch; a
  touch, or the `terrazzo_wall.wake` action (a motion sensor), wakes it without tapping a card. A long press (1.5 s) or the board's buttons page (past a `conditional` card its conditions hide)
  through the view's cards; so do the `terrazzo_wall.next_card` / `previous_card` actions.
- **Navigation bar** along the bottom (`navigation: true`, 48 dp): ‹ and › page through the
  view's screens, then a tab per dashboard this user can see (six at most, around the current
  one; a dashboard the API refuses to lay out, like the auto-generated Overview, is left
  out). A tab goes to that dashboard; the current one's tab names its view and steps through its
  views. Designs are made for the screen above the bar, and touches on the bar never reach a card.
- **Scroll bar** down the right (`scroll_bar: true`, 24 dp): its thumb shows which of the view's
  screens is showing. A tap above the thumb goes up a screen, below it down one. Designs are made
  for the screen left of it.
- **Pictures** (cameras, `image` entities, a `picture-entity` card) are fetched at the size they're
  drawn, and fetched again each minute while they show (and at once after a tap on their card).
  Only the JPEG is kept: a frame that draws over a picture decodes it straight onto the screen.
  One picture takes at most `picture_max` of the screen (25%): a larger one is drawn narrower, so
  other cards fit with it.
- **In Home Assistant** the panel is a device with a **Dashboard** select (the dashboard on
  show; choose another and the panel goes there), a **Place** text sensor (`Networks · Broadband
  · 1/4`), **Next/Previous screen** and **Next/Previous view** buttons, a **Screen** switch (the
  backlight: on wakes it, off sleeps it) and **Return after idle** (minutes, 0 off; `idle_return:`
  sets the first value, 5 min). After that long without a touch or a control from Home
  Assistant, the panel goes back to the dashboard Home Assistant last chose (else the one it
  started on), first view and screen: an automation's choice stays, wandering by touch doesn't.
  A **Screen capture** camera shows the frame on screen as a JPEG (`quality:`, default 70),
  encoded only when Home Assistant asks for a picture; it needs ~110 KB of PSRAM free for a
  moment and skips a picture rather than take the last of it. Rename or hide them
  under `home_assistant:` like any ESPHome entity (`internal: true`). The select's options are
  the dashboards this panel's user can see, fetched during setup so Home Assistant has them
  when it connects.
- **Switching is fetched ahead**: once every design of the page on screen is in, the panel
  fetches, between taps, the layout and first screen's design of this dashboard's other views and
  of the other dashboards (nearest first). A switch then asks only for the new page's live values;
  designs of pages left behind are kept too (256 KB at most), so going back is as quick.
- **Live** through the API's long poll: the first answer carries every card's design and values,
  later ones only what changed, and those go straight into the document's named slots. A design
  is fetched once by its sha and kept while the view uses it.
- **Taps** run the card's own action in Home Assistant: the document's `ha` host action is posted
  back as it is (`POST /actions/…`), on its own task so it never waits behind a poll. A
  `navigate` to another view of the same dashboard switches the cards shown.
- Problems (no token, no design server, a card type with no design yet) show on a banner and in
  the log, and the client backs off from 2.5 s to a minute.

```
components/terrazzo_wall/
  __init__.py          YAML schema and codegen: pulls in the Kobento components and the board
  terrazzo_wall.*      the device glue: esp_http_client, rcstage, the poll and tap tasks
  wall_client.*        the protocol (layout, long poll, designs, taps): a copy of wall-client/src
  glyph_packs.*        glyph packs: copies of wall-client/src and wall-client/tools
  pack_source.h        glyph packs as Kobento's glyph source
  boards/              boards kobento-mcu doesn't have yet (same layout as its esp32/boards/)
examples/              complete device YAMLs
```

## Requirements

- Home Assistant with the **Terrazzo** integration, and a design server: the **Terrazzo Card
  Designs** add-on on HA OS, or `TERRAZZO_DESIGN_SERVER_URL` elsewhere (see the API doc).
- A **long-lived access token**, ideally of a non-admin user made for the panel: the panel sees
  that user's dashboards and its taps run as that user.
- A board kobento-mcu supports (`esp32/boards/`: Waveshare ESP32-S3 AMOLED 1.8 and LCD 1.28,
  M5Stack Tab5, Core2 and PaperS3, Elecrow CrowPanel 5.79), or one kept here until it moves
  there (`components/terrazzo_wall/boards/`: the Waveshare ESP32-S3-Touch-LCD-7), and ESPHome's
  **ESP-IDF** framework.
  The board's own `sdkconfig.defaults` are applied, except what ESPHome owns (chip, CPU clock,
  flash, partitions, console, log and the radio), so set those in `esp32:`, `psram:`,
  `wifi:` / `esp32_hosted:` to match the board's README.
- The Kobento sources on the machine that builds: the component builds the player from a local
  kobento-mcu checkout, and kobento-mcu builds the RemoteCompose engine from
  `remotecompose-experiments` beside it (or wherever `kobento: engine:` points).

## Installing with ESPHome Builder

1. Put the Kobento sources in Home Assistant's `/config/esphome/kobento/` (the Builder's config
   folder; the File editor, Samba or SSH add-ons all reach it):

   ```
   /config/esphome/kobento/kobento-mcu/
   /config/esphome/kobento/remotecompose-experiments/
   ```

2. Add the token and Wi-Fi to the Builder's **Secrets**:

   ```yaml
   wall_token: "eyJhbGciOi…"
   wifi_ssid: "…"
   wifi_password: "…"
   ```

3. Create a device, replace its YAML with [`examples/terrazzo-wall-lcd7.yaml`](examples/terrazzo-wall-lcd7.yaml)
   or [`examples/terrazzo-wall-amoled.yaml`](examples/terrazzo-wall-amoled.yaml) (or adapt one to your board), and **Install**. The first build takes a while: it compiles
   the engine and ThorVG.

## Configuration

```yaml
terrazzo_wall:
  url: http://homeassistant.local:8123   # Home Assistant, as the panel reaches it (https works)
  token: !secret wall_token
  dashboard: default      # a dashboard url_path (Overview is `lovelace`); `default`: the integration's choice
  view: "0"               # a view's index or path
  card: v0.s0.c1          # optional: this one card only (a single-card device)
  style: classic          # the card look (an HaUberTheme id)
  dark: true              # light text on black (false: the light designs on white)
  wait: 25s               # long-poll hold, up to 55s; 0s polls every 30 s instead (battery)
  fit: true               # the card on its canvas, on black (false: laid out at the screen, on white)
  screen_timeout: 5min    # backlight off after this long untouched (0s: never); a touch wakes it
  splash: my_splash.rc    # optional: shown until the first card (default: kobento-mcu's splash)
  screens: true           # page a screen of cards at a time (false: a card at a time)
  navigation: true        # a bar of dashboards along the bottom (false: the whole screen is cards)
  scroll_bar: true        # a scroll bar down the right: where in the view, tap to go up or down
  picture_max: 25%        # the most of the screen one picture takes
  layout_animations: false  # true: animate a card's layout on a state change (~320 ms on the clock)
  snapshot_port: 8080     # optional, for debugging: GET http://<device>:8080/snapshot.bmp[?step=2]
                          # is the screen now (header X-Card: the card on it). No auth: LAN only.
  timezone: "GMT0BST,M3.5.0/1,M10.5.0"                      # optional: POSIX TZ for documents' clocks
  glyphs:                 # text composed from glyph packs (below); all optional
    embed:                # the styles built in (default: these); [] for none
      - face: regular     # regular, bold or mono: the player's Roboto faces
        sizes: [12, 13, 15, 17, 22]
      - face: bold
        sizes: [16]
    chars: "…"            # the characters built in (default: printable ASCII and reading signs)
    bits: 4               # coverage depth: 8, 4, 2 or 1
    server: http://192.168.0.10:8080   # optional: a design server; by default through Home Assistant
  kobento:
    path: kobento/kobento-mcu       # relative to the YAML
    board: waveshare_s3_amoled18    # a directory under kobento-mcu/esp32/boards
    engine: kobento/remotecompose-experiments   # optional; default: beside kobento-mcu
```

**Glyph packs.** Rasterising a run of text is most of what a card costs the panel to draw (some
75 ms of a card's first frame). With glyph packs, a run is composed from glyph coverage instead: a
copy. `embed` draws the styles listed into the firmware at build (with FreeType, from the same
Roboto files the player loads; 4 bits a pixel, some 6 KB a style in the app image, no other RAM;
on a board that runs from PSRAM, as the LCD-7 does with `CONFIG_SPIRAM_XIP_FROM_PSRAM`, the app
image itself is PSRAM, so each style costs its 6 KB there).
Text in a style or with a character no pack has draws as before, with ThorVG, and the panel asks
for just those glyphs: through Home Assistant (`/api/terrazzo/wall/v1/glyphs`, which passes the
request to the Terrazzo Card Designs add-on's glyph route), or the design server `server` names.
It keeps them in NVS, so the next run in that style is composed too (64 KB of fetched glyphs at
most; dropped when the built-in packs change). **Emoji** come the same way, as colour glyphs: the
panel reads its own fonts' character maps, asks for what they lack (`family=emoji`, 32-bit
colour), measures them at their own advance and shows the card again once they arrive. Each glyph goes where ThorVG's own advances put it, so a
run is exactly as wide as the layout measured. `docs/architecture/glyph-range-fonts.md` has the
measurements behind this.

Card ids are the API's positions (`v<view>.s<section>.c<card>`); list them with

```bash
curl -sH "Authorization: Bearer $TOKEN" http://homeassistant.local:8123/api/terrazzo/wall/v1/dashboards/default | jq '.views[].sections[].cards[] | {id, type, entities}'
```

Set `wifi: power_save_mode: none`: a wall panel is mains-powered, and on the LCD-7 Wi-Fi light
sleep's wake path aborted (`phy_track_pll_init`) while the RGB panel streamed from PSRAM.

The validation checks the board exists and is the chip `esp32:` builds for. Add an `sntp`
`time:` for documents that show the time: rcstage's own SNTP is turned off so the two don't
both run.

**Paging and switching.** On the panel, a long press opens the menu: a tap on its left or right
third pages back or on; in the middle third, the top half goes to the next view of the dashboard
and the bottom half to the next dashboard (its first view). From Home Assistant, the actions
`terrazzo_wall.next_card`/`previous_card`, `next_view`/`previous_view` and
`next_dashboard`/`previous_dashboard` do the same (the example YAML makes buttons of them).

## Testing

The protocol is the wall-client library's (`wall-client/`), copied into the component so
ESPHome builds it from this folder; its host test (a scripted Home Assistant, a recording player;
built with ASan and UBSan) runs there:

```bash
wall-client/test/run.sh
```

It covers the first load and the incremental polls, value text for the player (booleans,
integers, floats, `null`), design changes and the blob cache, paging, relayout on a dashboard
edit, per-card errors, back-off, taps with and without an action, and `navigate` between views.
The YAML side was checked with `esphome config` and `esphome compile --only-generate` on
ESPHome 2026.10, and the device glue with a syntax-only build against kobento-mcu's real
`rcstage` and `board` headers. It hasn't been flashed to a board yet.

## Not yet

- One card on screen at a time: a whole view laid out on the grid needs more than one document
  live in rcstage (or a view-sized document from the design server).
- No pictures (`<slot>.picture_url` is skipped) and no named fonts (`/fonts/`): documents use
  the faces the player ships.
- After a `navigate`, the new view's values wait for the poll in flight to end (up to `wait`).
- Designs live in RAM only; a reboot downloads them again.
- The Kobento sources must be local: the engine isn't fetchable by the ESP-IDF component
  manager yet, so `external_components` can't pull everything from git on its own.
