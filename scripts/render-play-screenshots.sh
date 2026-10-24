#!/usr/bin/env bash
#
# Render the Play-Store-targeted @Preview composables in
# `app/src/main/kotlin/ee/schimke/terrazzo/previews/ScreenPreviews.kt` and
# `wear/src/main/kotlin/ee/schimke/terrazzo/wear/ui/WearPreviews.kt`
# (functions named `Play_…`) and stage the PNGs into the listing
# graphics folders that the play-publisher plugin uploads. Wear shares the
# phone's listing, so its shots land under the same `graphics/` tree.
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
WEAR_RENDERS=wear/build/compose-previews/renders
LISTING=app/src/main/play/listings/en-GB/graphics
PHONE=$LISTING/phone-screenshots
T7=$LISTING/seven-inch-screenshots
T10=$LISTING/ten-inch-screenshots
WEAR=$LISTING/wear-screenshots

# Render only the Play_* previews. compose-preview's `--filter` matches
# anywhere in the preview id, so the `Play_` prefix scopes the run.
compose-preview render --module app --filter Play_
compose-preview render --module wear --filter Play_

# The CLI names a render `<function>_<preview name>[-<hash>].png`; match on
# the stem so a hash suffix (newer CLIs) or none (older ones) both resolve,
# and take the newest when an earlier run left stale hashes behind.
render_for() {
  local dir=$1 stem=$2 found
  found=$(ls -t "$dir/$stem".png "$dir/$stem"-*.png 2>/dev/null | head -n 1 || true)
  if [[ -z $found ]]; then
    echo "missing render: $dir/$stem[-<hash>].png" >&2
    exit 1
  fi
  printf '%s\n' "$found"
}

# The renderer writes `<render>.warnings.json` when a frame never settled (a
# spinner still turning, content still loading) and it fell back to the latest
# frame. That's not a store screenshot: keep the committed one and flag it.
unsettled() {
  local warnings=$1.warnings.json
  [[ -f $warnings ]] && grep -q '"unsettledCaptures":\[{' "$warnings"
}

keep_committed() {
  echo "::warning file=$2::$(basename "$1") did not settle (still animating or loading); keeping the committed $2" >&2
  git checkout HEAD -- "$2" 2>/dev/null || echo "::warning::no committed $2 to keep" >&2
}

# Play takes screenshots as JPEG or 24-bit PNG, without an alpha channel. The
# renders are RGBA but fully opaque (PhoneHost paints its background), so
# dropping the channel changes no pixel.
stage() {
  local src
  src=$(render_for "$RENDERS" "$1")
  if unsettled "$src"; then keep_committed "$src" "$2"; return; fi
  convert "$src" -alpha off "$2"
  chmod 0644 "$2"
}

# Round watch renders are clipped to a transparent circle, but Play rejects
# screenshots with alpha. Flatten onto black, as a real watch screenshot reads.
stage_wear() {
  local src
  src=$(render_for "$WEAR_RENDERS" "$1")
  if unsettled "$src"; then keep_committed "$src" "$2"; return; fi
  convert "$src" -background black -alpha remove -alpha off "$2"
  chmod 0644 "$2"
}

# Wipe stale slot files (a renamed preview leaves its old PNG behind
# otherwise) before re-staging.
mkdir -p "$WEAR"
find "$PHONE" "$T7" "$T10" "$WEAR" -maxdepth 1 -name '*.png' -type f -delete

stage Play_Phone_01_HomeLight_play_phone_home_light    "$PHONE/01-home-light.png"
stage Play_Phone_02_HomeDark_play_phone_home_dark      "$PHONE/02-home-dark.png"
stage Play_Phone_03_Discovery_play_phone_discovery     "$PHONE/03-discovery.png"
stage Play_Phone_04_Picker_play_phone_picker           "$PHONE/04-dashboard-picker.png"
stage Play_Phone_05_WatchCards_play_phone_watch_cards     "$PHONE/05-watch-cards.png"
stage Play_Tablet7_01_Home_play_7_inch_home            "$T7/01-home.png"
stage Play_Tablet10_01_Home_play_10_inch_home          "$T10/01-home.png"
stage_wear Play_Wear_01_Home_play_wear_home            "$WEAR/01-home.png"
stage_wear Play_Wear_02_WidgetLarge_play_wear_widget_large "$WEAR/02-widget-large.png"
stage_wear Play_Wear_03_WidgetSmall_play_wear_widget_small "$WEAR/03-widget-small.png"
stage_wear Play_Wear_04_Dashboard_play_wear_dashboard  "$WEAR/04-dashboard.png"

echo "Staged Play Store screenshots:"
ls -1 "$PHONE" "$T7" "$T10" "$WEAR"
