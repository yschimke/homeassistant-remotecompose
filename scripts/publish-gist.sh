#!/usr/bin/env bash
#
# Push the staged comparison images to the preview gist:
#   https://gist.github.com/yschimke/09c6fbeb83f71b4de3442a410010ee3f
#
# Depends on `scripts/stage-gist.sh` running first (reads /tmp/ha-rc-gist).
# gh CLI's `gist create` doesn't accept binary uploads, so we push via
# the gist's git remote.

set -euo pipefail

GIST_ID="${GIST_ID:-09c6fbeb83f71b4de3442a410010ee3f}"
GIST_URL="https://gist.github.com/${GIST_ID}.git"
STAGE="/tmp/ha-rc-gist"
WORK="/tmp/ha-rc-gist-git"
MSG="${1:-Refresh preview gallery}"

[[ -d "$STAGE" ]] || { echo "missing $STAGE — run scripts/stage-gist.sh"; exit 2; }

rm -rf "$WORK"
git clone --quiet "$GIST_URL" "$WORK"
cd "$WORK"
rm -f *.png *.md
cp "$STAGE"/*.png "$STAGE"/README.md . 2>/dev/null || true

git add -A
git -c user.email=yuri@schimke.ee -c user.name="Yuri Schimke" commit -m "$MSG" 2>&1 | tail -3
git push 2>&1 | tail -3
echo "https://gist.github.com/yschimke/${GIST_ID}"
