#!/usr/bin/env bash
#
# Clean-render all @Preview composables to PNG.
#
# The compose-preview plugin's render cache doesn't drop stale outputs
# when a @Preview function is renamed; wipe the renders directory so
# every output is current. Output lands in
# `previews/build/compose-previews/renders/`.

set -euo pipefail
cd "$(dirname "$0")/.."

rm -rf previews/build/compose-previews/renders
./gradlew :previews:renderAllPreviews --rerun-tasks --no-daemon --no-configuration-cache
