# Compose Multiplatform application hosts

`app-ui` contains shared dashboard presentation, adaptive navigation, the Remote Compose
player boundary, and desktop/browser application entry points. Android consumes the same
presentation module while retaining its OAuth, persistence, local card converters, widgets,
and authenticated external-image loader. `ha-model`, `ha-client`, and `terrazzo-core` build
for JVM and Wasm as well as Android; the live session and snapshot binding utilities are common.

## Run

Use JDK 21 and Android SDK 37.1 when building the Android variants.

```sh
./gradlew :app-ui:run
./gradlew :app-ui:wasmJsBrowserDevelopmentRun
./gradlew :app-ui:wasmJsBrowserDistribution
./gradlew :app-ui:createDistributable
```

The browser distribution is under `app-ui/build/dist/wasmJs/productionExecutable`.
Desktop installers can be built with `:app-ui:packageDistributionForCurrentOS` on
the corresponding operating system.

Desktop and browser hosts currently use a long-lived Home Assistant token, kept only
in memory. Optional add-on access uses the same bearer token. Browser connections need
Home Assistant/add-on CORS configuration for the hosting origin, and HTTPS hosting
needs HTTPS/WSS services. These are normal browser constraints; desktop does not need CORS.

## Remote Compose boundary

`RemoteComposeCard` uses rc-players CMP playback and its backend capability report.
Embedded PNG/raw bitmaps render through that player. URL/file-backed images need the
forthcoming host image-resolution API. Keep its integration here rather than adding
an app-specific bitmap protocol. Android routes documents that need an image host or
unsupported operations to its existing player and authenticated loader. Desktop/browser
use native entity/heading/basic markdown presentation when a document cannot render.

Android preserves the converter live-update contract: named values for binding cards,
document regeneration for cards with a data signature. Add-on cards on desktop/browser
refresh their document on snapshot changes; they do not overwrite server-formatted bindings.
Both named HA actions and metadata actions are forwarded to the shared action dispatcher.

## Remaining platform work

The desktop/browser UI is an initial host, not full Android feature parity. It does not
include Android OAuth, persistent sessions, local Android-only converters, widgets,
image-host playback, or every specialized Lovelace card/action. Common native fallbacks
cover entity states/toggles, headings, basic markdown, and nested card lists. Charts,
complex markdown/template rendering, and specialized interactions still need shared renderers
or compatible add-on documents. Android retains those existing integrations.
