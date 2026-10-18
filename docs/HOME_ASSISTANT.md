# Terrazzo in Home Assistant

Terrazzo is a **custom integration**, installed into Home Assistant Core's config
folder. It works with HA OS, Container and Core; no Supervisor add-on, public
backend or separate renderer service is needed. It registers an authenticated
sidebar panel at **`https://YOUR-HA/terrazzo`** and serves both renderers locally.
Use that URL as the start page in a wall-panel browser. Normal HA login runs first.

## Install

Download `terrazzo-home-assistant.zip` from the **Home Assistant plugin** workflow's
`home-assistant-plugin` artifact (Actions → successful run → artifacts). Unzip it
into your HA configuration directory, so the manifest is at
`/config/custom_components/terrazzo/manifest.json`. Restart after adding:

```yaml
# configuration.yaml
terrazzo:
  title: Wall panel
  dashboard: wall-panel  # existing Lovelace dashboard URL path; omit for default
```

Open `/terrazzo`. The existing shared WASM app loads the configured Lovelace
configuration and live entities over HA's authenticated WebSocket API. Existing
dashboard selection, card rendering and service dispatch are reused. Do not copy
HA tokens into YAML, URLs or card configuration. This package is manually
installable; it is not currently listed in HACS.

## Add a Remote Compose Lovelace card

In **Settings → Dashboards → Resources**, add
`/terrazzo-static/remote-compose-card.js` as a **JavaScript module**. For dashboards
whose resources are maintained in YAML:

```yaml
lovelace:
  resources:
    - url: /terrazzo-static/remote-compose-card.js
      type: module
```

Add this card to an existing dashboard, replacing the entity with your own:

```yaml
type: custom:remote-compose-card
card:
  type: tile
  entity: light.kitchen
  name: Kitchen
  tap_action:
    action: toggle
card_style: classic
# Optional: use your own design library and a fixed template hash.
# library: https://your-design-server/designs/v1/
# design: <64 lowercase hexadecimal characters>
```

The TS player and embed are packaged locally. The default design library is
`https://ha.coo.ee/designs/v1/`; without `design`, its resolver receives the card
configuration and style to select a template. It receives **no HA credentials or
entity states**. To keep design requests local too, set `library` to your own
server and `design` to the SHA-256 of a compatible values-free template. That
server needs CORS permission for the HA origin if it is on another origin.

Alternatively, load a compatible RC template directly by URL, without a design
library or resolver:

```yaml
type: custom:remote-compose-card
template_url: /local/cards/kitchen.rc
card:
  type: tile
  entity: light.kitchen
  tap_action:
    action: toggle
```

Place that file at `/config/www/cards/kitchen.rc`, or use an absolute HTTPS URL
with CORS permission for your HA origin. Relative paths resolve against the HA
page. Do not combine `template_url` with `design`. Template requests omit cookies,
authorization and the page referrer; do not put credentials in the URL. Only
HTTP(S) is accepted, subject to browser mixed-content restrictions. A direct URL
uses whatever bytes its publisher currently serves when the card loads; use
`library` plus `design` for SHA-256 integrity verification instead. URLs are not
polled for changes. The document must use the same host-supported slot bindings
as library templates; arbitrary snapshot exports are not automatically live.
Live values and actions still come from `card` and HA, never from the URL server.

The standalone web embed accepts the same option as a `template-url` attribute
on `<ha-card>`.

The first version accepts `tile`, `button`, `entity` and `gauge` cards backed by
live named bindings. Templates declaring data bindings that the host cannot populate are rejected with
a visible error, including gauges requiring converter-only range/severity fields.
Snapshot-only designs (history, forecast, baked lists) are not supported by this custom card. It updates values, availability and theme
from HA's `hass` property and refreshes relative timestamps on the embed's timer.
Remote Compose chooses the appearance; the card's own Lovelace configuration
chooses its action. Service calls use `hass.callService`, including full HA targets;
confirmation, more-info, same-origin navigation and HTTP(S) URL actions are handled
by the adapter. Unsupported actions resolve to no action. Only tap is supported
by the current RC templates; hold and double-tap are not implemented.

## Authorization and lifecycle

The integration serves public **static code**, like HA's frontend. It exposes no
new entity, dashboard or service API and holds no server-side admin credential.
The `/terrazzo` panel is a normal HA frontend route and uses the signed-in user's
short-lived access token. The same-origin iframe gets a token through a function
on its own frame element; refresh tokens remain inside HA's frontend. Each WASM
reconnect asks for a current token. Removing the panel destroys the child and
its sockets. Directly opening a static WASM asset does not confer a session.

HA continues to enforce user permissions on dashboard reads, entity subscriptions
and service calls. The custom card creates no OAuth session or extra WebSocket:
it sees exactly the `hass.states` supplied by HA. Device registry access is already
optional in the shared client. HA permission errors remain errors; this package
does not retry with privileged credentials. Visibility rules are not an additional
security boundary; use HA user permissions for access control.

The WASM panel reuses the shared renderer's supported card set; custom third-party
Lovelace cards do not become WASM components. In particular, the TS custom card
runs inside native Lovelace; a WASM dashboard containing that custom type may
show the existing unsupported-card message. More-info and navigation actions
in the panel are forwarded to the parent HA frontend.

## Build and verify

```sh
./gradlew :app-ui:wasmJsBrowserDistribution :design-server:embedResources
python3 scripts/package-home-assistant.py
python3 -m unittest discover -s home-assistant/test -p '*_test.py'
npm install --no-save --prefix embed playwright@1.56.1
npx --prefix embed playwright install chromium
node --test home-assistant/test/card-config.test.mjs home-assistant/test/frontend.test.mjs
```

The focused browser suite loads real RC bytes and the TS player with HA's native
`ha-card` tag already registered, asserts a canvas tap's service call, compares
pixels before/after a live update, clears a removed entity, surfaces authorization
failure, and exercises the panel token refresh/cleanup. Before/after captures are
in [the evidence folder](evidence/home-assistant-plugin/README.md).

CI builds the production WASM distribution, packages it, installs it into a fresh
real Home Assistant Container, onboards a synthetic user and verifies both the
`/terrazzo` WASM dashboard and the native Lovelace custom card, including actions.
It uploads the installable ZIP and browser screenshots. The integration's reference
configuration is in `home-assistant/test/`. Longer Kotlin builds run in GitHub CI.

This runtime keeps the host boundary from the
[dashboard editor work](https://github.com/yschimke/homeassistant-remotecompose/pull/834)
and the [UI Builder Lovelace plan](https://github.com/yschimke/compose-ui-builder/pull/898):
HA owns configuration, entities and authorization; renderers consume its documents.
It adds no second dashboard storage or editor implementation.

API references: [HA custom panels](https://developers.home-assistant.io/docs/frontend/custom-ui/creating-custom-panels/),
[HA custom cards](https://contributors.home-assistant.io/frontend/custom-ui/card/).
