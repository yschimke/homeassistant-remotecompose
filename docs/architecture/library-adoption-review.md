# Library adoption review

Status: review, 2026-11. Read-only audit of every client-side module; no code changed.
Publishing of the modules that are clean today, and a second pass on templates, the Terrazzo API
and HTTP-client configuration: [library-publishing.md](library-publishing.md).

**Goal.** Every part of the Home Assistant client should be adoptable on its own as a library:

- **standalone / offline**: no Home Assistant server; render from a JSON snapshot, a fixture or
  demo data;
- **incrementally**: start offline, then add a live server connection, then auth, cache, actions,
  history, without taking the whole Terrazzo app;
- so an **alternative client** (another Android app, a desktop or web shell, SwiftUI, a wall panel,
  an ESP32 bridge) can choose which pieces to reuse and leave the rest.

**Verdict.** The pieces exist and several are already good library citizens (`ha-model`,
`room-ui`, `rc-components`, `rc-material3-motion`, the open `CardRegistry`). What blocks adoption
is *where* things live and *what they drag in*, not missing code:

1. The data-source seam (`HaSession`) sits in an **API-35-only** module next to AppAuth, DataStore
   and Metro, so Wear, TV, Quest, desktop and Wasm each re-implemented session, auth or cache.
2. The renderer (`rc-converter`) depends on the **network client** (`ha-client`, Ktor) for five
   plain value types, and leaks `ha-model` types through an `implementation` dependency.
3. **Everything is in a few large modules**: `ha-client` holds six concerns, `rc-converter` holds
   40+ converters plus playback, widget capture and bundled designs, `app-ui` is one app's shell.
4. **No library discipline** in the build: no `explicitApi()`, no ABI validation, no publishing,
   no samples that work (`demo-app` is a TODO).

None of these needs a rewrite. Most fixes are moves, `api`/`implementation` corrections and a few
new interfaces. A phased plan is at the end.

---

## 1. Target layering

What an alternative client should be able to pick from, bottom up. Each row depends only on rows
above it. Names are proposals; *(exists)* marks a module that already fits.

| Layer | Module (proposed) | Contents | Platforms | Pulls in |
|---|---|---|---|---|
| Model | `ha-model` *(exists, trim)* | `Dashboard`, `CardConfig`, `HaSnapshot`, `EntityState`, `HaArea`, `DashboardSummary`, `HaInstanceConfig` | all | kotlinx-serialization, datetime |
| HA logic | `ha-logic` | Jinja, `HaStateFormat`, section grid, room ranking, icon-*name* + state-colour-*role* resolution, tap-action parsing | all | ha-model |
| Design model | `ha-design-model` | `BuiltDesign`, `CachedFontFace`, `GlyphPack`, `Sha256`, `CardGenerator` interface, `design/*`, RC wire types (`CardBytes`, `HaLiveBindings`) | all | ha-model |
| Data-source contract | `ha-session-api` | slim `HaSession` (connect, dashboards, `liveStates`, `callService`, history), `HaTokenProvider`, `HaActionExecutor` | all | ha-model, coroutines — **no Ktor** |
| Fixtures | `ha-fixtures` | common `DemoHaSession`, scrubbed dashboards as resources, `RecordedCardGenerator`, in-memory stores, fakes | all | session-api |
| Transport + protocol | `ha-client` *(split)* | WebSocket transport behind `HaTransport`, core HA commands, REST `HaEntityStates`, `LiveHaSession` | all | Ktor (`api`) |
| Terrazzo extras | `terrazzo-client` | `terrazzo/*` commands, agent edits, `AgentRelayClient`, design/glyph/font HTTP clients | all | ha-client |
| Offline cache | `ha-session-cache` | `CachedHaSession`, `OfflineOnlySession`, `OfflineCacheStorage` interface + file impl | all (+jvm file impl) | session-api |
| Auth | `ha-auth` | IndieAuth code exchange/refresh, `CredentialStore` interface; platform launchers (AppAuth, loopback, ASWebAuthenticationSession, JS) | all + platform | session-api |
| Session supervisor | `ha-session-supervisor` | resume, reconnect/backoff, network wake-ups, sign-out decision (today in Composables) | all | session-api |
| RC primitives | `rc-components` *(exists)*, `rc-material3-motion` *(exists)*, `rc-icons-api` + `rc-mdi-icons` (optional default) | Tier-1 `RemoteHa*`, theme, `IconResolver` | all | RemoteCompose creation |
| Converter core | `rc-converter-core` | `CardConverter`, `CardRegistry`, `RenderChild`, shared parts | all | ha-model, ha-logic, rc-components |
| Converters | `rc-converters-builtin` (or per family: `-entity`, `-graph`, `-picture`, `-media`, `-bambu`), `rc-card-shutter` | 40+ converters, each family a `CardRegistry.withX()` | all | converter-core |
| Card host | `rc-card-host` | common port of `CachedCardPreview` (cache key, `dataSignature`, binding push), public `captureCard(card, snapshot, size)` | all | converter-core, rc-player-ui |
| Player | `rc-player-ui` *(exists, rename package)* | `RemoteComposeCard(bytes, bindings)`, `RcErrorBoundary` | all | RC player |
| Designs runtime | `rc-card-designs-runtime` | `CardDesigns`, `CardDesignTemplates`, bundled `.uid` designs, `CardDesignDocument` | all | converter-core, design-model |
| Headless app state | `dashboard-state` | dashboard list, selection, snapshot, errors, integration detection as `StateFlow` | all | session-api |
| UI | `dashboard-ui`, `entity-controls-ui`, `room-ui` *(exists)* | one composable dashboard + session; more-info / controls; history charts | CMP | dashboard-state, card-host |
| Platform adapters | `ha-image-coil`, `wear-sync-proto`, Android widgets, crash-reporter seam, mDNS discovery | | per platform | |
| Apps | `app`, `wear`, `tv`, `quest`, `glasses`, desktop/Wasm (`app-ui`), iOS | glue only | | |

The two adoption paths then read as dependency choices:

- **Offline, one card:** `ha-model` + `rc-converter-core` + one converter family + `rc-card-host` +
  `rc-player-ui`. No Ktor, no auth, no app.
- **Incremental live client:** add `ha-session-api` + `ha-client` (`LiveHaSession`), then
  `ha-session-cache`, `ha-auth`, `ha-session-supervisor`, `dashboard-state`, `dashboard-ui` as
  needed. Each step is optional.

---

## 2. Findings by layer

Severity: **P0** blocks reuse outright, **P1** forces copying or drags in large unrelated
dependencies, **P2** hygiene.

### 2.1 Data and client: `ha-model`, `ha-client`, `terrazzo-core`

- **P0: The session seam lives in an API-35, Android-heavy module.** `HaSession`
  (`terrazzo-core/src/commonMain/.../session/HaSession.kt:55`) and `LiveHaSession` (`:333`) are
  common code, but `terrazzo-core` sets `minSdk = 35` (`terrazzo-core/build.gradle.kts:20`; the
  comment cites the RemoteCompose widget, which lives in `app/`). The consequences are written down
  in the code:
  - Quest: "owns the Home Assistant WebSocket because the shared session layer is API-35-only"
    (`quest/.../QuestRoomModel.kt:28-29`). Quest has its own AppAuth (`QuestAuth.kt:30-98`), token
    store and subscribe loop (`QuestRoomModel.kt:201-230`).
  - TV copies the offline cache "rather than depend on terrazzo-core because … minSdk"
    (`tv/.../data/TvOfflineCache.kt:19-22`).
  - Wear (minSdk 30) talks to `HaClient` directly (`wear/.../sync/WearDirectAccess.kt:44`).
- **P0: Offline and demo sessions are Android-only for no Android reason.** `CachedHaSession`,
  `DemoHaSession` and `OfflineOnlySession` are in `androidMain` with **no** `android.*` imports;
  `OfflineCache.kt` imports only `Context` for a wrapper (`:3`, `:42`), and
  `OfflineCacheStorage(rootDir: File)` (`:48`) is plain JVM. So a JVM, Wasm or iOS consumer gets
  only `LiveHaSession` and cannot be offline-first.
- **P1: `HaSession` carries Terrazzo-specific members.** `agentEdits` (`:186`), `cloudAgentEdits`
  (`:193`), design selections (`:257-271`), `registerSilentPush` (`:243`). A third-party client
  implementing a fake or a non-HA source has to know about them. Split into a core interface and
  optional capability interfaces (`AgentEditCapability`, `DesignSelectionCapability`,
  `PushCapability`) checked with `as?`.
- **P1: `ha-client` mixes six concerns.** `HaClient` is a 1296-line class
  (`ha-client/.../HaClient.kt`) holding WebSocket transport and correlation (`:109-151`,
  `:1042-1180`), HA core commands (`:236-1000`) and `terrazzo/*` integration commands
  (`:549-630`, `:713-850`). The module also holds the card-generation chain (`CardGenerator.kt`,
  `AddonCardGenerator`, `RecordedCardGenerator`), design/agent HTTP clients and glyph/font caches
  (`design/*`, `agent/*`, ~2.5k lines), and the iOS `TerrazzoKit` XCFramework config
  (`ha-client/build.gradle.kts:13-29`). Its KDoc still says "Not a subscription client yet"
  (`HaClient.kt:98`) although `subscribeEvents` exists (`:484`).
- **P1: Auth is half pluggable.** `HaClient` takes `accessTokenProvider` (`HaClient.kt:103`) but
  `HaConfig.accessToken` is still required (`:85`); `HaEntityStates`, `CardStudioClient`,
  `TerrazzoClient` take a static token only. `HaAccessTokens` is a concrete class. Add an
  `HaTokenProvider` interface (`token()`, `invalidate(stale)`, `needsReauth`) and accept it
  everywhere.
- **P1: OAuth/refresh is implemented four-plus times:** `terrazzo-core/.../auth/HaAuthService.kt`,
  `quest/.../QuestAuth.kt`, `app-ui/src/jvmMain/.../DesktopHomeAssistantAuth.kt:171-218`, the Wasm
  `ha-oauth.js`; plus a token-mint copy in `app/.../widget/WidgetActionRunner.kt:277-290`
  duplicating `HaSessionFactory.mintAccessToken` (`terrazzo-core/.../di/TerrazzoGraph.kt:113-121`).
- **P1: Metro DI is forced on consumers.** `AppScope` imports `dev.zacsweers.metro.Scope` in
  `commonMain` (`terrazzo-core/src/commonMain/.../di/AppScope.kt`), and 14 `androidMain` classes
  carry Metro annotations. Libraries should have plain constructors; keep the Metro graph and
  bindings in an app-level `*-di` module.
- **P2: `api`/`implementation` leaks.** `ha-client` exposes `HttpClientEngine` in public
  constructors (`HaClient` `:102`, `HaEntityStates` `:35`, `AddonClient` `:48`, `AgentRelayClient`
  `:103`, `CardStudioClient` `:42`) and `HttpClientConfig` (`NetworkErrors.kt:16`) while Ktor is
  `implementation`; coroutines `Flow` is public but `implementation`. `terrazzo-core` exposes
  `ha-model`/`ha-client` types but declares both `implementation` (`build.gradle.kts:28-29`). It
  compiles only because every consumer adds the dependencies itself.
- **P2: `ha-model` is not just data.** It holds a Jinja evaluator (`model/JinjaTemplate.kt`, 887
  lines), state formatting (`rc/HaStateFormat.kt`, 694), section-grid layout, room ranking,
  `design/*` and RC wire types. All dependency-free, so this is cohesion rather than weight; split
  when convenient (`ha-logic`, `ha-design-model`).
- **P2: `terrazzo-core` is misnamed.** In practice it is "Android app services": `TokenVault`,
  AppAuth `HaAuthService`, DataStore `PreferencesStore`, `OfflineCache`, `PinStore`, `WidgetStore`,
  `WearSyncManager`, `LogStore`, LAN policy.
- **P2: No consumer fakes.** There is no fixtures module; `MockEngine` is test-only; the
  `DemoHaSession` tests live in `app/src/test/.../core/session/DemoHaSessionTest.kt`.

### 2.2 Rendering: `rc-*`, `card-designs`, `ha-cards-catalog`

- **P0: The converter depends on the network client.** `rc-converter/build.gradle.kts:110` has
  `implementation(project(":ha-client"))`, used only for plain types: `BuiltDesign`
  (`CardDesignTemplates.kt:17`; defined in the Ktor file `ha-client/.../design/DesignRepository.kt`),
  `CachedFontFace` and `Sha256` (`HaTypefaceLoader.kt:7-8`), `GlyphPack` (`GlyphPackFonts.kt:7`),
  `Sha256` (`androidMain/.../FileLocalDesignStore.kt:4`), and the `CardGenerator` interface
  (`androidMain/.../LocalCardGenerator.kt:6`). Moving these to `ha-design-model` removes Ktor from
  every rendering-only consumer. `card-designs` has the same import pattern
  (`CardDesignLibrary.kt:4-6`).
- **P0: The converter's public API leaks `ha-model`.** `CardConverter.Render(card: CardConfig,
  snapshot: HaSnapshot, …)` (`rc-converter/.../CardConverter.kt:40`) while `ha-model` is
  `implementation` (`build.gradle.kts:109`). `CardDesignTemplates.capture(...)` returns
  `BuiltDesign` from `ha-client`. `rc-card-shutter` repeats this (`api(rc-converter)`,
  `implementation(ha-model)`). Make `ha-model` `api`.
- **P1: No public, cross-platform "card + snapshot → bytes" call.** `captureCardDocument`
  (`rc-converter/src/androidMain/.../CardCapture.kt:63-65`) is `@RestrictTo(LIBRARY)` and needs a
  `Context`. The common path, `CardDesignTemplates.capture(CaptureHost, DesignKey, …)`, works in
  design keys rather than a (card, snapshot) pair.
- **P1: The live-update host loop is Android-only.** Computing bindings is pure and common
  (`cardSnapshotBindings()`, `ha-model/.../rc/CardEntities.kt:88,108`), and pushing them is common
  (`RemoteComposeCard(bytes, bindings)`, `rc-player-ui/.../RemoteComposeCard.kt:71-74`). But the
  loop that ties them together (cache key, `dataSignature`, `liveBindings` gating,
  `pushSnapshotBindings`) is only in `rc-converter/src/androidMain/.../CachedCardPreview.kt`
  (`:122`, `:283-311`; 732 lines). Every other host re-implements the
  [named-binding contract](live-card-updates.md) or does not. This is the piece a third-party
  client is most likely to get wrong, so it should be the first thing shipped as `rc-card-host`.
- **P1: All converters are one Gradle unit.** The registry is open (`CardRegistry.register()`,
  `CardConverter.kt:229-240`; `withEnhancedShutter()` proves third-party extension), and all 42
  converter classes are public, but depending on any converter means taking ~230 files: all of
  `rc-components`, the full MDI catalog, `material-icons-extended`, cmp-material3, bundled `.uid`
  designs and `rc-player-ui`. Only R8 trims it, and only on Android.
- **P1: `rc-converter`'s `androidMain` mixes concerns** (~3k lines): playback, caches,
  launcher/Glance widget capture, `HaRcComposePlayer`, `LocalCardGenerator`. And the
  document-writing converter depends on the player (`build.gradle.kts:112`).
- **P1: HA semantics are trapped next to Compose types.** Reusable by any client today (in
  `ha-model`): `formatState`, `formatValueWithUnit`, relative time, `weatherConditionLabel`
  (`HaStateFormat.kt:29,130,173,675`). Not reusable: `HaIconMap` (`rc-converter/.../icons/HaIconMap.kt:39`)
  wraps the domain/device_class/state → MDI-name logic around `ImageVector`, with `HaIconTables`
  `internal`; `HaStateColor` (`HaStateColor.kt:39`) returns Compose `Color`; `HaActionParser` is
  `internal`. A SwiftUI or plain-Compose client has to re-derive all of it. Move name/role/action
  resolution into `ha-logic` returning strings and enums; keep thin `ImageVector`/`Color` adapters.
- **P1: Icons are closed.** Themes are open (`ProvideHaTheme`, `ProvideHaUberTheme`), but
  `MdiIcons` is a hard-coded `object` (`rc-mdi-icons/.../MdiIcons.kt:24`) exported `api` from both
  `rc-components` (`:51`) and `rc-converter` (`:108`), and `material-icons-extended` sits on the
  main classpath (`rc-components/build.gradle.kts:58`) for ~26 glyphs. Add an `IconResolver`
  CompositionLocal; make MDI the optional default.
- **P2: Tooling on production classpaths.** `ha-cards-catalog` main scope has composeai preview
  annotations and `data-render-core` (`build.gradle.kts:83-87`), while its runtime piece
  `CardDesignDocument` is needed by `card-designs`. `card-designs` jvmMain declares
  `compose.desktop.currentOs` (`:57`), a host-OS native runtime, in a library consumed by
  `design-server`. (`card-designs → :previews` is test scope only, which is fine.)
- **P2: Cross-module source coupling.** `rc-converter` compiles `card-designs/ui-builder/designs/*.uid`
  as generated source (`build.gradle.kts:10,103`); `card-designs` reads
  `terrazzo-core/src/androidMain/resources` (`:35-38`). Two different objects are named
  `CardDesignLibrary` (rc-converter and card-designs).
- **P2: Android-only without need.** `rc-card-shutter` (no `android.*` imports; would be the
  reference third-party converter if KMP), `rc-components-ui` (only `HaEmbeddedPlayer.kt` needs
  Android), `rc-image-coil` (Coil 3 is KMP; only the `BitmapLoader` half is Android).
- **P2: Package identity.** `rc-player-ui` publishes `ee.schimke.terrazzo.shared`, the app's
  package; `app-ui` uses it too (split package).

Already clean: `rc-components` has no `ha-model` imports and takes its own data classes;
`rc-material3-motion`; theme providers; the open registry.

### 2.3 Shared UI and form factors: `app-ui`, `room-ui`, `wear`, `tv`, `quest`, `glasses`, iOS

- **P0: `app-ui` doesn't use the shared session layer.** It has no `terrazzo-core` dependency and
  rebuilds the session inside Composables: private `AppConnection` wrapping `HaClient`
  (`TerrazzoMultiplatformApp.kt:88`), reconnect loop (`:446-457`), `state_changed` subscription
  (`:458-475`), dashboard loading (`:476-487`), all as `remember`/`LaunchedEffect` state. There are
  no ViewModels or state holders. The phone app has a *different* shell (`app/.../TerrazzoApp.kt:389`,
  `SessionHolder.kt:22`). No headless state is shared between Android, desktop and Wasm.
- **P1: `app-ui` is one app's UI, not a UI library.** ~9.8k lines of commonMain: shell, Nav3
  destinations, connection screen, Card Studio, agent/LLM edit screen, draft editor, design-server
  font fetch (`:162-176`), telemetry upload (`:441-444`). Platform concerns are mixed in: Play
  in-app update (`androidMain/.../PlayStoreUpdatePrompt.kt`), desktop keyring, compose-webview,
  loopback OAuth, desktop and Wasm `Main.kt`. The entry point takes 17 host parameters
  (`:126-143`). Wear and TV depend on all of it for `ReportFeedbackButton`/`feedbackIssueUrl` alone
  (`wear/build.gradle.kts:193`, `tv/build.gradle.kts:31`).
- **P1: The Wear sync protocol is copied.** Four files (~605 lines) are byte-identical in
  `app/.../wearsync/proto/` and `wear/.../wearsync/proto/` ("must stay byte-for-byte identical",
  `WearSyncProto.kt:6-7`), and so is `wear_sync.proto`. One `wear-sync-proto` module.
- **P1: Three demo-data sources.** `terrazzo-core/.../DemoHaSession.kt`,
  `app-ui/.../DemoDataSource.kt` + `DemoDashboard.kt` (a separate `DemoDataSource` interface that
  reuses the name `DemoData`), `tv/.../TvDemoData.kt`; Glasses has a fourth (`DemoHouse`).
- **P1: The iOS boundary is not exercised and not narrow.** No Swift file imports `TerrazzoKit`; the
  app has its own `URLSessionWebSocketTask` (`ios/Terrazzo/LiveBindingStream.swift:30`),
  `DashboardStore`, `CardCache`, `CredentialStore`. Meanwhile `TerrazzoKit` is built and released
  exporting the raw `ha-client` + `ha-model` API (`StateFlow`, `JsonObject`), which contradicts
  [apple-app-boundaries.md](apple-app-boundaries.md) ("narrow facade … do not export HttpClient,
  Flow, or serializer types"). `iosMain` has 7 lines.
- **Good:** `room-ui` is the model to copy: `api(ha-model)` only, a pure `roomState(...)`
  (`RoomState.kt:28`).

### 2.4 Phone app: `app/`

`app/src/main` is ~28.5k lines in ~140 files. Most of it is legitimate glue, but these are reusable
logic trapped there:

- **P1: `HaAction` → `call_service` is written four times** (plus a fifth in app-ui):
  `dashboard/DashboardActionDispatcher.kt:228`, `widget/WidgetActionRunner.kt:193,345`,
  `wearsync/MobileWearSyncManager.kt:373`, `assistant/TerrazzoAppFunctions.kt:110`,
  `app-ui/.../CardActionCall.kt:27`. One common `HaActionExecutor`.
- **P1: Session lifecycle in Composables.** Cold-start resume, reconnect, sign-out:
  `TerrazzoApp.kt:119-145, 613-660`, `MainActivity.kt:109-115, 236-270`. A common
  `SessionSupervisor` would replace this and app-ui's copy.
- **P1: Card-design stores take a `Context` but need only a `File`.** `CardStyles.kt`,
  `CardDesignOverrideStore.kt`, `SkinStore.kt`, `PlaceholderCardStore.kt`, `CardFonts.kt`,
  `CardUsage.kt` (also duplicated as `app-ui/.../AppCardUsage`). Only `CardDesignSync`'s WorkManager
  scheduling is Android.
- **P1: Authenticated HA image loading** (`image/HaImageStack.kt`, `HaBearerAuthInterceptor`,
  `LiveImageCacheInterceptor`, `CameraStream`) is what every Android client needs, yet the app
  builds its own Coil/OkHttp stack while also depending on `rc-image-coil`.
- **P2: Service locator, not DI.** 42 `Context.terrazzoGraph()` calls, 23
  `LocalTerrazzoGraph.current` reads, 29 `X.of(context)` singletons, 2 `@Inject`. Anything moved
  to a library needs constructor parameters first.
- **P2: Smaller seams worth moving:** the `CrashReporter` interface (good seam, app-only; Wear has
  none), `HaDiscovery` mDNS (`discovery/HaDiscovery.kt:38`) as expect/actual in `ha-client`, push
  parse/dedupe logic (`notify/*`; `PushTokenSource` seam exists at `PushRelay.kt:100`).
- **P2: Duplicated theme and charts.** `ui/CardStyleAppTheme.kt:40`, `app-ui/.../AppTheme.kt:91`,
  `wear/.../WearTerrazzoTheme.kt`; `dashboard/HistoryCharts.kt` vs `wear/.../WearHistoryChart.kt`.
- **P2: Build file.** Play Services Wearable and Horologist are unconditional while Firebase is
  gated (`crashlyticsEnabled`), so a Google-free build still pulls Play Services; `work-testing`
  and `robolectric` versions are hard-coded outside the catalog; `compose.ui.test.manifest` is
  declared twice.

### 2.5 Cross-cutting

- **No library discipline in the build.** No `explicitApi()`, no Kotlin ABI validation, no
  `maven-publish`, no group/version, in any module or in `harc.base-conventions`. Without these,
  "library" has no enforceable meaning: any `public` is API by accident. Add a
  `harc.library-conventions` plugin: explicit API mode, ABI dump check, publishing coordinates,
  `allWarningsAsErrors` for libraries.
- **No working samples.** `demo-app/.../MainActivity.kt` shows only `Text("registry size …")`;
  the render path is a TODO. `integration/` is pixel-diff tests, not an integration layer.
- **Docs drift.** `offline-first.md:63,115` cites `TokenVault.lastInstance()`, which doesn't
  exist (code uses `offlineCache.lastInstance()`); `offline-first.md:100-110` expects TV on
  `CachedHaSession`, which minSdk blocks; `compose-multiplatform-apps.md:116` says Wear doesn't
  depend on app-ui (it does).

---

## 3. What an alternative client needs, and whether it can have it today

| A third-party client wants to… | Today | After the plan |
|---|---|---|
| Render one Lovelace card offline from JSON, on any platform | No public common call; Android path is `@RestrictTo`; pulls Ktor via `ha-client` | `rc-card-host.captureCard(card, snapshot)` + one converter family |
| Keep a rendered card live without re-encoding | Android only (`CachedCardPreview`) | common `rc-card-host` |
| Use only the HA WebSocket client | Possible, but takes design/agent/glyph clients too; Ktor types leak | slim `ha-client` |
| Feed the UI from its own data source / a fake | `HaSession` exists but needs API 35 + Metro + Terrazzo members | `ha-session-api` + `ha-fixtures` |
| Be offline-first on desktop/Wasm/iOS | Not possible (cache is androidMain) | `ha-session-cache` |
| Reuse sign-in | Copy one of four OAuth implementations | `ha-auth` + platform launcher |
| Reuse HA icon/colour/format logic in SwiftUI | Formatting yes; icons, colours, actions no | `ha-logic` |
| Supply its own icon set | No | `IconResolver` |
| Add a custom card converter | Yes (`withEnhancedShutter` pattern) | same, KMP reference in `rc-card-shutter` |
| Drop in a dashboard screen | Only by taking the whole Terrazzo shell (`app-ui`) | `dashboard-state` + `dashboard-ui` |
| Use from Swift | `TerrazzoKit` exports raw Kotlin API; app doesn't use it | narrow facade over `ha-session-api` |

---

## 4. Phased plan

Ordered so each phase is independently shippable and unblocks the next. Effort is rough.

**Phase 0: guardrails (small).**
1. `harc.library-conventions`: `explicitApi()`, ABI validation, publishing coordinates. Apply to
   `ha-model`, `room-ui`, `rc-components`, `rc-material3-motion`, `rc-player-ui` first (already
   clean), then each module as it is fixed.
2. Fix `api`/`implementation`: `rc-converter` and `rc-card-shutter` → `api(ha-model)`;
   `terrazzo-core` → `api(ha-model, ha-client)`; `ha-client` → `api(ktor-client-core)` or hide
   `HttpClientEngine` behind a factory.
3. Rename `rc-player-ui`'s package off `ee.schimke.terrazzo.shared`.

**Phase 1: unblock the form factors (small, highest leverage).**
4. Lower `terrazzo-core` to `minSdk 29`; gate API-35 code with `@RequiresApi`/runtime checks.
   Wear, TV and Quest can then drop their copies.
5. Move `CachedHaSession`, `DemoHaSession`, `OfflineOnlySession`, `OfflineCacheStorage` to
   `commonMain` (inject a clock; load demo dashboards as resources).
6. Extract `wear-sync-proto`; delete the copy.
7. ~~Move `BuiltDesign`, `CachedFontFace`, `GlyphPack`, `Sha256`, `CardGenerator` to
   `ha-design-model`; drop `rc-converter → ha-client`.~~ Done: moved into `ha-model`
   (`ee.schimke.ha.design`, `ee.schimke.ha.design.glyphs`, `ee.schimke.ha.model`); see
   [library-publishing.md](library-publishing.md). Moving `LocalCardGenerator` to a glue module is
   still open.

**Phase 2: the data-source contract (medium).**
8. `ha-session-api`: slim `HaSession` + capability interfaces, `HaTokenProvider`,
   `HaActionExecutor`. Point app-ui, Quest, Glasses and TV at it; delete
   `TerrazzoMultiplatformApp.kt:446-487` and `QuestRoomModel.kt:190-237`.
9. `ha-fixtures`: one demo source replacing the four.
10. `SessionSupervisor` in common code; remove lifecycle from `TerrazzoApp`/`MainActivity`.
11. Split `HaClient`: transport (`HaTransport`), core commands, `terrazzo-client`.

**Phase 3: rendering as a library (medium).**
12. `rc-card-host`: common port of `CachedCardPreview` + public `captureCard`.
13. Split `rc-converter` into core / built-in families / Android widgets / designs runtime.
14. `ha-logic`: icon-name, colour-role and tap-action resolution without Compose; `IconResolver`.
15. Split `ha-cards-catalog` runtime from tooling; move `compose.desktop.currentOs` out of
    `card-designs` main.

**Phase 4: UI and platforms (larger).**
16. `ha-auth` with platform launchers and `CredentialStore`; retire the four OAuth copies.
17. `dashboard-state` (headless) + `dashboard-ui`; `app-ui` becomes the Terrazzo shell on top.
18. Common entity controls/more-info and history charts (phone, app-ui, Wear).
19. Narrow `TerrazzoKit` facade (suspend + DTOs + callback bridge); move it out of `ha-client`.
20. Small modules for feedback chrome, crash-reporter seam, `ha-image-coil`, mDNS discovery.

**Samples (alongside Phase 1-3).** Replace `demo-app` with:
- `samples/offline-card` (JVM/CMP): card JSON + `HaSnapshot` JSON → converter → player, no network;
- `samples/live-card`: `LiveHaSession(url, token)` → `liveStates` → named bindings into one card
  (shows the [named-binding path](live-card-updates.md), not re-encode);
- `samples/custom-converter`: the current registry example, finished;
- `samples/ios-live`: Swift over the TerrazzoKit facade.

Each sample's Gradle dependency list is the real test of the layering: if `offline-card` resolves
Ktor, or `live-card` resolves AppAuth, a boundary has regressed. Add a dependency-assertion check
for them in CI.
