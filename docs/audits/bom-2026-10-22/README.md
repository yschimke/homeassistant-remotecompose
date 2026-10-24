# BOM audit — 2026-10-22

## Corrections

- [HARC #1044](https://github.com/yschimke/homeassistant-remotecompose/pull/1044): import `rc-players-bom:2.17.2` for every player consumer, with versionless aliases. Export the platform with `api` where the player is exposed through `api`. The standalone browser ZIP configuration needs its own import; keep platform traversal enabled and make only the ZIP dependency non-transitive.
- [Design Parity #604](https://github.com/yschimke/design-parity/pull/604): read each ZIP version from the BOM. Its old workflow incorrectly used the BOM version for both ZIP URLs and failed with HTTP 404.

## Was #1044 correct?

The compose upgrade is valid, but its claim that only compose shipped at 2.17.2 is incorrect. The [published BOM](https://repo.maven.apache.org/maven2/ee/schimke/composeai/rc-players-bom/2.17.2/rc-players-bom-2.17.2.pom) selects compose/runtime/protocol/trace/edit at **2.17.2**, while JS and the vendored embedded player remain **2.17.0**.

The original PR's actual Android graph promoted runtime/protocol/trace to 2.17.2 transitively but left edit at **2.17.0**. Current main had independently moved edit/protocol to 2.17.2 while leaving compose at 2.17.1. The fix resolves that conflict and consumes the producer's published set. This corrects alignment; no runtime crash was demonstrated. Different artifact versions are valid when the BOM selects them.

## Inventory and discovery

Scope: October 15–22 UTC. Discovery used actual user commit, PR-creation and review contributions, paginated searches and events—not repository push dates. All 487 PR-creation contributions were paginated. Checked 33 recently active public repositories plus one private fork; coverage varies below. Names are under `yschimke/` unless qualified.

| Repositories | Scope and result |
|---|---|
| homeassistant-remotecompose | All player consumers and Compose/Firebase/daemon imports; fixed #1044. Actual Android, Wasm and browser dependency reports pass. |
| rc-players, compose-preview-contracts | BOM generation, partial-release version selection and published KMP metadata. No producer misuse found. |
| compose-ai-tools, compose-preview-daemon | Convention-added platforms, nested BOM imports, stable/compatibility/alpha lanes and standalone configurations. Representative graphs resolve. |
| compose-ui-builder, compose-preview-server, compose-preview-mcp | Platform scopes, API exports, schema/sidecar configurations and external consumer graphs. Web ZIPs are intentionally outside Maven BOMs. |
| compose-ai-contrib | Daemon/contracts platforms and older explicit driver/session/discovery versions resolve; consolidation is a discussion choice. |
| compose-preview-client | Compose platform imports checked in mobile, wear and player; no missing import found. |
| m3-catalog, wear-m3-catalog, remote-m3-catalog | Platforms, source substitutions and intentional prerelease lanes checked; representative graphs resolve. Wear #850 has existing render assertions requiring separate investigation. |
| meshcore-mobile | Android preview and desktop player graphs resolve. Trace/embedded 2.13.1 alongside player 2.16.0 is a published set, not automatically skew. |
| design-parity | Fixed CI distribution selection on existing #604; Gradle test fixtures also screened. |
| lysine-dev/okhttp | Recent user reviews establish scope. BC import scopes and okhttp-bom generation checked; BC 1.86 graph resolves. |
| androidx/androidx | Recent user commit changes inspected, including the two changed Remote Compose Gradle files. Entire upstream monorepo not resolved. |
| yschimke/ktor | Recent PR rename establishes activity; fork declarations and BOM generation screened. No recent BOM implementation change found. |
| compose-preview-bazel | Toolchains, archives and caller-supplied Maven lockfiles screened; Bazel not executed. |
| compose-preview-vscode, cadence, coo-ee-env, preview-coo-ee, renovate-config, skills | Configuration screened; no application Maven/BOM consumer graph found. |
| Bastih18/SoManyPpl | Recent attributed profile contribution; no dependency graph. |
| homeassistant-remotecompose-out, glimmer-catalog-out, compose-ai-tools-out, wear-m3-catalog-out, m3-catalog-out, remote-m3-catalog-out, meshcore-mobile-out | Generated publication roots checked; no independent source build. Historical bundles not all unpacked. |
| One private fork | Declaration structure screened separately; identity and contents excluded from this public report. |

Two candidate false positives were excluded: `joreilly/ClimateTraceKMP` had an old authored PR closed this week by another person; `ktorio/ktor`'s old authored PR timeline did not establish recent user activity.

## Evidence and limits

- All **25 representative published graphs** resolved without unresolved edges; all **358 distinct composeai coordinates** named by the audited BOMs existed. Maven and Gradle constraints agreed for **13 composeai BOMs**. These are representative resolutions, not an exhaustive configuration matrix.
- HARC: full `ktfmtCheck`, actual Android/Wasm/browser dependency reports, five-target player check, real JS ZIP inspection and `:design-server:embedResources` passed. Restoring an explicit player pin failed the regression guard; restoring the fix passed. Run `./gradlew -p scripts/player-dependency-check check` for the ongoing check.
- Design Parity: build and tests passed (2,042 Vitest, 258 conformance, 1,410 export-driver cases; 39 local skips), plus five new Python checks and existing shell checks. The fixed download block selected Wasm 2.17.1 / JS 2.17.0 from BOM 2.17.1, verified both checksums and extracted both ZIPs. Its full CI passed.
- No full application render suite, Xcode build, Bazel execution, Maven consumer build or deployment was performed locally. Generated dependency dumps remain session evidence rather than committed source.

## Discussion choices left unchanged

HARC's documented Material 3 1.5.0-beta01 requirement overrides the stable BOM's 1.4.0 recommendation; actual Foundation/UI resolve at 1.12.1. Removing that pin is an API/render compatibility choice. The AndroidX BOM does not manage JetBrains CMP, Wear, activity, navigation or Remote Compose's separate release families.

Contrib's older tools/session pins and MeshCore's working preview/player pins could move to BOMs, but no incompatibility was reproduced. Remote catalog's explicit floors currently match selected BOM members. None justifies an automatic upgrade. A BOM also cannot supply unsupported Intel iOS simulator variants.

Wear catalog #850's existing render assertion failures are tracked as [SCH-49](https://linear.app/yschimke/issue/SCH-49/wear-catalog-tooling-pr-850-fails-catalogrendertest-render-assertions). Separate plugin/BOM versions are valid for its partial release; the failed assertions do not establish a resolution defect.
