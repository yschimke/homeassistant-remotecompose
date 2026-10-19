#!/usr/bin/env bash
#
# After a Play graphics render, put back every committed PNG whose pixels
# didn't visibly change. Renders aren't byte-stable across machines and CLI
# versions: the compressed bytes shift, and antialiased edges drift by a level
# or two (2/255 seen between runs). A plain `git status` would report a refresh
# on every run. New and deleted files are left alone.
#
# Usage: scripts/restore-unchanged-play-graphics.sh [dir]  (default: the listing)

set -euo pipefail
cd "$(dirname "$0")/.."

dir=${1:-app/src/main/play/listings}
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

git diff --name-only -- "$dir" | while IFS= read -r f; do
  [[ $f == *.png && -f $f ]] || continue
  git show "HEAD:$f" > "$tmp/old.png"
  # `compare -metric AE` prints the count of pixels differing by more than
  # the fuzz (2% ≈ 5/255 per channel); a size change makes it error out,
  # which also counts as changed.
  if diff_px=$(compare -fuzz 2% -metric AE "$tmp/old.png" "$f" null: 2>&1) && [[ $diff_px == 0 ]]; then
    git checkout -- "$f"
    echo "unchanged pixels, restored: $f"
  else
    echo "changed: $f (${diff_px} px)"
  fi
done
