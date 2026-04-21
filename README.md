# homeassistant-remotecompose

Kotlin Multiplatform library that converts Home Assistant Lovelace dashboard
cards into [RemoteCompose](https://developer.android.com/jetpack/androidx/releases/compose-remote)
documents. Android target today; the shared data/client layers are structured
as KMP so more targets are possible as RemoteCompose publishes more klibs.

## Rendered previews

Full gallery (every card type, light + dark):
[**gist.github.com/yschimke/09c6fbeb83f71b4de3442a410010ee3f**](https://gist.github.com/yschimke/09c6fbeb83f71b4de3442a410010ee3f).

HA reference on the left, RemoteCompose on the right.

### tile — light theme

| variant | Home Assistant | RemoteCompose |
|---|---|---|
| temperature sensor | ![](https://gist.githubusercontent.com/yschimke/09c6fbeb83f71b4de3442a410010ee3f/raw/ref_tile_temperature_sensor_light.png) | ![](https://gist.githubusercontent.com/yschimke/09c6fbeb83f71b4de3442a410010ee3f/raw/rc_tile_temperature_sensor_light.png) |
| light on | ![](https://gist.githubusercontent.com/yschimke/09c6fbeb83f71b4de3442a410010ee3f/raw/ref_tile_light_on_light.png) | ![](https://gist.githubusercontent.com/yschimke/09c6fbeb83f71b4de3442a410010ee3f/raw/rc_tile_light_on_light.png) |
| light off | ![](https://gist.githubusercontent.com/yschimke/09c6fbeb83f71b4de3442a410010ee3f/raw/ref_tile_light_off_light.png) | ![](https://gist.githubusercontent.com/yschimke/09c6fbeb83f71b4de3442a410010ee3f/raw/rc_tile_light_states_off.png) |
| lock locked | ![](https://gist.githubusercontent.com/yschimke/09c6fbeb83f71b4de3442a410010ee3f/raw/ref_tile_lock_locked_light.png) | ![](https://gist.githubusercontent.com/yschimke/09c6fbeb83f71b4de3442a410010ee3f/raw/rc_tile_lock_states_locked.png) |

### tile — dark theme

| variant | Home Assistant | RemoteCompose |
|---|---|---|
| temperature sensor | ![](https://gist.githubusercontent.com/yschimke/09c6fbeb83f71b4de3442a410010ee3f/raw/ref_tile_temperature_sensor_dark.png) | ![](https://gist.githubusercontent.com/yschimke/09c6fbeb83f71b4de3442a410010ee3f/raw/rc_tile_temperature_sensor_dark.png) |
| light on | ![](https://gist.githubusercontent.com/yschimke/09c6fbeb83f71b4de3442a410010ee3f/raw/ref_tile_light_on_dark.png) | ![](https://gist.githubusercontent.com/yschimke/09c6fbeb83f71b4de3442a410010ee3f/raw/rc_tile_light_on_dark.png) |

### other cards (light)

| card | Home Assistant | RemoteCompose |
|---|---|---|
| `button` | ![](https://gist.githubusercontent.com/yschimke/09c6fbeb83f71b4de3442a410010ee3f/raw/ref_button_light_on_light.png) | ![](https://gist.githubusercontent.com/yschimke/09c6fbeb83f71b4de3442a410010ee3f/raw/rc_button_states_on_light.png) |
| `entities` | ![](https://gist.githubusercontent.com/yschimke/09c6fbeb83f71b4de3442a410010ee3f/raw/ref_entities_living_room_light.png) | ![](https://gist.githubusercontent.com/yschimke/09c6fbeb83f71b4de3442a410010ee3f/raw/rc_entities_living_room_light.png) |
| `glance` | ![](https://gist.githubusercontent.com/yschimke/09c6fbeb83f71b4de3442a410010ee3f/raw/ref_glance_overview_light.png) | ![](https://gist.githubusercontent.com/yschimke/09c6fbeb83f71b4de3442a410010ee3f/raw/rc_glance_overview_light.png) |
| `markdown` | ![](https://gist.githubusercontent.com/yschimke/09c6fbeb83f71b4de3442a410010ee3f/raw/ref_markdown_notes_light.png) | ![](https://gist.githubusercontent.com/yschimke/09c6fbeb83f71b4de3442a410010ee3f/raw/rc_markdown_notes_light.png) |

## Why this exists

HA's frontend is lit-element web components that read a live `hass` object
and call services. Every public "render a Lovelace card to PNG/other"
project runs the real frontend inside a headless Chromium (Puppeteer) —
nobody has re-implemented the card set natively. That is the gap this
library fills: take a card config + an HA state snapshot and emit a
RemoteCompose byte stream that any RC player can render (Android apps,
Glance widgets, Wear launcher tiles, ESP32 devices, etc.).

## Module layout

| Module | Target | Role |
|--------|--------|------|
| [`ha-model`](ha-model/) | KMP (Android + JVM) | Dashboard / view / section / card config + HA state snapshot types, kotlinx-serialization. |
| [`ha-client`](ha-client/) | KMP (Android + JVM) | Ktor WebSocket client for `lovelace/config` + state fetch. |
| [`rc-converter`](rc-converter/) | Android | `CardConverter` strategy + one impl per card type. Emits RemoteCompose content. |
| [`previews`](previews/) | Android | `@Preview` fixtures per card type, rendered by the `ee.schimke.composeai.preview` plugin for pixel-parity iteration. |
| [`demo-app`](demo-app/) | Android | Sample app: fetch a real dashboard, render via `remote-player-compose`. |

## Key library references

- `androidx.compose.remote:remote-creation-compose:1.0.0-alpha08` — the
  authoring DSL. Alpha, API shifts between releases.
- `androidx.compose.remote:remote-player-compose:1.0.0-alpha08` — playback
  inside a Compose app.
- `androidx.wear.compose.remote:remote-material3:1.0.0-alpha02` —
  **Wear-only** mirror of Wear Material3. Not used for phone cards; for
  phone-sized HA cards we build on core RC primitives + hand-written
  Material3-flavoured wrappers.
- `ee.schimke.composeai.preview` — the Gradle plugin that renders
  `@Preview` composables to PNG outside Android Studio. Drives the
  pixel-parity loop; see the `compose-preview` skill.

## Rendering strategy: why 1-by-1, not automatic

Short answer: automatic conversion isn't feasible from a JVM/Android
process. Cards don't expose a shared render IR we can intercept — each is a
self-contained `LitElement` that pulls from `hass.states`, subscribes to
history/statistics via WebSocket, and uses per-type CSS. The only way to
"run" them is in a browser.

So the approach is hand-written converters verified against real output:

1. **Fetch** a reference screenshot for every card config we care about
   (use [`hass-lovelace-screenshotter`](https://github.com/itobey/hass-lovelace-screenshotter)
   or a Puppeteer script) — these go under `references/<card-type>/<name>.png`.
2. **Write** a `CardConverter` and a `@Preview` fixture with the same config
   + snapshot.
3. **Render** with `compose-preview show --json` — output lands under
   `previews/build/compose-previews/renders/`.
4. **Diff** against the reference; iterate on the converter until pixel
   delta is under threshold. A `diffimg` / `resemblejs`-style check in CI
   gates regressions.
5. Move to the next card type.

Start with `tile` (scaffolded), then `entities` / `glance` / `gauge` /
`button` — these cover the bulk of real dashboards. `picture-elements`,
`map`, and the graph cards are hard; `iframe` and custom cards are
explicitly out of scope.

## Running the previews

```sh
compose-preview doctor             # verifies Java 17 toolchain
compose-preview list               # lists all @Preview entries
compose-preview show --json        # renders and prints PNG paths
compose-preview render --filter tile
```

## Fetching HA config

`HaClient` (in `ha-client`) wraps the WebSocket commands the converter
needs:

- `lovelace/config` → `Dashboard` (resolved JSON — strategy dashboards are
  resolved client-side today; we do not).
- `get_states` → `Map<String, EntityState>` for the current snapshot.

Auth is a long-lived access token (HA Profile → Security → create token).

## Open questions / TODOs

- Pin the RemoteCompose alpha once API churn slows. The `Render` bodies in
  `rc-converter/cards/*` are stubbed at the draw-call boundary for this
  reason.
- Custom cards (`type: custom:…`) have no shared schema — each needs a
  hand-written converter. Start with the HACS top-10 (Mushroom, button-card,
  mini-graph-card, ApexCharts).
- Strategy-based dashboards need client-side resolution; not implemented.
- Theming: HA uses CSS custom properties via `--ha-<name>`. Map the active
  theme into a `RemoteMaterialTheme`-equivalent token bag.
- Hosting: this library is agnostic about where it runs. A HA-side deployment
  (serves `.rc` bytes over HTTP) and an Android-side deployment (fetches
  config + renders locally) are both viable; keep the converter pure so
  both work.
