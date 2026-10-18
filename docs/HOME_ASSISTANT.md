# Terrazzo in Home Assistant

Terrazzo is a **custom integration**, installed into Home Assistant Core's config
folder. It works with HA OS, Container and Core; no Supervisor add-on, public
backend or separate renderer service is needed. It registers an authenticated
sidebar panel at **`https://YOUR-HA/terrazzo`** and serves both renderers locally.
Use that URL as the start page in a wall-panel browser. Normal HA login runs first.

## Install and update with HACS

The release pipeline publishes two ZIPs containing the same built renderers:
`terrazzo.zip` for HACS and `terrazzo-home-assistant.zip` for manual installation.
**HACS installation becomes available after the first release containing these
assets is published.** Older releases and the source checkout do not include the
built renderers. Do not select the default branch in HACS.

1. Install [HACS](https://www.hacs.xyz/docs/use/) if you do not have it already.
2. In HACS, open **Custom repositories**, add
   `https://github.com/yschimke/homeassistant-remotecompose`, and select
   **Integration** (not Dashboard). Terrazzo is a custom repository; it does not
   need to be listed in the default HACS store.
3. Find **Terrazzo Remote Compose**, download a published release containing
   `terrazzo.zip`, and add the configuration below. HACS installs its complete
   contents into `/config/custom_components/terrazzo/`.
4. Check your HA configuration, restart Home Assistant, and open `/terrazzo`.

If you installed manually before, back up the existing integration outside
`custom_components` and your YAML first, then add the custom repository and
redownload the selected release in HACS. Keep your existing `terrazzo:` settings
and Lovelace resources; no second configuration is needed.

HACS tracks new releases through a Home Assistant **update entity**. Use its
install action to download an update, then restart HA at a convenient time and
refresh wall-panel/browser tabs. Discovery is automatic; installation and restart
remain user-triggered by default. You can later automate `update.install` for the
Terrazzo update entity, but it does not replace the required restart. Confirm the
entity ID in your instance rather than copying a guessed ID.

## Manual installation

Download `terrazzo-home-assistant.zip` and `SHA256SUMS-home-assistant.txt` from a
[GitHub release](https://github.com/yschimke/homeassistant-remotecompose/releases)
that includes both. Verify the checksum before extracting. For an agent/operator
with GitHub CLI, choose the published tag explicitly:

```sh
TAG=vX.Y.Z  # replace with a published release containing the plugin assets
mkdir -p /tmp/terrazzo-download
cd /tmp/terrazzo-download
gh release download "$TAG" --repo yschimke/homeassistant-remotecompose \
  --pattern terrazzo-home-assistant.zip --pattern SHA256SUMS-home-assistant.txt
sha256sum --check --ignore-missing SHA256SUMS-home-assistant.txt
unzip -l terrazzo-home-assistant.zip
# After backing up any previous integration outside custom_components:
unzip terrazzo-home-assistant.zip -d /config
```

`/config` means your HA configuration directory; use the mounted config directory
for Container/Core. The manifest must end up at
`/config/custom_components/terrazzo/manifest.json`. The HACS ZIP has a different
layout: do not extract `terrazzo.zip` into `/config`.
For a first install, add the configuration below, check it and restart HA.
For a manual update, back up and replace the entire old `terrazzo` directory with
the verified package rather than overlaying it, then check configuration, restart
and refresh browser tabs. A successful **Home Assistant plugin** workflow also
retains these packages in its `home-assistant-plugin` artifact for testing; use
published releases for normal installations.

## Configure

```yaml
# configuration.yaml
terrazzo:
  title: Wall panel
  dashboard: wall-panel  # optional initial Lovelace dashboard; omit to show the picker
```

Open `/terrazzo`. The existing shared WASM app loads the configured Lovelace
configuration and live entities over HA's authenticated WebSocket API. Existing
dashboard selection, card rendering and service dispatch are reused. Do not copy
HA tokens into YAML, URLs or card configuration.

## Choose any supported dashboard

`/terrazzo` opens the dashboard picker unless `terrazzo.dashboard` sets an initial
choice. That setting is a default, not a restriction: use **Dashboards** or the
dashboard title's switcher to open another dashboard. Choices come from HA's
Lovelace dashboard list, including dashboards hidden from the sidebar, and are
checked for a readable configuration with explicit views. HA still enforces the
signed-in user's access.

Bookmark `/terrazzo?dashboard=wall-panel` to open a particular named dashboard.
Use `/terrazzo?dashboard=` to open the picker even when YAML sets an initial
dashboard. No token belongs in either URL.

HA also has built-in pages such as Home and Energy which are not ordinary
Lovelace configurations. These cannot be rendered by Terrazzo. Frontend-generated
Lovelace strategies also need HA's JavaScript frontend, so they are excluded from
the picker. The unnamed Overview is offered only if its actual configuration has
explicit views; Terrazzo does not invent a Home entry. A dashboard *named* Home
with normal Lovelace views works. Unsupported/unavailable bookmarked dashboards
show an explanation; choose another through **Dashboards**. If none are supported,
create a Lovelace dashboard with explicit views in HA first, then reconnect.

## Verify and roll back

After installation or an update, check HA logs for integration load errors, open
`/terrazzo`, confirm a live entity changes, and test an action on a safe test entity.
If you use the Lovelace card below, verify it separately too. Actions use the
signed-in user's permissions. A restart interrupts HA; arrange the restart with
the instance owner when an agent installs or updates it.

To roll back with HACS, use its redownload/version selector to choose a previous
release that contains `terrazzo.zip`, restart HA and refresh tabs. For a manual
rollback, restore the backed-up integration directory (outside `custom_components`
until replacement), or replace it with a checksum-verified older manual ZIP.
Restore YAML only if it changed. Keep a backup of the last known working package;
releases predating plugin publication cannot serve as HACS rollback versions.

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

## Publishing (maintainers)

The integration uses the repository's release version. Release Please updates
`custom_components/terrazzo/manifest.json` together with the application version.
After the release PR is reviewed and merged, Release Please creates the tag and
GitHub release and calls `release-build.yml`. Its Home Assistant job calls the
same `home-assistant-plugin.yml` checks used for PRs, at that exact tag. It:

1. Rejects a tag that does not match the source integration version.
2. Builds both renderers, verifies both ZIP layouts and checksums, and runs the
   frontend and installed-Home-Assistant browser checks using the HACS ZIP.
3. Uploads the tested `terrazzo.zip`, `terrazzo-home-assistant.zip`, and
   `SHA256SUMS-home-assistant.txt` to that existing GitHub release only after all
   plugin checks pass. Screenshots remain in the separate
   `home-assistant-plugin-evidence` workflow artifact.

PRs and main builds never publish plugin assets. A build-only manual run can
check any ref; for a tagged build enter its tag. To recover missing publication,
run **Home Assistant plugin** manually, select the release tag, and explicitly
check **publish**. It requires an existing stable `vX.Y.Z` tag and GitHub release,
rebuilds/tests that tag, and does not overwrite existing release assets. Investigate
partial uploads before retrying; never replace an already distributed package
with different bytes under the same version. A bare tag push must already have
an associated GitHub release before upload succeeds.

The first release carrying this setup enables HACS installation. Publication can
lag creation of the GitHub release while tests run; check the Home Assistant job
and presence of all three assets before announcing availability. The site links
to this guide; deploying the updated site is a separate operation. Default HACS
store inclusion is optional and is not performed by this workflow.
