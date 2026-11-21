# Library publishing

Status: set up, off until the Central namespace and keys are configured (see [Enabling](#enabling)).
Follows [library-adoption-review.md](library-adoption-review.md), which explains why each module is,
or is not yet, fit to publish.

## What publishes

`ee.schimke.ha:<module>:<version>`, with every module at the release's version. Import the BOM and
name modules without versions:

```kotlin
dependencies {
  implementation(platform("ee.schimke.ha:ha-bom:0.1.99"))
  implementation("ee.schimke.ha:ha-model")
  implementation("ee.schimke.ha:rc-converter")
}
```

| Module | Targets | What it is for | Pulls in |
|---|---|---|---|
| `ha-model` | Android, JVM, Wasm, iOS | Dashboard, card config and state types; state formatting, Jinja, section grid; design, font and glyph stores and value types | kotlinx-serialization, datetime |
| `ha-client` | Android, JVM, Wasm, iOS | Home Assistant WebSocket + REST client; Terrazzo integration, design-library and card-source clients | ha-model, Ktor (one engine per target), coroutines |
| `rc-mdi-icons` | Android, JVM, Wasm, iOS | MDI icons by name for Remote Compose | MDI catalog |
| `rc-material3-motion` | Android, JVM, Wasm, iOS | Material 3 motion tokens for RC creation | RC creation |
| `rc-components` | Android, JVM, Wasm, iOS | RC card components and themes, HA-model-free | RC creation, rc-players, materialkolor |
| `rc-player-ui` | Android, JVM, Wasm, iOS | `RemoteComposeCard(bytes, bindings)` | rc-players Compose player |
| `rc-converter` | Android, JVM, Wasm, iOS | `CardConverter`, `CardRegistry`, built-in converters, bundled designs, template capture/playback | ha-model, rc-components, rc-mdi-icons, rc-player-ui (no ha-client) |
| `room-ui` | Android, JVM, Wasm | Room state from a snapshot, and its UI | ha-model, CMP |
| `ha-bom` | platform | Version constraints for all of the above | |

The list lives in one place, `HarcPublishing.LIBRARIES` in `build-logic`; a module also applies
`id("harc.publishing")` and names itself in `mavenPublishing { pom { … } }`. A published module may
depend only on published coordinates or on other modules in the list, or its POM points consumers
at something that doesn't exist.

Not published, and why:

| Module | Blocker |
|---|---|
| `terrazzo-core` | Metro DI in commonMain; it is the phone app's services (AppAuth, DataStore, widgets, Wear sync), not a library. Its common `HaSession` should move out first (review §2.1). |
| `app-ui` | One app's shell: 17-parameter entry point, Play update, keyring, webview, telemetry. |
| `ha-cards-catalog` | Composeai preview tooling on its main classpath. Its runtime half (`CardDesignDocument`) is wanted; split it out (below). |
| `card-designs` | Design generation tooling; `java.io.File` in common, `compose.desktop.currentOs` in jvmMain. |
| `rc-card-shutter`, `rc-components-ui`, `rc-image-coil` | Android-only without needing to be; small. Publishable as-is if wanted, better after going KMP. |

## Versions

- A release publishes at the tag: the `maven-central` job in `release-build.yml` runs
  `./gradlew publishHarcLibraries -Pharc.libraryVersion=<tag without v>`.
- Anything else is the next patch after `.github/.release-please-manifest.json` as `-SNAPSHOT`, so
  `./gradlew publishHarcLibrariesToMavenLocal` never claims a released version.
- Libraries share the app's version line, which release-please bumps by a patch on every release.
  Versions therefore say nothing about API compatibility. That is acceptable at `0.x`; see
  [What else](#what-else) for when it stops being.

## Enabling

1. Verify the `ee.schimke.ha` namespace (or `ee.schimke`, which covers it) on the Central Portal for
   the account that already publishes `ee.schimke.composeai`.
2. Add the repository secrets livery and rc-players use: `MAVEN_CENTRAL_USERNAME`,
   `MAVEN_CENTRAL_PASSWORD` (a Central Portal user token), `SIGNING_KEY` (ASCII-armoured private
   PGP key), `SIGNING_KEY_ID`, `SIGNING_KEY_PASSWORD`.
3. Set the repository variable `MAVEN_CENTRAL_PUBLISH=true`. The next release publishes; until then
   the job is skipped and nothing else in the release changes.

To change the group, edit `HarcPublishing.GROUP`.

---

## Review: can an app adopt the parts it wants?

### Just the template system, locally (no server, no network)

**Yes, functionally; not as a small dependency.** The offline path is public in common code:

1. `CardDesignTemplates.keyFor(card, snapshot, style, dark, cardStyle)` picks a design key.
2. Bytes, one of:
   - `CardDesignLibrary.template(host, key)` with `CommonCaptureHost` (non-Android) or a `Context`,
     drawing the bundled `.uid` designs compiled into `rc-converter`;
   - your own design: `CardDesignTemplates.captureWith(host, key) { c, s, m -> … }`;
   - pre-captured `.rc` blobs: `cachedDesignRepository(store).resolve(key)`, seeded from an
     asset bundle with `BundledDesigns.seed(store, open)`. No HTTP client is created.
3. `CardDesignTemplates.forDisplay(bytes, density, fontScale)`.
4. `CardDesignTemplates.playback(designCard(card, snapshot), bytes)` → `CardTemplatePlayback`;
   each snapshot `playback.bindings(snapshot, clock)`; taps `playback.resolveTap(action, snapshot)`.
5. `RemoteComposeCard(bytes, bindings = …)`, which seeds values before the first frame.

Fonts and glyphs work offline: `InMemoryFontStore` + `HaFontLibrary.install(faces)`;
`InMemoryGlyphStore` + `GlyphCache.stored()`.

What stops it being a clean local library:

- **Done: it no longer pulls in Ktor.** The network-free design types now live in `ha-model`:
  - `ee.schimke.ha.design`: `BuiltDesign`, `Sha256`, `DesignStore` and `InMemoryDesignStore`,
    `StoredManifest`, `GeneratedDesigns`, `InbuiltDesigns`, `DesignBlob`, `BundledDesigns`,
    `FontStore`, `InMemoryFontStore`, `CachedFontFace` and `CardDesignOverrides`;
  - `ee.schimke.ha.design.glyphs`: `GlyphPack`, `GlyphPackCodec`, `GlyphStore`,
    `InMemoryGlyphStore` and `GlyphRequest`;
  - `ee.schimke.ha.model`: the `CardGenerator` / `CardSource` chain.

  `rc-converter` no longer depends on `ha-client`; `ha-client` keeps the network half
  (`DesignRepository`, `DesignFonts`, `GlyphCache`, `DesignPublisher`, `CardStudioClient`). A
  consumer that only renders gets no Home Assistant client. It still resolves Ktor, through
  rc-players' `rc-player-compose`; dropping that is rc-players' change to make.
- **The glue from bindings to player is private.** The `CardSnapshotBindings` → `RcNamedValue` map
  is private in `app-ui` (`LibraryCard.kt:340`). The only player that wires taps and bindings
  together is `CachedCardPreview`, and it is androidMain only. Publish `toNamedValues()` and a
  common `TemplateCard(playback, snapshot, onAction)` composable.
- **The bundled design set can't be swapped.** `CardDesigns.design()` hard-wires the internal
  `BundledCardDesigns`. Add a `CardDesignSource` with the bundled set as the default.
  Optionally move the `.uid` files into a separate `card-designs-bundled` artifact, so an app that
  brings its own designs doesn't ship ours.
- **Parsing a `.uid` needs an unpublished tooling module.** `CardDesignDocument.parse` is in
  `ha-cards-catalog` next to composeai preview tooling. Move it to a runtime module and publish it.
- **Small gaps:** `CardDesignLibrary` only blocks, which doesn't work on Wasm; add a suspend
  `template`. `FileLocalDesignStore` is androidMain but plain JVM; move it to jdkMain.

The resulting split is three parts. The **runtime** (offline) is `rc-converter` plus the design
model. **Sync** is `DesignRepository` refresh, `DesignFonts.refresh`, the network half of
`GlyphCache`, `TemplateUsage` and `DesignPublisher`, staying in `ha-client` or moving to
`ha-design-sync`. **Authoring** (`card-designs`, catalog stickers, `design-server`) stays
unpublished.

### The client API to the Terrazzo integration and add-on

**Partly.** `ha-client` has a working client for the integration's WebSocket commands:
`registerTerrazzoPush`, `takeTerrazzoPushes`, `watchTerrazzoStates`, `terrazzoDesignServer`,
`terrazzoDesignLink`, design selections, `reportTerrazzoWebOrigin`, `relayAgentDraft` and the
`AgentEdits` edit sessions. It also has clients for the add-on (`AddonClient`) and the design server
(`DesignRepository`, `CardStudioClient`, `AgentRelayClient`).

**A versioned handshake, on the WebSocket only.** The integration answers `terrazzo/info` with
`{protocol, min_client, version, capabilities}` (`custom_components/terrazzo/capabilities.py`):
`edit`, `push`, `design_selections`, `web_origin` and `agent` are revisions (all 1),
`design_server` is `{available, version}`, `mcp` is `{enabled}` and `wall` is `"v1"`. A revision
or object only ever gains optional fields; a missing key means unsupported; `protocol` bumps only
on a breaking change. The answer is per user: `edit_apply`, `design_server_open` and `mcp` are
left out for users who can't use them. `ha-client` reads it with `HaClient.detectTerrazzo()` →
`Installed(info)` / `Absent` / `TooOld(version)` / `Unsupported(minClient)`: on
`unknown_command` it asks Core's `manifest/get` for `terrazzo` to tell an older integration from
none. The HTTP APIs are still versioned by path only (`/designs/v1`, `wall/v1`, the add-on's
`/v1`); the design manifest has `schema` and `minClient`.

But:

- **Mostly untyped.** Push, design-server info, selections and web-origin replies are hand-parsed
  `JsonObject`s inside `HaClient`. The typed DTOs that do exist (`AgentEditSession`, `EditScope`,
  relay state) live in `ha-client`, so a consumer can't have them without Ktor. Only the
  design-library DTOs are in `ha-model`.
- **Gaps:** the wall API (`/api/terrazzo/wall/v1/*`; only the C++ wall client uses it), the add-on's
  `/v1/snapshot` and `/v1/stream`, and several `/api/studio/dashboard/*` routes have no Kotlin
  client.
- **Mixed into the HA client.** The `terrazzo/*` methods sit in the 1296-line `HaClient` next to the
  core HA commands. `HaSession` (in the unpublished `terrazzo-core`) exposes them as members every
  implementation has to carry.

Proposed:

- **`terrazzo-protocol`** (serialization only): edit-session DTOs, relay DTOs, typed replies
  (`PushRegistration`, `DesignServerInfo`, `DesignSelections`, `WebOriginStatus`, `DesignLink`),
  command-name constants, wall `v1` DTOs.
- **`terrazzo-client`**: move `TerrazzoInfo` and `detectTerrazzo` there, over a small
  `HaWebSocketTransport` interface (which `HaClient` implements).
- **`HaSession`:** `LiveHaSession` still probes each command and catches `unknown_command`. It
  should call `detectTerrazzo()` once per connection and gate each Terrazzo member on its
  capability; the members then become capability interfaces checked with `as?`.
- **Add-on and design server:** mirror `terrazzo/info` as `GET /v1/info`.

### Dependencies, and supplying a configured HTTP client per platform

Changed in this PR:

- **`HaHttpClientFactory`** (`ha-client`): one seam through which every library client makes its
  Ktor `HttpClient`. The library passes the plugins it needs; the factory supplies the engine and
  its own configuration around them. On Android that means an app's own `OkHttpClient` (custom CA
  for a self-signed Home Assistant, client certificates, interceptors); on Apple, `Darwin` with
  `handleChallenge`; on the JVM, CIO or Java; on Wasm, Js. Every client takes an optional
  `httpClients` parameter next to its existing `engine` parameter: `HaClient`, `HaEntityStates`,
  `AddonClient`, `AgentRelayClient` and `AgentCardDesigner`. So do the design helpers:
  `createDesignRepository`, `DesignFonts.refresh`, `DesignFilters.bitmapFonts` and
  `uploadWithDefaultClient`.
- **Fixed: Android clients built without an engine skipped the shared client.** `HaClient` and
  `AddonClient` built `HttpClient {}` on Ktor's default engine. On Android that meant a fresh
  `OkHttpClient` without `SharedOkHttpClient`'s `ForbiddenCertificatesHostnameVerifier`, its
  connection pool or its dispatcher. They now go through the platform factory like the other
  clients.
- **`api` where types are public:** `ha-client` exposes Ktor core and coroutines;
  `rc-converter` exposes `ha-model`. Both were `implementation` while their types sat in public
  signatures, which compiled only because every consumer happened to add them itself.
  (`rc-converter` briefly exposed `ha-client` too, until the design types moved to `ha-model`.)
- **Fixed: common metadata didn't compile.** `TemplateBindings` used
  `RegexOption.DOT_MATCHES_ALL`, which isn't in common Kotlin; `ha-model` built per target but not
  as a published multiplatform library.

Still to do:

- `CardStudioClient`'s public constructors take only an engine; add `httpClients` there too.
- `GlyphCache` defaults to its own platform client.
- `terrazzo-core`'s `LiveHaSession` takes only an engine.
- `SharedOkHttpClient` is a process-global `object`. An app supplying its own client should not
  also get ours; once callers use the factory everywhere, make the shared client an internal default.
- Each target ships one fixed engine as `implementation`: OkHttp on Android, CIO on the JVM, Darwin
  on Apple, Js on Wasm. A JVM app that uses the Java engine still gets CIO on its classpath. That's
  acceptable now; if it matters, move the default engines to a separate `ha-client-engines` artifact.
- `rc-components` puts `material-icons-extended` on the main classpath for about 26 glyphs, which
  is large on Wasm and iOS. `MdiIcons` is a closed `object`; add an `IconResolver`.

### What else

- **API discipline before `1.0`.** There is no `explicitApi()` and no ABI validation, so every
  `public` declaration is API by accident (for example `design/*` helpers such as `handoffToken`).
  Turn on Kotlin's built-in ABI validation (`abiValidation {}`), commit the dumps for the published
  modules, then tighten visibility module by module.
- **A separate version line.** Libraries currently take a patch bump on every app release, even
  when nothing in them changed. Once anyone depends on them, use a library version line and publish
  only what changed: livery's `maven-publish-plan.sh` + `release-tasks.gradle.kts` do exactly this,
  with the BOM pinning each module at the version it last published.
- **Package and artifact names.** `rc-player-ui` publishes `ee.schimke.terrazzo.shared`, the app's
  package, which `app-ui` also uses. Rename it before people import it. `rc-player-ui` also sits
  close to rc-players' `rc-player-*` artifacts; a name such as `ha-card-player` would be clearer.
- **`ha-client` is six modules in one.** Publishing it fixes its shape in consumers' builds.
  Splitting it (transport, HA core, Terrazzo, design sync, card sources, the iOS facade) is cheaper
  before people depend on it. See review §2.1.
- **Samples as boundary tests.** `samples/offline-card` should resolve no Ktor and
  `samples/live-card` no AppAuth. A dependency assertion on each catches a regressed boundary that a
  green build won't.
- **A PR check.** `publishHarcLibrariesToMavenLocal` compiles every target's common metadata and
  POM. That is the check that would have caught the `DOT_MATCHES_ALL` break. It is cheaper than a
  release-day failure; run it in CI on changes to published modules.
- **Docs.** The only API documentation is KDoc. Dokka HTML for the published modules, or at least
  a README per module with its entry point, would help adopters. Central accepts the empty javadoc
  jars the plugin publishes today.
