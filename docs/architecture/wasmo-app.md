# Terrazzo as a Wasmo app

[Wasmo](https://github.com/wasmcomputercompany/wasmo) is a "cloud computer": it installs apps
packaged as `.wasmo` files and serves each one from its own origin,
`https://<app>-<computer>.wasmo.com` (or `*.wasmo.localhost:8080` when run locally).
`:app-ui:wasmoApp` packages the Compose Multiplatform Wasm web build as one of those apps, so a
Wasmo owner can open their Home Assistant dashboards, drawn with Terrazzo's cards, from their
Wasmo launcher.

```
./gradlew :app-ui:wasmoApp            # app-ui/build/wasmo/terrazzo.wasmo (ZIP archive)
./gradlew :app-ui:wasmoAppDirectory   # app-ui/build/wasmo/dir/terrazzo.wasmo/ (dev-mode directory)
```

## Shape

```
terrazzo.wasmo/
  wasmo-manifest.toml      # app-ui/wasmo/wasmo-manifest.toml, version from the app version
  www/                     # the wasmJsBrowserDistribution output, served to the owner only
    app.html               # the dist's index.html, renamed; + wasm-mime-fallback.js and the
                           #   manifest/icon links
    ha-oauth.js, terrazzo.js, *.wasm, composeResources/…
    assets/icon.png        # launcher icon
  www-public/              # app-ui/wasmo/www-public/, served to everyone
    index.html             # landing page: sends the owner to /app.html, explains installing
    manifest.webmanifest   # web app manifest (start_url /app.html), so the app can be installed
    icon-192.png, icon-512.png, apple-touch-icon.png
```

## Public landing page and installing to a device

Wasmo serves `www-public/` to everyone and checks it **before** the owner-only `www/`, and it has
no sign-in redirect: anyone who isn't the signed-in owner gets a bare 404 for every `www/` path.
So `/` is `www-public/index.html` for every visitor, and the app itself is `www/app.html` (the
launcher's `home_path`). The landing page fetches `/app.html`; a 200 means the visitor is the
owner, and it replaces itself with the app, keeping the query and hash. Anyone else, including the
owner signed out of Wasmo, sees what Terrazzo is, how to install it on their own Wasmo, and the
other ways to run it, instead of a 404.

The app page links `manifest.webmanifest`, so once open the owner can **Install app** / **Add to
Home Screen** from the browser and get a standalone window on the app's own origin. The manifest
and its icons sit in `www-public/` because browsers fetch a manifest without credentials, which
the owner-only `www/` would answer with 404. Neither holds anything private. There is no service
worker: browsers no longer require one to install, and the app needs a live connection to Home
Assistant anyway.

There is no `app.wasm`. The optional [shared cloud-settings backend](wasmo-cloud-backend.md)
uses JVM code in a fork-based Wasmo, enabled for a supported list of app install names. The web
build probes `/api/wasmo/v1/capabilities`: an enabled backend stores usage settings per install;
static deployments retain their browser storage. This staged integration is under review, with
no deployment or cutover performed. The optional [private session stage](wasmo-cloud-backend.md#ha-session-amendment-current-implementation)
adds encrypted HA custody on configured Wasmo hosts. Public cards remain disabled.

The browser connects to Home Assistant directly
(WebSocket + `/auth/token`). Wasmo serves the files and handles the owner's sign-in to Wasmo. It
fits because of what Wasmo offers today:

- **No WebAssembly runs on Wasmo yet.** Its apps are still Kotlin/JVM code compiled into the
  server; the WASI component runtime and the developer SDK are planned for January 2027.
  A static-resources app needs neither.
- **The platform has no WebSockets**, only buffered request/response HTTP. Live state from
  Home Assistant needs a persistent connection, which the browser has and a Wasmo app does not.
- **Hosted Wasmo can't reach a home network**, so server-side calls to `homeassistant.local`
  would only ever work for a homelab Wasmo.
- **Card generation runs in the browser or on the design server**, not on Wasmo: `rc-converter`
  targets wasmJs (the browser) and the JVM (the design server), not `wasmWasi`, so there is
  nothing to move into a Wasmo server-side component yet.

Using Wasmo's per-app database, object store, jobs and public routes (sign in once across devices,
settings on the server, publishing a card) is planned in
[wasmo-cloud-backend.md](wasmo-cloud-backend.md).

## Signing in to Home Assistant

The web app's connect screen offers **Sign in with Home Assistant**: browser OAuth with
`client_id` set to the page's origin, the same flow as [the card embed](card-embedding.md).
Home Assistant needs no registration for it, `/auth/token` allows cross-origin requests,
and the WebSocket API doesn't check the origin. On static deployments the refresh token stays in the app
origin's `localStorage` until **Sign out** in Settings. A Wasmo advertising `haSession` instead saves
it encrypted per install; owner devices restore it through the private API, and **Sign out** revokes
and removes that saved session for all devices. No refresh token is returned to those devices. It can also be revoked from the HA
profile's refresh tokens.

Requirements:

- **Home Assistant must be reachable over HTTPS** from the viewer's browser (Nabu Casa, or your
  own TLS). The app is served over `https://`, so the browser blocks `ws://` to a plain-HTTP
  `homeassistant.local:8123`.
- **Entity pictures and camera images** are plain HTTP fetches, so they also need the app's exact
  origin (`https://<name>-<computer>.<wasmo host>`, which changes with the install name) in Home
  Assistant's `http: cors_allowed_origins`. Cards render without it; only those pictures are
  missing.

When the page can't reach a backend it falls back to browser storage and, on a `.wasmo` install
(its `app.html` carries `<meta name="terrazzo-host" content="wasmo">`), shows a dismissible notice
saying why: the install name isn't one the Wasmo enables services for, or the Wasmo didn't answer
(one longer retry first). If Home Assistant can't confirm a sign-out, Terrazzo offers to remove the
saved sign-in anyway. Removing the app from the launcher revokes and forgets the saved sign-in;
settings are kept for a reinstall under the same name.

## Installing

Wasmo's computer menu has an **Install App** item (`InstallAppDialog` → `InstallAppRpc`) that
takes an app address and a name of your choice. It is on every Wasmo distribution — no catalog
entry in the Wasmo repository is needed. (The hardcoded `AppCatalog.kt` list, which carries a
placeholder **Smart Home** entry, is only the built-in catalog, not the only way in.)

1. Use the published `terrazzo.wasmo`. Each release attaches it (the
   [`Home Assistant plugin`](../../.github/workflows/home-assistant-plugin.yml) workflow builds
   `:app-ui:wasmoApp` and uploads it), so the latest build is always at the stable URL
   `https://github.com/yschimke/homeassistant-remotecompose/releases/latest/download/terrazzo.wasmo`.
   To host it yourself instead, build `./gradlew :app-ui:wasmoApp` and serve
   `app-ui/build/wasmo/terrazzo.wasmo` from any HTTPS URL Wasmo can reach.
2. In Wasmo, choose **Install App**, paste the URL, and name it `terrazzo` (or whichever name the
   operator lists in `WASMO_SERVICE_APPS`; other names work but keep settings in the browser).
3. Open it from the launcher, or `https://terrazzo-<computer>.wasmo.com/`.

The **Wasmo server**, not your browser, fetches the URL (`ZipResourceInstaller`, OkHttp), so the
address must be reachable from wherever Wasmo runs:

- **Hosted wasmo.com** reaches public URLs only — a GitHub release asset works; a LAN-only
  `homeassistant.local` URL does not.
- **Homelab Wasmo** on your network can also install from an HA-hosted URL or any LAN host, and
  from a local file path (`WasmoFileAddress.FileSystem`), which the developer distribution watches
  for changes.

Wasmo does not yet update an app installed from a URL when the file changes
(`docs/platform/plans/app_updates.md` is a plan), so updating means installing again under the same
name. On a Wasmo with the in-place reinstall fix (the `yschimke/wasmo` `deploy` branch) that
replaces the release and keeps the install's data; older resource versions are then deleted.

## Wasmo gaps worked around here

- **`.wasm` content type.** Wasmo's `ContentTypeDatabase` has no `wasm` entry, so `.wasm` is served
  without `application/wasm`, and `WebAssembly.instantiateStreaming` rejects it.
  `app-ui/wasmo/wasm-mime-fallback.js` falls back to compiling from bytes for those responses.
  The upstream fix is one line in `os/framework/.../ContentTypeDatabase.kt`:
  `"wasm" to "application/wasm".toMediaType()`.

## Card fidelity

This is the same Wasm app as the public web build, so it has the same limits
([compose-multiplatform-apps.md](compose-multiplatform-apps.md)). It runs the card converters
(`rc-converter`, on wasmJs) to compute each card's exact design key, asks the design server for
that key (`POST /designs/v1/generate`: generation properties only, never a card's config or
states), and plays the design with the converter's own bindings, as the phone app does. The
server answers with its library's design or captures one. A card with no template shape, or a key
the server has no design for, draws with the native fallback. The converters add about 0.8 MB to
the app's `.wasm` (0.25 MB to the compressed `.wasmo`).
