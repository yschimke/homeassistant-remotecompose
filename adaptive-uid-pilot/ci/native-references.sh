#!/bin/sh
set -eu
# Run inside the pinned compose-preview-host image, which carries BTA and Skiko.
resource=ee/schimke/composeai/uibuilder/renderer/ui-builder-renderer.bundle.png
archive=$(find /opt/compose-preview-server/lib -name '*ui-builder-render-bundle*.jar' -print -quit)
test -n "$archive"
mkdir -p "$RUNNER_TEMP/uid-renderer"
(cd "$RUNNER_TEMP/uid-renderer" && jar xf "$archive" "$resource")
mv "$RUNNER_TEMP/uid-renderer/$resource" "$RUNNER_TEMP/uid-renderer/catalog.png"
node _preview_server/scripts/ui-builder/publish-references.mjs \
  --root . --plan adaptive-uid-pilot/references.json \
  --out adaptive-uid-pilot/build/pilot --revision "$(git rev-parse HEAD)" \
  --renderer /opt/compose-preview-server/bin/compose-preview-server \
  --catalog "$RUNNER_TEMP/uid-renderer/catalog.png" \
  --components m3-catalog=/opt/compose-preview-server/ui-builder-components/m3-catalog-components-v1.json
