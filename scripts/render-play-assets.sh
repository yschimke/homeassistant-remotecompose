#!/usr/bin/env bash
#
# Render the Play-Store branding assets (hi-res icon, feature graphic)
# from the `Play_Icon_512` / `Play_Feature_1024x500` @Preview composables
# in the `:previews` module, and stage them into the listing graphics
# folders that the play-publisher plugin uploads.
#
# These two slots are siblings of the screenshot slots wired by
# `scripts/render-play-screenshots.sh` — kept in a separate script
# because they live in the `:previews` module (no app DI graph to wire
# up) rather than `:app`. Re-running overwrites the stage in place.

set -euo pipefail
cd "$(dirname "$0")/.."

RENDERS=previews/build/compose-previews/renders
LISTING=app/src/main/play/listings/en-GB/graphics

compose-preview render --module previews --filter Play_

# The CLI names a render `<function>_<preview name>[-<hash>].png` (older ones
# prefixed the `<File>Kt.` class); match on the stem so either resolves, and
# take the newest when an earlier run left stale hashes behind.
stage() {
  local stem=$1 dst=$2 found
  found=$(ls -t "$RENDERS/$stem".png "$RENDERS/$stem"-*.png \
    "$RENDERS"/*."$stem".png 2>/dev/null | head -n 1 || true)
  if [[ -z $found ]]; then
    echo "missing render: $RENDERS/$stem[-<hash>].png" >&2
    exit 1
  fi
  # The icon may keep its alpha channel (Play takes a 32-bit PNG); the feature
  # graphic must be 24-bit, so drop it there. Renders are opaque either way.
  case $dst in
    */feature-graphic/*) convert "$found" -alpha off "$dst" && chmod 0644 "$dst" ;;
    *) install -m 0644 "$found" "$dst" ;;
  esac
}

# Single file per slot — overwrite instead of wipe-and-replace so a
# rename of the source @Preview is visible as a single file rename in
# git review rather than as paired add/delete.
stage Play_Icon_512_play_icon_512 "$LISTING/icon/icon.png"
stage Play_Feature_1024x500_play_feature_1024x500 "$LISTING/feature-graphic/feature.png"

echo "Staged Play Store branding assets:"
ls -1 "$LISTING/icon" "$LISTING/feature-graphic"
