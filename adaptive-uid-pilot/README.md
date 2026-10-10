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
- **840 × 720dp:** list and selected details side by side, with a 360dp list pane.
- Selection survives resizing; interactive tests exercise the second entry, back, the action
  callback and the 839/840dp boundary. Back remains visible on tablets, as specified in the UID.
- Both widths have list/detail states in light/dark: eight comparisons at 2px/dp.
  The largest image is 1680 × 1440px, below 1800px in either dimension.

The `.uid` is an editable layout reference. Its buttons are layout specimens; use the Compose
screen for interaction behavior. The UI Builder's default editing view unfolds panes; select
**Device view** to inspect the adaptive layout at the captured size.

## Render and compare

```sh
./gradlew -PadaptiveUidPilot=true :adaptive-uid-pilot:test :adaptive-uid-pilot:renderPilot
```

Candidates go to `build/pilot/previews/`. They are **custom bundle IDs**, not discovery IDs.
The pilot is excluded from default Gradle projects and the deployed app catalog; do not add
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
python3 adaptive-uid-pilot/package.py
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
