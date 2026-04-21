#!/usr/bin/env bash
#
# Stage comparison images for the preview gist:
# /tmp/ha-rc-gist/
#   ref_<card>_{light,dark}.png    # committed HA screenshots
#   rc_<card>_{light,dark}.png     # our latest rendered previews
#   README.md                      # side-by-side comparison
#
# Prereq: `scripts/render-previews.sh` has run recently.

set -euo pipefail
cd "$(dirname "$0")/.."

OUT=/tmp/ha-rc-gist
rm -rf "$OUT" && mkdir -p "$OUT"

# HA reference captures — rename under gist scheme.
cp references/tile/temperature_sensor_light.png "$OUT/ref_tile_temperaturesensor_light.png"
cp references/tile/temperature_sensor_dark.png "$OUT/ref_tile_temperaturesensor_dark.png"
cp references/tile/light_on_light.png "$OUT/ref_tile_lighton_light.png"
cp references/tile/light_on_dark.png "$OUT/ref_tile_lighton_dark.png"

SRC="previews/build/compose-previews/renders"

# Tile previews (pinned function names — diff tests look them up).
cp "$SRC/ee.schimke.ha.previews.TileCardPreviewsKt.Tile_TemperatureSensor_tile — sensor temperature.png" "$OUT/rc_tile_temperaturesensor_light.png"
cp "$SRC/ee.schimke.ha.previews.TileCardPreviewsKt.Tile_TemperatureSensor_Dark_tile — sensor temperature (dark).png" "$OUT/rc_tile_temperaturesensor_dark.png"
cp "$SRC/ee.schimke.ha.previews.TileCardPreviewsKt.Tile_LightOn_tile — light on.png" "$OUT/rc_tile_lighton_light.png"
cp "$SRC/ee.schimke.ha.previews.TileCardPreviewsKt.Tile_LightOn_Dark_tile — light on (dark).png" "$OUT/rc_tile_lighton_dark.png"

# Card previews — match `<Kind>_{Light,Dark}` (current convention).
map() {
    local pattern="$1" out="$2"
    for f in "$SRC"/$pattern; do [ -f "$f" ] && cp "$f" "$OUT/$out" && return 0; done
}
map "*Button_Light_button*PARAM_0*" "rc_button_light_on.png"
map "*Button_Light_button*PARAM_1*" "rc_button_light_off.png"
map "*Button_Light_button*PARAM_2*" "rc_button_light_unavailable.png"
map "*Button_Dark_button*PARAM_0*"  "rc_button_dark_on.png"
map "*Button_Dark_button*PARAM_1*"  "rc_button_dark_off.png"
map "*Button_Dark_button*PARAM_2*"  "rc_button_dark_unavailable.png"
map "*Entity_Light_entity*"         "rc_entity_light.png"
map "*Entity_Dark_entity*"          "rc_entity_dark.png"
map "*Entities_Light_entities*"     "rc_entities_light.png"
map "*Entities_Dark_entities*"      "rc_entities_dark.png"
map "*Glance_Light_glance*"         "rc_glance_light.png"
map "*Glance_Dark_glance*"          "rc_glance_dark.png"
map "*Heading_Light_heading*"       "rc_heading_light.png"
map "*Heading_Dark_heading*"        "rc_heading_dark.png"
map "*Markdown_Light_markdown*"     "rc_markdown_light.png"
map "*Markdown_Dark_markdown*"      "rc_markdown_dark.png"
map "*VerticalStack_Light_vertical-stack*"     "rc_verticalstack_light.png"
map "*VerticalStack_Dark_vertical-stack*"      "rc_verticalstack_dark.png"
map "*HorizontalStack_Light_horizontal-stack*" "rc_horizontalstack_light.png"
map "*HorizontalStack_Dark_horizontal-stack*"  "rc_horizontalstack_dark.png"
map "*Grid_Light_grid*"             "rc_grid_light.png"
map "*Grid_Dark_grid*"              "rc_grid_dark.png"
map "*Unsupported_Light_unsupported*" "rc_unsupported_light.png"
map "*Unsupported_Dark_unsupported*"  "rc_unsupported_dark.png"

# State-variant tiles (PARAM_0/1/2 for on/off/unavailable or locked/unlocked/locking etc.)
map "*Tile_Light_States*PARAM_0*"  "rc_tile_light_on.png"
map "*Tile_Light_States*PARAM_1*"  "rc_tile_light_off.png"
map "*Tile_Light_States*PARAM_2*"  "rc_tile_light_unavailable.png"
map "*Tile_Cover_States*PARAM_0*"  "rc_tile_cover_closed.png"
map "*Tile_Cover_States*PARAM_1*"  "rc_tile_cover_open.png"
map "*Tile_Cover_States*PARAM_2*"  "rc_tile_cover_opening.png"
map "*Tile_Lock_States*PARAM_0*"   "rc_tile_lock_locked.png"
map "*Tile_Lock_States*PARAM_1*"   "rc_tile_lock_unlocked.png"
map "*Tile_Lock_States*PARAM_2*"   "rc_tile_lock_locking.png"

echo "Staged $(ls "$OUT"/*.png 2>/dev/null | wc -l) PNGs in $OUT"
