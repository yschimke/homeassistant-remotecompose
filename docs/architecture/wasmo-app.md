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
    index.html             # + wasm-mime-fallback.js before the other scripts
    ha-oauth.js, terrazzo.js, *.wasm, composeResources/…
    assets/icon.png        # launcher icon
```

There is no `app.wasm`. Everything runs in the browser, which connects to Home Assistant directly
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

## Signing in to Home Assistant

The web app's connect screen offers **Sign in with Home Assistant**: browser OAuth with
`client_id` set to the page's origin, the same flow as [the card embed](card-embedding.md).
Home Assistant needs no registration for it, `/auth/token` allows cross-origin requests,
and the WebSocket API doesn't check the origin. The refresh token stays in the Wasmo app
origin's `localStorage` until **Sign out** in Settings. It can also be revoked from the HA
profile's refresh tokens.

Requirements:

- **Home Assistant must be reachable over HTTPS** from the viewer's browser (Nabu Casa, or your
  own TLS). The app is served over `https://`, so the browser blocks `ws://` to a plain-HTTP
  `homeassistant.local:8123`.
- **Entity pictures** are plain HTTP fetches, so they also need the Wasmo origin in Home
  Assistant's `http: cors_allowed_origins`. Cards render without it; only those pictures are
  missing.

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
2. In Wasmo, choose **Install App**, paste the URL, and name it (e.g. `terrazzo`).
3. Open `https://terrazzo-<computer>.wasmo.com/`.

The **Wasmo server**, not your browser, fetches the URL (`ZipResourceInstaller`, OkHttp), so the
address must be reachable from wherever Wasmo runs:

- **Hosted wasmo.com** reaches public URLs only — a GitHub release asset works; a LAN-only
  `homeassistant.local` URL does not.
- **Homelab Wasmo** on your network can also install from an HA-hosted URL or any LAN host, and
  from a local file path (`WasmoFileAddress.FileSystem`), which the developer distribution watches
  for changes.

Wasmo does not yet update an app installed from a URL when the file changes
(`docs/platform/plans/app_updates.md` is a plan, and `InstallAppResponse.url` is still `"TODO"`),
so updating today means reinstalling.

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
