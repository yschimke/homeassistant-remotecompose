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

stage() {
  local src=$1 dst=$2
  if [[ ! -f $RENDERS/$src ]]; then
    echo "missing render: $RENDERS/$src" >&2
    exit 1
  fi
  install -m 0644 "$RENDERS/$src" "$dst"
}

# Single file per slot — overwrite instead of wipe-and-replace so a
# rename of the source @Preview is visible as a single file rename in
# git review rather than as paired add/delete.
stage PlayStoreGraphicsPreviewsKt.Play_Icon_512_play_icon_512.png \
  "$LISTING/icon/icon.png"
stage PlayStoreGraphicsPreviewsKt.Play_Feature_1024x500_play_feature_1024x500.png \
  "$LISTING/feature-graphic/feature.png"

echo "Staged Play Store branding assets:"
ls -1 "$LISTING/icon" "$LISTING/feature-graphic"
