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
- **The card converters are Android-only** (`rc-converter`). Nothing in either repository
  targets `wasmWasi`, so there is no card generation to move server-side yet.

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

## Wasmo gaps worked around here

- **`.wasm` content type.** Wasmo's `ContentTypeDatabase` has no `wasm` entry, so `.wasm` is served
  without `application/wasm`, and `WebAssembly.instantiateStreaming` rejects it.
  `app-ui/wasmo/wasm-mime-fallback.js` falls back to compiling from bytes for those responses.
  The upstream fix is one line in `os/framework/.../ContentTypeDatabase.kt`:
  `"wasm" to "application/wasm".toMediaType()`.
- **Installing.** Wasmo's app catalog is a hardcoded list (`AppCatalog.kt`). It already has a
  placeholder **Smart Home** (`smart`) entry with a label and an icon only, which is the natural
  slot. Until Wasmo has an install flow or SDK, the app installs only into a local Wasmo,
  pointing an entry at the built `.wasmo` (a `WasmoFileAddress` file-system path or an HTTP URL).

## Card fidelity

This is the same Wasm app as the public web build, so it has the same limits
([compose-multiplatform-apps.md](compose-multiplatform-apps.md)). Cards come from the design
library's templates and the native fallbacks, not from the Android converters. Full parity needs
a KMP converter or the add-on's `/v1/cards/{id}.rc` route, which still returns 501.
