# Home Assistant dashboard browser

One adaptive screen proposal for the no-Figma design workflow. The reviewed design is
[`ui-builder/designs/dashboards.uid`](ui-builder/designs/dashboards.uid); the independently written
Compose implementation is [`DashboardBrowser.kt`](src/main/kotlin/ee/schimke/adaptivepilot/DashboardBrowser.kt).
The Kotlin screen never loads the UID or its rendered PNGs.

This is an **opt-in desktop pilot**, not a replacement for the app's current navigation,
existing design references or branded theme. It uses stock Material 3 from the native UID
renderer, fixed English sample data, and an `onOpen` callback. No Home Assistant connection,
radio transport, dashboard control or message send happens in this prototype. The callback
is tested with the selected entry's ID; previews leave it unconnected.

- **412 × 720dp:** list initially; choosing an entry replaces it with details. Back returns to the list.
- **840 × 720dp:** list and selected details side by side, using standard scaffold pane sizing.
- Selection survives resizing; interactive tests exercise the second entry, back, the action
  callback and the 839/840dp boundary. Back remains visible on tablets, as specified in the UID.
- Both widths have list/detail states in light/dark: eight comparisons at 2px/dp. Tablet list uses item 0; tablet detail uses item 1
  (Energy), so both tablet states exercise distinct selected content.
  The largest image is 1680 × 1440px, below 1800px in either dimension.

The `.uid` is an editable layout reference. Its buttons are layout specimens; use the Compose
screen for interaction behavior. The UI Builder's default editing view unfolds panes; select
**Device view** to inspect the adaptive layout at the captured size.

## Render and compare

```sh
./gradlew -PadaptiveUidPilot=true :adaptive-uid-pilot:test :adaptive-uid-pilot:renderPilot
```

The `Adaptive UID pilot` workflow runs formatting, interaction tests and candidate rendering,
then independently renders all eight committed UID references in the pinned native server image.
It uploads a validated preview bundle and `adaptive-uid-evidence`: open `index.html` for reference /
exact pixel diff / actual. `evidence.json` records image hashes, reference revisions and changed
pixel counts. Missing evidence fails CI; visual differences are advisory for human review.

`Adaptive UID design audit` follows successful runs in a separate trusted job. It uses the same
`compose-preview guidelines` engine, `OPENROUTER_API_KEY` repository secret and $0.25 budget as
remote-m3-catalog's preview publish workflow. Rules are fetched from canonical m3-catalog at
`e588d36f971616e0bb6374d40b57e50abd7384d5`, verified against the workflow's SHA-256 pin and archived with their source URL; the CLI is pinned to 2.40.0. Results, annotated
screenshots, model/provider/cost records, rules, engine prompt source and provenance are preserved
as `adaptive-uid-design-audit`. Findings are advisory; missing keys or failed requests are reported
as an incomplete audit, never a clean result. No issue or PR comment is posted automatically.

The trusted audit workflow only starts after this workflow file is merged into the default branch.
It runs pinned shared staging tools with the default-branch capture plan and guideline pins, and executes
no PR code or UID content with the key. This first CI audit receives pictures and capture sizes;
it cannot establish accessibility semantics or interaction behavior from those images. The custom
capture IDs are separate subjects, so cross-size guidance needs human review of the full matrix.
The shared [interactive critique prompt](https://github.com/yschimke/compose-ai-tools/blob/f7ef9df14d71a619864709bc38055ed2011af196/docs/UID_DESIGN_CRITIQUE_PROMPT.txt)
is separate from the versioned guidelines engine prompt. The local reference-rendering command remains useful for iteration:

Candidates go to `build/pilot/previews/`. They are **custom bundle IDs**, not discovery IDs.
The eight annotated IDE previews use separate discovery IDs and do not automatically acquire
these custom references. The pilot is excluded from default Gradle projects and the deployed app catalog; do not add
these IDs to the normal catalog spec without a discovery/publication adapter.

Commit the UID and `references.json` before publishing references. With the companion
[server UID workflow](https://github.com/yschimke/compose-preview-server/pull/1495) available:

```sh
node ../compose-preview-server/scripts/ui-builder/publish-references.mjs \
  --root . --plan adaptive-uid-pilot/references.json \
  --out adaptive-uid-pilot/build/pilot --revision "$(git rev-parse HEAD)" \
  --renderer /path/to/compose-preview-server \
  --catalog /path/to/ui-builder-renderer.bundle.png \
  --components m3-catalog=/path/to/m3-catalog-components-v1.json
```

Use the native renderer bundle and component record from the same released server/UI Builder
set, with its Kotlin compiler sidecar installed. The publisher verifies committed input bytes,
image dimensions and hashes; it updates the reference manifest only after every render succeeds.
Start each publication with a fresh `build/pilot` directory to avoid shipping old hashed files.
Updating Compose candidates never approves a new reference.

Package the candidates and references using the checker (run from the repository root):

```sh
python3 ../design-parity/scripts/uid/package.py --pilot-directory adaptive-uid-pilot \
  --output adaptive-uid-pilot/build/dashboards.zip
/path/to/compose-preview-server serve --host 127.0.0.1 --port 18081 \
  --bundle dashboards=adaptive-uid-pilot/build/dashboards.zip \
  --ui-builder-dir /path/to/server-distribution/ui-builder
```

Open the server's authenticated browse link, then `/dashboards/compare/dashboards-412-detail-light`.
Reference / Diff / Actual, overlays, region selection and the digest-bound **Open in UI Builder**
link use the existing server workflow. The editor opens that captured design; download edits,
review and commit them before replacing the baseline. Rendered artifacts stay under ignored
`build/`, not in source control.

An uploaded zip supplies comparisons and the UID editor, but it does **not** supply catalog
source/provenance metadata. App-specific code links and issue destinations require a catalog
producer (or a local `ServeBundleHost`) with repo `yschimke/homeassistant-remotecompose`, the captured commit,
module `adaptive-uid-pilot`, and variant `sourceFile` pointing at
`src/main/kotlin/ee/schimke/adaptivepilot/DashboardBrowser.kt`. Until publication is wired, put the
commit, viewport, theme, state, comparison evidence and `.uid` node in a normal repository issue.
Nothing here deploys to preview.coo.ee or files bugs automatically.

Adaptive layout uses `currentWindowAdaptiveInfoV2()` at the screen boundary and
`calculatePaneScaffoldDirective` / `ListDetailPaneScaffold` for pane adaptation.
The scaffold owns pane sizing; no effect forces a fixed first-pane width.
The UID uses `paneSizing: preferred` to express the same design intent.
Tests resize the window information, verify selection/back behavior across the
839/840dp boundary, and check that an 840dp window stays adaptive when a parent
leaves only 760dp of content width. A nested content box is not the window.

CI setup is shared: [design-parity's UID workflow](https://github.com/yschimke/design-parity/pull/540)
owns build-tool setup, native references, comparison and artifact handoff;
[compose-ai-tools' optional audit workflow](https://github.com/yschimke/compose-ai-tools/pull/5787)
owns the trusted image-only OpenRouter audit. Both callers pin immutable commits.
Only triggers/path filters, the app build command, screen code, interaction tests,
UID designs and capture/state bindings stay here. The app renderer reads dimensions,
density, theme, state and `candidateState.selectedIndex` from `references.json`.
It never loads the UID. `distinctCaptures` declares the tablet state pairs that must
differ, without encoding a universal breakpoint in the shared evidence validator.
The IDE annotation previews remain app-owned navigation handles.

Evidence validation, pixel diffs, HTML, audit staging and packaging come from
[design-parity's shared tools](https://github.com/yschimke/design-parity/tree/1cd1bbee6c4f061043710b2755cd782607f1245a/scripts/uid).
Native reference setup and renderer dependency pins come from the server's
`render-ci-references.sh`. Their tests live with those implementations.

Land the shared cleanup PRs before this consumer. Callers use immutable provider
commits for integration testing; update them to landed revisions before merging.
The audit starts only after its `workflow_run` caller reaches the default branch.

## Hosted UID comparisons

CI exports a catalog on each pilot PR and publishes it after a default-branch
build. The committed `.uid` documents provide the rendered design references.
Open the app design catalog to compare phone/tablet, list/detail, and light/dark
captures, follow the source link, open the captured design in UI Builder, or
prepare a GitHub issue containing the comparison evidence. UI Builder edits to a
reference snapshot stay in the browser; download the `.uid` and commit it to
propose a design change.

Catalog: https://preview.coo.ee/homeassistant-uid/

The shared design-parity workflow owns export and publication. This module only
declares its catalog identity and source mapping in `references.json`.
