#!/usr/bin/env bash
#
# Render the Play-Store-targeted @Preview composables in
# `app/src/main/kotlin/ee/schimke/terrazzo/previews/ScreenPreviews.kt`
# (functions named `Play_…`) and stage the PNGs into the listing
# graphics folders that the play-publisher plugin uploads.
#
# Output is wired one-to-one — each preview produces a single fixed-name
# PNG in the matching slot directory. Re-running the script overwrites
# the stage in place, so a render diff lands as a single git diff per
# slot rather than a stream of newly-named files.
#
# The Pixel 8a / 7-inch / 10-inch device specs and dpi tuning live in
# `ScreenPreviews.kt` — see the `PIXEL_8A_DEVICE` / `TABLET_7_DEVICE` /
# `TABLET_10_DEVICE` constants. The dpi is chosen so the rendered PNG
# stays under 1800 px in either dimension while preserving the device's
# aspect ratio.

set -euo pipefail
cd "$(dirname "$0")/.."

RENDERS=app/build/compose-previews/renders
LISTING=app/src/main/play/listings/en-GB/graphics
PHONE=$LISTING/phone-screenshots
T7=$LISTING/seven-inch-screenshots
T10=$LISTING/ten-inch-screenshots

# Render only the Play_* previews. compose-preview's `--filter` matches
# anywhere in the preview id, so the `Play_` prefix scopes the run.
compose-preview render --module app --filter Play_

stage() {
  local src=$1 dst=$2
  if [[ ! -f $RENDERS/$src ]]; then
    echo "missing render: $RENDERS/$src" >&2
    exit 1
  fi
  install -m 0644 "$RENDERS/$src" "$dst"
}

# Wipe stale slot files (a renamed preview leaves its old PNG behind
# otherwise) before re-staging.
find "$PHONE" "$T7" "$T10" -maxdepth 1 -name '*.png' -type f -delete

stage Play_Phone_01_HomeLight_play_phone_home_light.png    "$PHONE/01-home-light.png"
stage Play_Phone_02_HomeDark_play_phone_home_dark.png      "$PHONE/02-home-dark.png"
stage Play_Phone_03_Discovery_play_phone_discovery.png     "$PHONE/03-discovery.png"
stage Play_Phone_04_Picker_play_phone_picker.png           "$PHONE/04-dashboard-picker.png"
stage Play_Phone_05_Widgets_play_phone_widgets.png         "$PHONE/05-widgets.png"
stage Play_Tablet7_01_Home_play_7-inch_home.png            "$T7/01-home.png"
stage Play_Tablet10_01_Home_play_10-inch_home.png          "$T10/01-home.png"

echo "Staged Play Store screenshots:"
ls -1 "$PHONE" "$T7" "$T10"
