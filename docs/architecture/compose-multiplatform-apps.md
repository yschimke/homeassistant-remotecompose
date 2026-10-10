# Compose Multiplatform application hosts

`app-ui` contains shared dashboard presentation, adaptive navigation, and desktop/browser application entry points.
`rc-player-ui` is a small CMP playback boundary, independent of the app, navigation and HA client. Android consumes the same
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
in memory. The sign-in form requires HTTPS, without credentials, query strings or fragments in the URL.
The token is sent only through Home Assistant's WSS authentication message, and is never saved
in browser storage or desktop preferences. Home Assistant must allow the browser hosting origin;
desktop does not need CORS. Add-on document fetching is not enabled in these hosts: the server's
`/v1/cards/{cardId}.rc` route currently returns 501 and the add-on has no browser CORS setup.

## Remote Compose boundary

`RemoteComposeCard` uses rc-players CMP playback and its backend capability report.
Embedded PNG/raw bitmaps render through that player. URL/file-backed images need the
forthcoming host image-resolution API. Keep its integration here rather than adding
an app-specific bitmap protocol. Android continues to use its existing AndroidX player for every cached card, preserving its
wrap-height workaround, authenticated loader, live-binding setters and action/long-press behavior.
The common player is available for future document sources; the initial desktop/browser hosts
currently render native entity/heading/basic markdown cards rather than requesting add-on bytes.

Android preserves the converter live-update contract: named values for binding cards,
document regeneration for cards with a data signature. Desktop/browser fetch a snapshot once per connection and apply `state_changed` deltas through
one subscription shared across dashboard navigation. On reconnect they obtain a fresh snapshot and
subscription. They do not poll entity states or re-fetch every card when unrelated entities change.

## Remaining platform work

The desktop/browser UI is an initial host, not full Android feature parity. It does not
include Android OAuth, persistent sessions, local Android-only converters, widgets,
image-host playback, or every specialized Lovelace card/action. Common native fallbacks
cover entity states/toggles, headings, basic markdown, and nested card lists. Charts,
complex markdown/template rendering, and specialized interactions still need shared renderers
or compatible add-on documents. Android retains those existing integrations.

## Dependency changes

Dependency modernization is part of this change. SDK 37.1 is required by the upgraded Android
Compose artifacts; target SDK remains 37. Kotlin's Wasm Node/Yarn/Binaryen setup creates project
repositories, so settings prefer the centrally declared, group-filtered toolchain repositories.
The converter does not depend on `app-ui` or `rc-player-ui`; Wear, demo and preview hosts retain
Android-only card playback. The Android phone consumes shared presentation and therefore resolves
the CMP Android variants, which can raise Compose above its BOM minimum.

`dependencyInsight` on `debugRuntimeClasspath` confirms Foundation's Android artifact resolves to
`1.13.0-alpha03` in both phone and Wear. Remote Compose alpha21 already requests that artifact
above the BOM's `1.12.1` minimum; Wear has no dependency on `app-ui` or `rc-player-ui`. The phone
also consumes CMP Foundation `1.13.0-alpha02`. The upgraded `Grid` API no longer requires its
previous experimental opt-in.
