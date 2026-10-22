# BOM audit — 2026-10-22

## Result

Two corrections are needed, on existing branches:

- [HARC #1044](https://github.com/yschimke/homeassistant-remotecompose/pull/1044): use `rc-players-bom:2.17.2` with versionless player aliases in every consuming configuration, including the standalone browser ZIP configuration. Export the platform with `api` where the player dependency is exposed through `api`.
- [Design Parity #604](https://github.com/yschimke/design-parity/pull/604): read each ZIP's version from the BOM rather than substituting the BOM version into both download URLs. The existing browser CI job fails with HTTP 404 for the JS ZIP at 2.17.1.

No other definite BOM misuse was established by the declaration, publication metadata and resolution checks below. That is not a claim that every application configuration was compiled or rendered.

## Specifically, is #1044 correct?

The desired Compose player upgrade is valid; the explanation and manual alignment are not correct on the current published release.

The PR's original head is `5a603ad1e1edbb7d82c99888626238c9270cc986`. It declares compose 2.17.2, edit/protocol 2.17.0, and says only compose was published at 2.17.2. The [published BOM POM](https://repo.maven.apache.org/maven2/ee/schimke/composeai/rc-players-bom/2.17.2/rc-players-bom-2.17.2.pom) and [Gradle module metadata](https://repo.maven.apache.org/maven2/ee/schimke/composeai/rc-players-bom/2.17.2/rc-players-bom-2.17.2.module) agree:

| Artifact | BOM 2.17.2 constraint | Original PR's Android resolution |
|---|---:|---:|
| rc-player-compose | 2.17.2 | 2.17.2 |
| rc-player-runtime | 2.17.2 | 2.17.2, transitively |
| rc-player-protocol | 2.17.2 | 2.17.2, overriding the direct 2.17.0 floor |
| rc-player-trace | 2.17.2 | 2.17.2, transitively |
| rc-player-edit | 2.17.2 | **2.17.0** |
| rc-player-wasm-dist | 2.17.2 | Not consumed by this PR |
| remote-compose-player-js-dist | **2.17.0** | Separate direct pin |
| third-party-rc-embedded-player | **2.17.0** | Not consumed by this PR |

The 2.17.2 Kotlin modules exist. Gradle's newest-wins resolution already promotes runtime/protocol/trace through compose's metadata; the comment does not describe what actually runs. Edit remains on an older release. This establishes deviation from the producer's published set, **not a demonstrated runtime crash**. The fix consumes the producer's set rather than inventing an equality rule. Future partial releases can legitimately select different Kotlin module versions too.

Current `main` at `a18b2182` had independently moved edit/protocol to 2.17.2 while leaving compose at 2.17.1; #1044 conflicted with that catalog edit. The branch incorporates current main without rewriting the existing published commit, then replaces the competing pins with one BOM. Documentation also corrects the stale JS 2.16.1 claim.

One configuration detail matters: setting `embedPlayer.isTransitive = false` prevents traversal of the BOM's constraints and fails versionless ZIP resolution. The corrected configuration permits platform traversal and sets `isTransitive = false` on the ZIP dependency only. The test downloads exactly one ZIP and checks `bundle.js` exists.

## How repositories were discovered

Window: 2026-10-15 through the audit on 2026-10-22 UTC, using calendar-date boundaries. Evidence was collected around 05:45–06:05 UTC; revisions are snapshots, not a promise about later pushes.

Discovery combined the authenticated user's GitHub contribution collection (commits by repository, created PRs, reviews and issues), paginated authored PR and commit searches, and recent user events. All 487 PR-creation contributions were paginated; they covered 22 repositories. Review contributions added `lysine-dev/okhttp`. Commit contributions also identified a private fork, checked separately without publishing its identity or contents here. Repository push dates were not used as proof of user work.

An authored PR's `updated_at` is only a candidate signal: [ClimateTraceKMP #165](https://github.com/joreilly/ClimateTraceKMP/pull/165) was created in 2025 and closed this week by someone else; the paginated timeline of [ktorio/ktor #4013](https://github.com/ktorio/ktor/pull/4013) did not establish recent user activity. They were screened but excluded from the recent-work conclusion. The user's rename of [yschimke/ktor #1](https://github.com/yschimke/ktor/pull/1) on October 17 does establish recent activity, albeit on an older change.

[Inventory evidence](inventory.json) contains candidate repositories, public revision snapshots and activity links. [Resolved graphs](resolved-dependencies.json) contain every selected component and selection reason for the 25 representative graphs. They are real Gradle resolutions, not inferred versions from TOML.

## Repository inventory

Names below are under `yschimke/` unless qualified. “Resolved” means the representative published graph was resolved with current declared versions, not that the entire source build ran.

| Repository | Scope and finding |
|---|---|
| homeassistant-remotecompose | All player consumers, Compose/Firebase/daemon imports, catalog and custom ZIP configuration. Player alignment fixed on #1044; actual Android and browser dependency reports also pass. |
| rc-players | BOM generation, partial-release manifest version selection, KMP root/target metadata, published Android embedded-player dependency policy. Producer BOM uses effective per-artifact versions. Resolved player graphs on Android/JVM/Wasm/iOS. |
| compose-ai-tools | Main and included-plugin catalogs, convention-added daemon/contracts platforms, samples and standalone daemon benchmark configurations, tools BOM nested imports. Resolved daemon desktop/contracts graph. Stable, compatibility and alpha sample lanes intentionally differ. |
| compose-preview-daemon | Contracts alignment convention, player imports and daemon BOM's contracts import; published platform metadata. Resolved contracts/player graph. |
| compose-preview-contracts | Generated BOM constraints and partial-release selection; published root/target metadata. Resolved through consumers. BOM release 3.35.0 legitimately selects many modules at 3.32.0. |
| compose-ui-builder | Common/JVM source-set platform imports, `api` exports, standalone protocol-schema configuration and generated BOM. Resolved external runtime/export/protocol/player graph through this repo and server. Web ZIP excluded from Maven BOM intentionally. |
| compose-preview-server | Tools/contracts/daemon/builder/player platforms and standalone Android/CMP/desktop sidecar configurations. Resolved representative JVM graph. JS 2.17.0 and Wasm 2.17.2 have explicit distribution selectors; these match the player BOM. |
| compose-preview-mcp | `api` platform imports and versionless core dependencies. Resolved JVM graph. Explicit UI Builder web release and JS ZIP are separately fetched distributions. |
| compose-ai-contrib | Daemon/contracts platforms, scripting and driver/session/discovery declarations, contract test custom renderer runtimes. Resolved JVM graph. Explicit older tools/session versions exist; consolidation is a discussion choice below. |
| compose-preview-client | Compose BOM 2026.06.01 imported by mobile, wear and player. Versionless AndroidX UI/foundation/material3; Wear, activity and Remote Compose are independently versioned. No missing import found. Declaration/metadata audit, not full Android build. |
| compose-preview-bazel | MODULE.bazel, rules and runtime/toolchain declarations. Toolchain/CLI archives and caller-supplied Maven lockfiles, rather than a shared Gradle classpath. No BOM misuse established; Bazel itself was not run. |
| compose-preview-vscode | Node extension manifests/workflows. CLI/server integration, no Gradle/Maven dependency graph to align. |
| m3-catalog | Tools/contracts/daemon imports and explicit JVM exporter/source-composite coordinates. Resolved annotations/contracts graph. Exporter test pin is not assumed to equal the web editor release. |
| wear-m3-catalog | Common/Android/desktop platform imports and independently versioned Wear artifacts. Resolved current annotations/platform graph. Existing upgrade #850 has render assertion failures; not evidence that distinct plugin/BOM versions are wrong. |
| remote-m3-catalog | Player BOM, nested tools/daemon/contracts imports, custom renderer source substitutions and intentional prerelease AndroidX lane. Resolved connector/annotation graph. Direct pins currently select the BOM's partial-release members. |
| meshcore-mobile | Production KMP player, Android preview annotations/connector/embedded player, AndroidX/Wear release trio and optional source-composite catalog. Resolved Android preview and desktop player graphs. Trace 2.13.1 with compose/runtime/protocol 2.16.0 is the published dependency set, not automatically skew. |
| design-parity | Node manifests, Gradle test fixtures and CI Maven distribution downloads. Fixed the false “BOM version = both ZIP versions” assumption on #604. |
| lysine-dev/okhttp | Actual recent user reviews; BC platform import scopes in JVM/Android tests, compileOnly production and testing-support API; okhttp-bom generation. Resolved BC 1.86 graph. Not required to replace unrelated application JDK-specific BC alignment with this different artifact-family BOM. |
| androidx/androidx | Examined the user's recent commit file changes. Only one affected Gradle declarations (`compose/remote/remote-player-compose` and `remote-testing`); changes concern compileSdk, core and font dependency removal. This is upstream source/project dependency publication, not a downstream BOM consumer. Entire AndroidX monorepo was not resolved. |
| yschimke/ktor | Recent user PR rename; fork dependency declarations and ktor-bom generation screened. No recent BOM-changing implementation or definite misuse established. No Ktor build run. |
| cadence | Node/automation configuration screened; no application Maven/BOM graph found. |
| coo-ee-env | Node/browser/environment configuration screened; no application Maven/BOM graph found. |
| preview-coo-ee | Deployment/source configuration screened; no Gradle/Maven consumer graph. No deployment performed. |
| renovate-config | Dependency grouping/regex rules screened; no runtime BOM consumer. Grouping is not treated as proof of resolved alignment. |
| skills | Skill/package configuration screened; no Maven/BOM graph. |
| Bastih18/SoManyPpl | Recent attributed profile contribution found by commit search. Repository is documentation/profile content, no dependency graph. |
| homeassistant-remotecompose-out, glimmer-catalog-out | Generated publication repositories: root catalog/bundle/output structure checked, no independent source build/BOM declaration. Bundled historical artifacts were not all unpacked. |
| compose-ai-tools-out, wear-m3-catalog-out, m3-catalog-out, remote-m3-catalog-out | Root contains README only at audit time. No independent dependency declarations. |
| meshcore-mobile-out | Generated baseline/history/render outputs, no independent Maven build. |
| joreilly/ClimateTraceKMP, ktorio/ktor | Candidate false positives described above; not counted as actual recent user implementation activity. Their dependency declarations were screened but no project-wide conclusion is made. |

## Publication and resolution evidence

- Every explicit composeai catalog artifact was checked for published metadata. The exceptions are deliberately non-Maven UI Builder web ZIPs and synthetic renderer-source coordinates substituted by an explicitly supplied source checkout. These are not missing Maven releases to “fix.”
- All **358 distinct composeai coordinates** constrained by the catalog BOMs and their Maven imports returned published POMs. [Existence results](bom-member-checks.json) are separate from dependency resolution evidence.
- For **13 composeai BOMs**, Maven dependency-management constraints and Gradle module platform constraints agreed. Root BOMs constrain KMP root coordinates; target selection follows `available-at` metadata. Target artifacts are not expected to have a separate, universally equal release number.
- All **25 representative graphs** resolved with no unresolved dependency edges. Kotlin's usage/platform compatibility rules and JVM-environment attributes were registered: an Android graph must request environment `android`, otherwise a synthetic fixture can select desktop Compose metadata and falsely report unavailable Android Skiko artifacts.
- The nested tools BOM imports daemon/contracts BOMs. Daemon imports contracts. The producer implementations use effective per-module versions from the publication manifest, not the release tag indiscriminately. No `enforcedPlatform` was introduced into library metadata.
- HARC's actual `:rc-converter:dependencies --configuration debugRuntimeClasspath` resolves player modules to 2.17.2 and Material 3 to 1.5.0-beta01; no `FAILED` nodes. Its actual `:design-server:dependencies --configuration embedPlayer` selects JS 2.17.0 through BOM 2.17.2. See the [Android tree](harc-android-dependencies.txt) and [browser tree](harc-embed-dependencies.txt). The actual KMP consumer also resolves its [Wasm runtime graph](harc-wasm-dependencies.txt) without failed nodes.

Reproduce the archived representative graphs using JDK 21 and this repository's wrapper:

```sh
./gradlew -p docs/audits/bom-2026-10-22/resolver audit
```

The resolver writes `results.json` and fails if any dependency edge is unresolved. Inputs are frozen audit snapshots. For the ongoing HARC catalog/consumer check, run:

```sh
./gradlew -p scripts/player-dependency-check check
```

That independent build covers Android, desktop, Wasm, iOS device and Apple Silicon simulator metadata and downloads the BOM-selected JS archive. It compares each demanded root module to **its own** BOM constraint, not the BOM version number.

## Intentional differences and discussion items

1. **Compose overrides are real overrides, not syntax mistakes.** HARC imports stable Compose BOM 2026.09.00, which recommends Material 3 1.4.0; its documented explicit 1.5.0-beta01 requirement wins. Foundation/UI resolve at 1.12.1. MaterialKolor introduces its CMP Material 3 dependency too. Whether to stay on that beta or upgrade with #1035 is an API/render compatibility decision, not grounds to remove the pin. The AndroidX BOM does not manage JetBrains CMP coordinates, Wear Compose, activity, navigation or Remote Compose's alpha release family.
2. **Separate versions do not mean separate mistakes.** Player BOM 2.17.2 deliberately selects JS/embedded distributions at 2.17.0. Tools BOM 2.70.7 selects several tools modules at 2.69.3; daemon BOM 3.21.4 selects annotations at 3.21.1 and renderer-desktop at 3.21.2. All resolve. Updating a BOM must not imply rewriting every module version to the BOM number.
3. **Contrib could adopt the tools BOM for its older explicit tool/session declarations.** Current discovery/launch-builder 2.66.0 and session/driver 2.61.2 exist and resolve with daemon 3.18.5/contracts 3.35.0. No runtime incompatibility was reproduced. Consolidation or upgrading is therefore left for discussion; it would change a tested older API surface.
4. **MeshCore could replace preview/player pins with platforms.** Its current graph resolves compose/runtime/protocol 2.16.0, trace/embedded 2.13.1, annotations/connector 3.17.1 and contracts modules 3.32.0. These are existing published members, not invented release versions. Adopting BOMs is worthwhile future maintenance, but upgrading that stack or deciding the desired preview plugin floor needs rendering evidence.
5. **Remote catalog has redundant direct floors already matching selected BOM members.** Removing them would simplify maintenance but does not correct a demonstrated resolution failure today.
6. **Wear catalog #850 remains a render follow-up.** Its separate tools BOM 2.67.0 and plugin 2.66.0 are correct for a partial release. Existing CI reports `CatalogRenderTest` failures, not missing Maven versions. Tracked separately as [SCH-49](https://linear.app/yschimke/issue/SCH-49/wear-catalog-tooling-pr-850-fails-catalogrendertest-render-assertions); no off-scope render changes were made.
7. **Unsupported targets stay unsupported.** Current CMP/player metadata omits Intel iOS simulator support. A BOM cannot add a missing target; no version forcing or artificial alignment was added for that configuration.

A version catalog records requested versions; a regular platform supplies constraints and does not freeze the graph. Higher direct/transitive requests can win. See [Gradle's catalogs/platforms documentation](https://docs.gradle.org/current/userguide/centralizing_catalog_platform.html) and [platform publication documentation](https://docs.gradle.org/current/userguide/java_platform_plugin.html). For Maven, import ordering can select a different version than Gradle's highest-wins policy; this audit checks the published imports but does not claim to have run Maven consumer builds.

## Validation and limits

- HARC: full `./gradlew ktfmtCheck` passed (97 executed tasks); real Android/browser dependency reports passed. The standalone five-target player check, real JS ZIP inspection and `:design-server:embedResources` passed. Restoring an explicit compose version was observed failing the consumer guard (`rc-player-compose must take its version from the BOM`), then the corrected catalog passed. A non-transitive-platform attempt was observed failing versionless ZIP resolution before the configuration was corrected.
- Design Parity: `npm ci`, `npm run build`, and `npm test` passed: 2,042 Vitest cases, 258 conformance cases, and 1,410 export-driver cases; 39 export-driver cases skipped in the local environment. Five new Python regression checks pass. The workflow-wiring test failed before the workflow fix. Existing workflow and published-version shell checks pass once their required `jq` is present.
- The fixed Design Parity download block was executed with BOM 2.17.1. It selected Wasm 2.17.1 / JS 2.17.0, verified both published SHA-1 sidecars and extracted both distributions successfully. No downloaded executable was needed for that smoke check.
- This Linux VM initially lacked JDK, Gradle, jq and unzip; temporary tools were provisioned under `/tmp`. No Android SDK or Xcode build, full application render suite, exhaustive Gradle configuration matrix, Bazel execution, Maven consumer invocation or deployment was performed. Firebase dependencies were resolved in a fixture; no real Firebase credentials were used.
- The archived graphs exercise representative dependencies, not every dependency in each source project. Producer Gradle code and published metadata were examined, but releases were not republished. Historical generated catalog bundles were not all unpacked and audited.
- Private fork findings are kept out of this public report. Its declaration structure was screened separately; no change was proposed.
- Existing PRs and Hive sessions were checked before edits. Neither fix duplicates an existing PR. No merge or deployment is authorized or performed.
