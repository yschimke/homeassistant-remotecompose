# Terrazzo for Home Assistant

This package provides two frontend surfaces from one custom integration:

- `https://YOUR-HA/terrazzo`: a friendly top-level HA panel rendering your Lovelace
  dashboard in the same Compose Multiplatform WASM app as the public demo. HA login
  works as usual, including on wall panels. The panel is a read-only viewer.
- `custom:terrazzo-card`: an individual Lovelace card rendered by the TypeScript
  Remote Compose player, with live values and optional actions using HA's existing
  authenticated frontend session.

The integration serves the WASM distribution, player and frontend modules locally.
No Supervisor add-on is needed, so Container and Core installs work too. The existing
`addon-server` is a separate, optional converter service; it is not required for
published template playback. This follows HA's [custom panel](https://developers.home-assistant.io/docs/frontend/custom-ui/creating-custom-panels/)
and [custom card](https://developers.home-assistant.io/docs/frontend/custom-ui/custom-card/)
contracts. Python registers static paths with the supported asynchronous API.

## Install

Download the `terrazzo-home-assistant` artifact from a successful **Wasm website**
GitHub Actions run. Unzip `terrazzo-home-assistant.zip` into your HA configuration
directory, producing `custom_components/terrazzo/`. Add to `configuration.yaml`:

```yaml
terrazzo:
  # Optional: a storage/YAML dashboard URL path; omit for the default dashboard.
  dashboard: lovelace-wall
```

Restart Home Assistant. Open **Terrazzo** in the sidebar, or bookmark `/terrazzo`
directly. The package loads its custom-card module automatically. It does not
modify or save your Lovelace configuration. HA's normal user permissions apply.
The read-only viewer is not a server-side read-only account: do not use this
integration to publish a private HA instance anonymously. The public demo has
its own isolated data source and security boundary.

## Individual Remote Compose cards

Choose a published template from `https://ha.coo.ee/designs`, copy its SHA-256,
and use the matching card type/domain/shape. A pinned template avoids sending
your Lovelace config to an external resolver.

```yaml
type: custom:terrazzo-card
design: REPLACE_WITH_64_CHARACTER_TEMPLATE_SHA256
library: https://ha.coo.ee/designs/v1/
theme: light
read_only: true
card:
  type: tile
  entity: light.kitchen
```

Set `read_only: false` to enable the card's service actions. The wrapper dispatches
through `hass.callService`; the player does not receive credentials or open its
own HA connection. HA enforces the current user's permissions. More-info events
are supported; navigation/URL actions are not dispatched by this wrapper.

Values update whenever HA supplies a new `hass` object. Use templates covered by
the [host-reproducible binding contract](../docs/architecture/live-card-updates.md),
such as tile/entity/entities cards. Converter-only structures (history, forecast,
dynamic lists) need a fresh conversion; published templates alone cannot keep
those structures live. Templates with declarations this host cannot populate are
rejected, so a library example cannot masquerade as live data. Relative-time
labels refresh every 20 seconds even when HA's entity state is unchanged.
Unsupported dashboard cards use the shared host's built-in
fallback, with an explicit design-unavailable message when appropriate.

## Build and verify

```sh
./gradlew :app-ui:wasmJsBrowserDistribution :design-server:embedResources
python3 scripts/package-ha-integration.py \
  --wasm app-ui/build/dist/wasmJs/productionExecutable \
  --embed design-server/build/generated/embed/embed/v1 \
  --output build/terrazzo-home-assistant.zip
python3 -m unittest discover -s home-assistant/test -p 'test_*.py'
HA_BROWSER_REQUIRED=1 node --test home-assistant/test/*.test.mjs
```

CI builds the installer and exercises the real WASM host and real TS player
against fixture HA frontend objects. Screenshots are retained in
`wasm-browser-evidence`; these fixture tests do not establish compatibility with
a running HA installation. Install/restart and verify `/terrazzo` on the target
HA version before rolling out to a wall panel.

## Public demo and deployment

`/demo/` runs the same WASM host with generic fake data, without a token, storage
or WebSocket connection. Inline Classic/Braun/LCD/Console chips and a light/dark
button change its design. Template fetch failures fall back to built-in cards.
`?demo=1` selects the same mode on the root WASM website for development.

The design-server image includes the production WASM distribution at `/app/wasm`
and serves it under `/demo/`. Override `WASM_SITE_DIR` for a local distribution.
The preview server's `deploy/image/ha.caddy` must route `/demo` and `/demo/*` to
`ha-designs:8080`, alongside `/designs` and `/embed`. This edge configuration is
owned by `compose-preview-server`; building the image alone does not change it.
GitHub Pages also gets a `/demo/` entry without duplicating the WASM assets.
Authentication documentation under `docs/auth/` and `docs/terrazzo-auth/` is
preserved by the existing Pages workflow.

The HA panel and its iframe exchange snapshots only on the same origin and check
the sending window. Tokens, auth objects and connections stay in HA. Dashboard
design resolution sends only card type and anonymous domain slots, never states,
names or entity IDs. Downloaded template bytes are checked against their SHA-256.
The individual card fetches a pinned template and binds values locally.

Whole-dashboard editing remains the separate `/studio/dashboard` architecture
in PR #834; this viewer does not introduce another editor or a save API.
