# Wall client

A portable C++17 client for the [wall display API](../docs/architecture/wall-display-api.md),
for a Home Assistant wall panel that plays the cards' RemoteCompose designs itself. It has no
renderer and no platform code: a device brings both.

| File | What it is |
|---|---|
| [`src/wall_client.h`](src/wall_client.h), [`.cpp`](src/wall_client.cpp) | `WallClient`: picks a dashboard and view, pages through its cards or the screens the server packs them into, fetches each design once by sha, long-polls the named values and pushes them into the document on screen, switches views and dashboards, and fetches ahead where a switch may go. |
| [`src/glyph_packs.h`](src/glyph_packs.h) | Header-only reader for glyph packs, the coverage rasters the design server serves a face's glyphs as ([glyph-range-fonts.md](../docs/architecture/glyph-range-fonts.md)), and the store of what a player has and still wants. |
| [`tools/glyph_packs.py`](tools/glyph_packs.py) | Draws a pack from a font file with FreeType, in the same wire form, for packs built into a firmware. |
| [`test/`](test) | Host tests for both, with fakes for the device side. |

## The device side

`WallClient` reaches the outside through two interfaces in `wall_client.h`:

- **`Transport`**: `get` (with `If-None-Match`) and `post_json`, relative to Home Assistant, with
  the wall token. On an ESP32 this is `esp_http_client`; in the test, a fake that answers by path.
- **`Player`**: `show` a document, `set_named` one slot's value, `status` a one-line state, and
  optionally `preload` the next screen. Values are set before `show`, so a document's first
  frame shows live data.

Run `step()` in a loop on one task: it fetches the layout when needed, makes one `/values` poll
and fetches the designs it names, and returns how long to wait before the next round (a
back-off after errors). `page()` is safe from any task; `switch_place()`, `switch_dashboard()`
and `prefetch()` block, so run them off the player's task.

JSON is [ArduinoJson](https://arduinojson.org/) 7, as ESPHome's `json` component ships.

## Tests

```bash
wall-client/test/run.sh
```

Needs a C++17 compiler and curl (for the ArduinoJson single header, fetched once into
`test/build/`). Built with `-Wall -Wextra -Werror` and the address and undefined-behaviour
sanitizers.
