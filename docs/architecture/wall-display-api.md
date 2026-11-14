# Wall display API

A small HTTP API in the Terrazzo integration for wall displays that can play RemoteCompose but
shouldn't parse Lovelace or Home Assistant's state objects: an ESPHome panel (ESP32-S3/P4 with an
RC player), an e-paper frame, a kiosk tablet. It's served by Home Assistant itself, under
`/api/terrazzo/wall/v1/`, so it works wherever the integration is installed from the Marketplace
(formerly HACS). Rendering happens in the card design add-on, which already turns a card and its
states into a design (`.rc`) and its named values.

Code: [`wall.py`](../../custom_components/terrazzo/wall.py) (views and the live value hub),
[`wall_api.py`](../../custom_components/terrazzo/wall_api.py) (rules, no HA imports, tested by
[`wall_api_test.py`](../../home-assistant/test/wall_api_test.py)).

## What the device does

```
GET  /dashboards                          once: pick one (or use the `default`)
GET  /dashboards/{d}                      the grid; again when `etag` changes
GET  /values/{d}?cards=…&wait=0           every card's design sha + every value
GET  /blobs/{sha}.rc                      each design it doesn't have cached: immutable
GET  /fonts/fonts.json                     the named faces; each by hash, immutable
loop:
  GET /values/{d}?cards=…&since={cursor}  long poll: only what changed
  GET /pictures/{d}/{card}/{slot}?w=&h=   when a `<slot>.picture_url` changes
on tap:
  POST /actions/{d}/{card}                {"action": "<the `ha` host action's string>"}
```

The device never sees a Lovelace card config, an entity's attributes, a formatter or a service
name. It sees boxes on a grid, opaque design hashes, `name → value` maps it pushes straight into
the player's named slots, and taps it posts back verbatim.

## Auth: a Home Assistant token in a header

Every endpoint is a normal Home Assistant API view, so auth is just

```
Authorization: Bearer <long-lived access token>
```

Make the token under the HA user's profile (**Security → Long-lived access tokens**). A dedicated
non-admin user per display is best: the display sees the dashboards that user may see (admin-only
dashboards are hidden), its taps run as that user (the logbook shows who), and revoking the token
cuts off the one device. Values only ever carry entities the user may read: an
entity outside the user's read permissions is resolved as missing, so the card shows it
unavailable, and each user's values are kept apart. No OAuth flow, refresh tokens or websocket handshake on the device.

The integration passes the same token on to the design add-on, which checks it with Core before it
answers (its nginx `auth_request`), so the integration keeps no credentials of its own.

## Endpoints

All JSON responses are `Cache-Control: no-store` unless noted. Errors are
`{"message": "…"}` with 404 (no such dashboard/card), 422 (a strategy dashboard that has no
saved cards; take control of it first), 502 (the design server failed) or 503 (no design server).

### `GET /dashboards`

```json
{"dashboards": [
  {"url_path": "wall-panel", "title": "Wall panel", "icon": null, "mode": "yaml",
   "default": true, "layout": "/api/terrazzo/wall/v1/dashboards/wall-panel"},
  {"url_path": "lovelace", "title": "Overview", "mode": "storage", "default": false, …}
]}
```

`default` is the integration's chosen dashboard (`terrazzo: dashboard:`), else Overview. Overview
is always `lovelace`.

### `GET /dashboards/{url_path}`: the grid

A simpler shape than Lovelace's: every view becomes sections of cards placed on HA's own 12-column
sections grid (row 56 px, gap 8 px), each with an id and a box. The device lays boxes out; it
doesn't need to know what a `grid_options` or a `vertical-stack` is.

```json
{
  "dashboard": "wall-panel", "title": "Wall panel", "etag": "3f1c0a…",
  "grid": {"columns": 12, "row_height": 56, "gap": 8},
  "values": "/api/terrazzo/wall/v1/values/wall-panel",
  "actions": "/api/terrazzo/wall/v1/actions/wall-panel",
  "views": [{
    "index": 0, "path": "home", "title": "Home", "icon": null,
    "type": "sections", "max_columns": 4,
    "sections": [{
      "title": "Kitchen", "column_span": 1,
      "cards": [
        {"id": "v0.s0.c1", "type": "tile", "x": 0, "y": 0, "w": 6, "h": 1, "auto_height": false,
         "entities": ["light.kitchen"],
         "card": "/api/terrazzo/wall/v1/cards/wall-panel/v0.s0.c1"}
      ]
    }]
  }]
}
```

- **Ids** are positions: `v<view>.s<section>.c<card>`, or `v<view>.c<card>` for a masonry or
  panel view. They are stable while the dashboard isn't edited; `etag` (also the `ETag` header,
  so `If-None-Match` gets a 304) changes when it is, and the device fetches the layout again.
- **Boxes** follow the sections view: `grid_options.columns`/`rows` when set, else HA's default
  for the card type (tile 6×1, button 3×2, heading 12×1, …), shelf-packed left to right. A card
  with no fixed height gets an estimate and `auto_height: true`: the document's own height wins.
  A masonry view stacks its cards full width; a panel view has its first card only.
- **`entities`** is informational (a device can show "unavailable" without its document); the
  values below already carry everything the document reads.
- A stack or grid card is one card: the design server draws its children in one document.

### `GET /values/{url_path}?cards=&since=&wait=`: designs and live values

One endpoint does both the initial load and the updates, for many cards at once:

| query | |
|---|---|
| `cards` | Comma-separated card ids; empty for every card on the dashboard. A device on one view passes that view's. |
| `since` | The `cursor` from the last answer; empty the first time. |
| `wait` | Seconds to hold the request when nothing changed (default 25, max 55, 0 answers at once). |
| `style`, `dark`, `palette` | The look: an `HaUberTheme` id (`classic` default), `dark=1`, a `ThemeStyle` palette. |
| `screens` | A view's index, instead of `cards`: that view a screen at a time (needs `width` and `height`). The answer then carries `screens`, the view's screen ids in order, whenever they changed after `since`. |
| `picture_max` | With `width` and `height`: the most of that screen one picture may take, in percent. A `picture-entity` card that would take more (at 16:9, the width it's drawn at) is drawn in a grid of as many columns as it takes to fit, so it's narrower and other cards fit with it. Its slots, and so `/actions` and `/pictures`, are unchanged. Without it, no picture is capped. |
| `density`, `font_scale`, `width`, `height` | A small player's display (either alone is enough; the other is 1). Each design is then made for it on the design server (`PlayerFolding`): sizes written as numbers instead of expressions over the player's density and font size, `CoreText` as `TextLayout`, values the device won't push frozen, so a player that can't evaluate those (an ESP32 panel's rccore) draws it. Served under its own sha. `width` and `height` (dp, both or neither) also make each design's canvas the whole screen, for a device that shows one card at a time. With a `width`, a card whose layout depends on its width (a `grid`'s columns, a card's width tiers) is laid out for that width when its design is made, rather than by a size gate the player evaluates as it plays: the same picture, with fewer layout passes on a microcontroller. An ESPHome panel whose player lays out in dp sends `density=1&font_scale=1` and its screen's size in dp. |
| `profile` | What the small player can play, by a name the design server knows: `kobento-mcu` (the default: AndroidX's operations, without variables in float fields such as paddings and without `TextLayout` colours by id), `androidx`, `widgets-v6`, `widgets-v7`, `wear-widgets`. The design server adapts each design to it with rc-players' `adaptTo`. Anything that isn't such a name is ignored. A resolve request may also give `{"base": …, "without": ["op:CORE_TEXT", "feature:variable-float-fields"]}`. |

```json
{
  "cursor": "1762500000000-42",
  "etag": "3f1c0a…",
  "reset": false,
  "cards": {
    "v0.s0.c1": {"design": "9b2e…", "rc": "/api/terrazzo/wall/v1/blobs/9b2e….rc", "exact": true,
                 "values": {"light.s0.is_on": true, "light.s0.state": "On", "light.s0.name": "Kitchen"}},
    "v0.s0.c2": {"values": {"sensor.s0.state": "21.5 °C", "sensor.s0.numeric_state": 21.5}},
    "v0.s0.c3": {"error": "No design draws this custom:foo card yet."},
    "v0.s0.c4": {"hidden": true}
  }
}
```

- A **`conditional`** card is drawn as its inner card while its conditions hold (the same
  `state` / `state_not` / `numeric_state` / `and` / `or` / `not` the app evaluates; a condition
  only Home Assistant can evaluate is an `error`). The entities its conditions read re-resolve it.
  While they don't hold it answers `{"hidden": true}`: the device draws nothing for it (and skips
  it when cycling cards). When they hold again it comes whole, as a new design does.
- **Screens** (`screens=<view>`): the view's cards, in layout order, packed into screens of the
  player's `height` (grid rows of 56 dp and 8 dp gaps) and at most `SCREEN_ENTITIES` (12) entities, each drawn as one `vertical-stack` of
  its shelves (cards side by side on one row, as a `horizontal-stack`). Screens overlap: each
  starts about 80% of a screen below the last (`SCROLL_STEP`), at a row of cards, so the last rows of
  one are the first of the next and paging reads as one continuous view; where the rows fall so a
  step would be under half a screen (a tall card), the next screen starts after this one. A heading
  never ends a screen with more to come. A screen id names its run of the view's cards, `v<view>.p<first>-<last>`,
  and is a card id everywhere else (`/cards`, `/actions`, `/pictures`). When a screen's stack has
  no design, the hub sets apart its cards that don't draw in a stack, and packs the view again:
  each such card is a screen of its own (`v0.p3-3`, drawn as that card), and the cards around it
  fill whole screens before and after it. A card with no design even alone is left out, and the
  view packed again without it: the screen runs on over it, and its id names it,
  `v<view>.p<first>-<last>x<index>…` (`v0.p2-7x5` is cards 2 to 7 but 5), so `/actions` and
  `/pictures` read the same stack the design was made from. A screen of headings alone is dropped. So a page is never an
  `error` for a card no design draws, nor a thin run left over from a split.
- **Section columns.** A sections view on a screen wide enough stands its sections side by side,
  in as many columns as Home Assistant gives it: as many 320 dp columns (`SECTION_MIN_WIDTH`, with
  32 dp gaps) as fit the player's `width`, at most the view's `max_columns`. An 800 × 480 panel
  (776 dp left of its scroll bar) has two, so a section of half-width tiles shows four across the
  screen. Sections fill rows of columns in order; one with a `column_span` above 1 has a row to
  itself, across the screen. A row of sections is drawn as a `horizontal-stack` of its columns,
  each the stack of its section's shelves, and a column with nothing on a screen holds an empty
  heading, so the others keep their width (a short last row stays its column's width, as in Home
  Assistant). Screens step down the rows as before, but end only where no card in any column runs
  across, and hold `SCREEN_ENTITIES` entities a column. Their ids end in `c<columns>`
  (`v0.p0-10c2`). Without a `width`, or with room for one column, sections stack as before.
  A section spanning k of those columns holds its full-width cards k to a row (headings still
  across, narrower cards as they are): a wide section's picture beside its tiles, rather than each
  across a screen that has fewer columns than the dashboard was made for.
- For a small player (`width`/`height`) each card is simplified to what its template draws: a
  heading without its icon or badges, markdown without inline emphasis, links or images. An
  entity that doesn't exist, or that the user can't read, is sent as `unavailable`, so its card
  keeps its exact design, with that slot unavailable.
- A card whose **design** is new to the cursor (first poll, or its shape changed: an entity went
  unavailable, a light gained brightness) comes whole: `design`, `rc`, every value.
- Otherwise only **values that changed** after `since`. The names are the document's own named
  slots (`<domain>.s<N>.<field>`, plus converter bindings like a gauge's range). A document declares each in RemoteCompose's
  user domain, so the device pushes `light.s0.state` as `USER:light.s0.state` (a shared `HA:`
  value keeps its own domain). Types are JSON's: `true`/`false` booleans, integers
  without a point, floats with one, strings; `null` is a number with no JSON form (an
  unavailable reading, NaN).
- A design may also read the values every home shares, `HA:sun.sun.*` (elevation, azimuth, the
  sun's times as minutes of the local day). Only a card whose document declares one carries them.
  Each resolve sends `sun.sun`'s state (when the user may read it) and Home Assistant's
  `time_zone` beside the card's own, so they are this home's sun, not the daytime stubs a design
  reads without the data; a change to `sun.sun` re-resolves only the cards that read it.
- `<slot>.picture_url` is where to fetch a slot's picture (see [pictures](#get-picturesurl_pathcard_idslotwhfitformat)).
- **`reset: true`** means the cursor is from before a restart or a dashboard edit: the answer is
  full again, and if `etag` differs from the layout the device holds, it fetches the layout.
- The poll answers as soon as anything it asked about changes. A burst of state changes (a scene)
  is settled for 150 ms and answered once.

### `GET /blobs/{sha256}.rc`

The design's RemoteCompose bytes, `Cache-Control: private, max-age=31536000, immutable`. Designs
are content-addressed and shared between cards of the same shape (light tiles in one look share
one), so a device keeps a small flash cache by sha and almost never downloads after its first
boot. `If-None-Match: "<sha>"` gets a 304.

### `GET /cards/{url_path}/{card_id}`

One card resolved now, for debugging or a single-card device (a button, a badge):

```json
{"id": "v0.s0.c1", "type": "tile", "entities": ["light.kitchen"], "design": "9b2e…",
 "rc": "/api/terrazzo/wall/v1/blobs/9b2e….rc", "exact": true,
 "values": {…}, "tap": {"kind": "Toggle", "entity_id": "light.kitchen"}}
```

### `POST /actions/{url_path}/{card_id}`

A template's tap is a `HostNamedAction` named `ha` whose value is an `HaAction` JSON string. The
device doesn't parse it; it posts it back:

```json
{"action": "{\"type\":\"ee.schimke.ha.rc.components.HaAction.Toggle\",\"entityId\":\"light.s0\"}"}
```

or an empty body for a tap on the card itself. The server runs the card's own action, as the app
and the embed do (`CardTemplatePlayback.resolveTap`): the template only picks a slot; that slot's
`tap_action` from the card config runs, or HA's default for its real entity (toggle for lights,
switches, locks, …; more-info otherwise). A template can never choose the service or its data.

```json
{"ran": "homeassistant.toggle", "action": {"kind": "Toggle", "entity_id": "light.kitchen"}}
{"ran": null, "action": {"kind": "Navigate", "path": "/wall-panel/lights"}}
```

`ran: null` is an action the device handles itself, from the card's config: `MoreInfo` (show
something or nothing), `Navigate` (switch view when the path is this dashboard's), `Url`. 403 is a
service the user may not call. The state change it causes reaches the device through its poll.

## Icons, fonts and pictures

**Icons** need nothing: MDI icons are vector paths inside the design, captured with it (an on/off
design carries both glyphs and a value picks one).

### `GET /fonts/fonts.json` and `GET /fonts/{sha256}.{ext}`

A design may name a face no device ships (`RemoteFontFamily.Named`: `digital7`, `gspot`, from
`card-fonts/`). The manifest is the design server's, with a `url` per face:

```json
{"families": [{"name": "digital7", "role": "display",
  "fonts": [{"file": "digital7.ttf", "weight": 400, "style": "italic",
             "sha256": "4261…", "url": "/api/terrazzo/wall/v1/fonts/4261….ttf"}]}]}
```

A device fetches the manifest at boot (`Cache-Control: max-age=3600`), then each face it can use by
hash: immutable, checked against its sha256 here before it's served, and cached by hash like
designs. A player hands faces to its typeface loader by `name`; a device that can't load a face
keeps its own for that name, as a launcher widget does.

### `GET /pictures/{url_path}/{card_id}/{slot}?w=&h=&fit=&format=`

A design never carries an image URL: a picture slot is `rc-slot://<slot>/entity_picture`. This
endpoint is the device's loader for it. It finds `<slot>` (`camera.s0`) among the card's own
entities by position and domain (`CardDesignSlots.resolvePictureUrl`'s rule), checks the user may
read that entity, fetches its `entity_picture` (one Home Assistant serves, such as
`/api/camera_proxy/…` or `/local/…`, on its own loopback address with the device's token; an
`http(s)` one as it is, with no token) and scales it:

| query | |
|---|---|
| `w`, `h` | The box in pixels, 1–2048. Either may be left out: the other side follows the picture's aspect ratio. A picture is never scaled up. |
| `fit` | `contain` (default): all of it, inside the box. `cover`: the box filled, cropped at the centre. |
| `format` | `jpeg` (default once scaled, quality 80, alpha flattened onto black) or `png`. With no query at all, the picture as it came. |
| `fresh` | `1` fetches the picture from its source now (a tap on it). Otherwise one size of one entity's picture is fetched from its source at most once a minute, however many devices ask. |

The `ETag` is the answer's hash, so `If-None-Match` gets a 304 when the picture didn't change. The
long poll says when to fetch: every card value map also has a `<slot>.picture_url` for each slot
whose entity has a picture, for example
`/api/terrazzo/wall/v1/pictures/wall-panel/v0.c3/camera.s0?v=1a2b3c4d5e6f`. Its `v` changes with
the entity's picture URL (a camera's still moves with its rotating token). A camera's still
also changes behind the same URL, so the answer is cacheable for a minute, as long as the endpoint
serves it from its own cache; a device showing a picture asks again each minute. These names are the device's, not the document's: the player has no slot for
them, so they aren't pushed into it.

## Why long poll, not a websocket

Home Assistant's own websocket is the obvious transport, but on a microcontroller it's the
expensive part: an auth handshake, then `subscribe_entities` diffs of full state objects with
every attribute, which the device must still format exactly as the converter did. Our own
websocket would shrink the payload, but not the cost of keeping a TLS websocket client, its
reconnects and its framing on an ESP32.

The long poll is one `GET` on the HTTP client every ESP-IDF/ESPHome build already has:

- **Stateless on the device.** All it keeps is a cursor string. A dropped Wi-Fi, a deep sleep or a
  reboot just sends the old cursor (an incremental answer) or none (a full one).
- **Stateless-ish on the server.** The cursor is `epoch-seq`. Missed updates are never lost: a
  poll asks "after N", not "from now".
- **Battery devices** pass `wait=0` on a timer instead and get the same incremental answers.
- **Latency** is one round trip after a change, as with a websocket, and the poll ends on the
  first change, so a busy home doesn't hold answers back.

Server-sent events on the same hub would save the reconnect per change; they can be added if
polling ever shows up in a profile, with the same cursor as `Last-Event-ID`.

## Why the work splits this way

| Work | Where | Why |
|---|---|---|
| Lovelace config, strategies, visibility per user | Integration (Python) | It is in HA's process already: no extra API calls. |
| Layout: Lovelace → grid boxes | Integration (`wall_api.layout`) | Small and pure; the device gets boxes. |
| Card → design + values | Design add-on (`POST /api/cards/resolve`, JVM) | The app's own converters and formatters; values match the design byte for byte. |
| State changes → which cards | Integration (`state_changed` listener) | One listener for every display, no websocket to HA. |
| Diffing values, cursors | Integration (`ValueStore`) | So the device gets only what changed. |
| Tap → service call | Integration (`resolve_tap`) | The card config never leaves HA; templates can't pick services. |
| Drawing | Device (RC player) | Plays a document and pushes named values. |

A card is re-resolved only when one of its entities changes, and only while a display polls it; a
hub (one per dashboard, look and user) nobody polled for 10 minutes is dropped. Resolves of one card
run one at a time, each reading states once it starts, so a slow resolve never overwrites a newer
one.

## Requirements and limits

- **A design server.** On Home Assistant OS/Supervised, install the **Terrazzo Card Designs**
  add-on (this repository's add-on store; design-server/addon); the integration finds it through
  the Supervisor. On Container or Core, run the design server yourself and set
  `TERRAZZO_DESIGN_SERVER_URL` (for example `http://designs.lan:8080`) in Home Assistant's
  environment. Without one, the dashboard list, layout and actions work; values and cards answer
  503.
- **Card types** are the converters' (`docs/cards-todo.md`); others answer a per-card `error`
  and the device shows a placeholder.
- **Strategy dashboards** (the auto-generated Overview) have no saved cards: take control first.
- **`last_changed`** bindings read 0: the resolve request carries a state and its attributes only.
- **Pictures Home Assistant serves** are fetched from `127.0.0.1` on its HTTP port; an `http:
  server_host` that leaves out loopback breaks them.
- **Converter bindings that tick** (a clock's date) update when an entity changes or the document
  computes them itself; there's no timer on the server.

## Trying it

```sh
TOKEN=…  # a long-lived access token
HA=http://homeassistant.local:8123/api/terrazzo/wall/v1
curl -sH "Authorization: Bearer $TOKEN" $HA/dashboards | jq
curl -sH "Authorization: Bearer $TOKEN" $HA/dashboards/wall-panel | jq '.views[0].sections[0].cards'
curl -sH "Authorization: Bearer $TOKEN" "$HA/values/wall-panel?wait=0" | tee first.json | jq
curl -sH "Authorization: Bearer $TOKEN" "$HA/values/wall-panel?since=$(jq -r .cursor first.json)&wait=30"
curl -sH "Authorization: Bearer $TOKEN" -X POST $HA/actions/wall-panel/v0.c1
```
