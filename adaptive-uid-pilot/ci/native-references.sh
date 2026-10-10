#!/bin/sh
set -eu
# Run inside the pinned compose-preview-host image, which carries BTA and Skiko.
# Checkout runs as the runner user; this container executes as root. Trust only this checkout.
git config --global --add safe.directory "$(pwd)"
revision=$(git rev-parse HEAD)
resource=ee/schimke/composeai/uibuilder/renderer/ui-builder-renderer.bundle.png
archive=$(find /opt/compose-preview-server/lib -name '*ui-builder-render-bundle*.jar' -print -quit)
test -n "$archive"
mkdir -p "$RUNNER_TEMP/uid-renderer"
(cd "$RUNNER_TEMP/uid-renderer" && jar xf "$archive" "$resource")
mv "$RUNNER_TEMP/uid-renderer/$resource" "$RUNNER_TEMP/uid-renderer/catalog.png"
# The packaged renderer also contains Wear components. Their four CMP jars live
# in the publisher's GitHub Maven repository, which `design --local` cannot add
# to its default Central/Google resolvers. Seed only those exact, hash-bound jars.
wear_repository=https://raw.githubusercontent.com/yschimke/wear-m3-catalog-out/de0a10b7a4846b66fb84e1f94c8e8c6ff6ec9937
wear_version=1.7.0-beta02-cmp09
while read -r artifact expected; do
  relative="ee/schimke/wearcmp/$artifact/$wear_version/$artifact-$wear_version.jar"
  target="$RUNNER_TEMP/uid-maven/$relative"
  mkdir -p "$(dirname "$target")"
  curl -fsSL --retry 3 "$wear_repository/$relative" -o "$target"
  printf '%s  %s\n' "$expected" "$target" | sha256sum -c -
done <<'DEPS'
wear-compose-material3-jvm e940a5c2b434597accb0f566f91ebdfd5153c2640c7e96ebb3f49deb1b56c769
wear-compose-foundation-jvm c7fa9805e244f65358891dfe722316d59ce42a6f2103fcd6cfd49f22e2ff7607
wear-compose-material-core-jvm 9902a61d1c005641f97f10c866ade2dd45af30ef2eb2221689255fc887b1d5b0
port-runtime-jvm 938668a0361ebc8bfc69983e4090ded00ceffea09082abc00541b33128dc53fd
DEPS
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dmaven.repo.local=$RUNNER_TEMP/uid-maven"

node _preview_server/scripts/ui-builder/publish-references.mjs \
  --root . --plan adaptive-uid-pilot/references.json \
  --out adaptive-uid-pilot/build/pilot --revision "$revision" \
  --renderer /opt/compose-preview-server/bin/compose-preview-server \
  --catalog "$RUNNER_TEMP/uid-renderer/catalog.png" \
  --components m3-catalog=/opt/compose-preview-server/ui-builder-components/m3-catalog-components-v1.json
