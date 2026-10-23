# Home Assistant adaptive design workbench

Eleven key surfaces have editable UI Builder UID references and independently written
Compose previews. These are **stock Material 3 design proposals**, not a replacement for
production navigation, Remote Compose cards, or the Terrazzo theme. The candidate renderer
never reads UID layouts or reference PNGs. Fixtures contain English sample data; actions call
local callbacks and never connect to Home Assistant, authenticate, operate devices, alter
preferences, remove real pins, clear logs, or update a watch.

| Surface | UID | Production implementation |
| --- | --- | --- |
| Connect / discovery | [connection.uid](ui-builder/designs/connection.uid) | [DiscoveryScreen](../app/src/main/kotlin/ee/schimke/terrazzo/discovery/DiscoveryScreen.kt) |
| Dashboard picker | [dashboards.uid](ui-builder/designs/dashboards.uid) | [DashboardPickerScreen](../app-ui/src/commonMain/kotlin/ee/schimke/terrazzo/dashboard/DashboardPickerScreen.kt) |
| Dashboard view | [dashboard-view.uid](ui-builder/designs/dashboard-view.uid) | [DashboardViewScreen](../app/src/main/kotlin/ee/schimke/terrazzo/dashboard/DashboardViewScreen.kt) |
| Choose dashboards | [choose-dashboards.uid](ui-builder/designs/choose-dashboards.uid) | [DashboardSelectionScreen](../app-ui/src/commonMain/kotlin/ee/schimke/terrazzo/dashboard/DashboardSelectionScreen.kt) |
| Settings | [settings.uid](ui-builder/designs/settings.uid) | [SettingsScreen in TerrazzoApp](../app/src/main/kotlin/ee/schimke/terrazzo/TerrazzoApp.kt) |
| Installed widgets | [widgets.uid](ui-builder/designs/widgets.uid) | [WidgetsScreen](../app/src/main/kotlin/ee/schimke/terrazzo/widget/WidgetsScreen.kt) |
| Manage pinned | [pinned.uid](ui-builder/designs/pinned.uid) | [ManagePinnedScreen](../app/src/main/kotlin/ee/schimke/terrazzo/dashboard/ManagePinnedScreen.kt) |
| Wear widget slots | [wear-widgets.uid](ui-builder/designs/wear-widgets.uid) | Retired: the phone no longer assigns watch widgets; each widget's card is chosen on the watch ([WearWidgetsScreen](../wear/src/main/kotlin/ee/schimke/terrazzo/wear/ui/WearWidgetsScreen.kt)) |
| Sync diagnostics | [sync-diagnostics.uid](ui-builder/designs/sync-diagnostics.uid) | [SyncDiagnosticsScreen](../app/src/main/kotlin/ee/schimke/terrazzo/wearsync/SyncDiagnosticsScreen.kt) |
| Logs | [logs.uid](ui-builder/designs/logs.uid) | [LogsScreen](../app/src/main/kotlin/ee/schimke/terrazzo/logs/LogsScreen.kt) |
| Card history | [card-history.uid](ui-builder/designs/card-history.uid) | [CardHistoryScreen](../app/src/main/kotlin/ee/schimke/terrazzo/dashboard/CardHistoryScreen.kt) |

[DashboardBrowser](src/main/kotlin/ee/schimke/adaptivepilot/DashboardBrowser.kt) implements the
picker proposal; [KeyScreens](src/main/kotlin/ee/schimke/adaptivepilot/KeyScreens.kt) implements
the other proposals. The production links above provide context; these previews do not assert
pixel parity with the current production bodies. The installed-widget inventory is still a
placeholder in production, so its proposal shows the empty state and add-widget guidance.
Card history uses a value list, not the production chart. Remote Compose card types, external
browser authentication, actual Android/Wear widgets, permission dialogs, loading, keyboard-open,
large-text, scrolling long lists and screen-reader traversal require separate device coverage.

## Adaptive variants and interaction

[`references.json`](references.json) defines **100 comparisons**: eleven surfaces × phone/tablet ×
list/detail × light/dark, plus twelve connection-failure/offline-dashboard/empty-selection variants. Phone is
412 × 720dp; tablet is 840 × 720dp. Density is 2px/dp; the maximum image is 1680 × 1440px.
Every light/dark pair must differ in both candidate and reference. Tablet detail selects a
second destination, with a visible selection label, so it is not a duplicate of tablet list.
Alternate structural states use editable `*-secondary.uid` snapshots; connection failure and
offline dashboard are separate UID files. IDE handles in
[KeyScreenPreviews](src/main/kotlin/ee/schimke/adaptivepilot/KeyScreenPreviews.kt) and
[ScreenPreviews](src/main/kotlin/ee/schimke/adaptivepilot/ScreenPreviews.kt) cover these variants.

All screens use `currentWindowAdaptiveInfoV2()` and standard `ListDetailPaneScaffold` sizing.
The window owns adaptation; there are no content-box size classes or forced pane widths.
Phones show one pane, tablets overview and detail together. Tests cover the 839/840dp boundary,
narrow tablet content, resizing, edited URL callbacks, invalid-address protection, persistent
multi-dashboard choices, selected device actions, disabled offline controls, settings callbacks
and empty Wear slots. Proposal selection IDs are local fixture IDs, not persisted app preferences.

UID buttons are layout specimens. Use Compose previews/tests for behavior. The UI Builder editing
canvas unfolds panes; choose **Device view** for the captured adaptive layout. Download edits,
review and commit the UID before replacing a reference. Static UID layout references do not
reproduce the navigator's runtime focus restoration, so focus highlights can produce advisory diffs.

## Local rendering, PR diffs and existing catalog

```sh
./gradlew -PadaptiveUidPilot=true :adaptive-uid-pilot:ktfmtCheck \
  :adaptive-uid-pilot:test :adaptive-uid-pilot:renderPilot
node ../compose-preview-server/scripts/ui-builder/publish-references.mjs \
  --root . --plan adaptive-uid-pilot/references.json \
  --out adaptive-uid-pilot/build/pilot --revision "$(git rev-parse HEAD)" \
  --renderer /path/to/compose-preview-server \
  --catalog /path/to/ui-builder-renderer.bundle.png \
  --components m3-catalog=/path/to/m3-catalog-components-v1.json
python3 ../design-parity/scripts/uid/evidence.py \
  --plan adaptive-uid-pilot/references.json --root adaptive-uid-pilot/build/pilot
python3 ../design-parity/scripts/uid/package.py --pilot-directory adaptive-uid-pilot \
  --output adaptive-uid-pilot/build/key-screens.zip
```

Use matching released renderer assets and compiler sidecars; start with a fresh reference output
for each revision. The publisher verifies committed input bytes and records immutable source,
UID and image hashes. Candidates go to `build/pilot/previews/`. The PR's `Adaptive UID pilot`
check uploads `adaptive-uid-evidence`: download it and open `index.html` for Reference / Diff /
Actual. Shared tooling validates missing images, dimensions, hashes and declared distinct pairs;
changed pixels remain advisory. No new renderer or difference scorer is introduced here.

For the real local compare page and UID editor while the hosted deployment is unavailable:

```sh
/path/to/compose-preview-server serve --host 127.0.0.1 --port 18091 \
  --bundle key-screens=adaptive-uid-pilot/build/key-screens.zip \
  --ui-builder-state-dir adaptive-uid-pilot/build/editor-state \
  --ui-builder-dir /path/to/server-distribution/ui-builder
```

Use the authenticated browse link, then `/key-screens/compare/connection-840-detail-dark`.
**Open in UI Builder** loads the captured UID; downloaded edits need review and a commit.
Local serving does not publish. After merge, the existing Design Artifacts workflow folds the
prepared screens and UID references into [the existing Home Assistant catalog](https://preview.coo.ee/homeassistant-remotecompose/).
There is one catalog and one publisher. Source links go to the independent proposal; the table
above links production routes. Catalog metadata also supplies draft issue links for human review.

## Advisory AI audit

The trusted audit workflow reuses compose-ai-tools, `OPENROUTER_API_KEY`, its $0.25 cap and
[canonical m3-catalog guidelines](https://github.com/yschimke/m3-catalog/blob/e588d36f971616e0bb6374d40b57e50abd7384d5/ui-builder.guidelines.json)
verified by digest. No guidance copy is committed here. Same-repository runs only; no PR code
executes with the key. Rules, prompt source, model/cost records and annotated images are preserved.
Findings are advisory, not a gate or automatic issue filing.

The trusted workflow uses the **default-branch capture plan**. Before this expanded plan is
merged, automatic PR auditing covers the existing eight dashboard-picker captures, not the
whole 100-image suite. The full local independent agent audit is separate and must not be
reported as a completed 100-capture OpenRouter CI audit. After merge, new subjects can be audited
within the cap; budget-limited/failed runs are incomplete. Images alone do not prove behavior
or accessibility semantics.

Shared immutable workflow pins own renderer setup, evidence, packaging and trusted auditing.
This opt-in module owns app-specific designs, fixtures, states and interactions. It is excluded
from default Gradle projects unless `-PadaptiveUidPilot=true` is supplied.
