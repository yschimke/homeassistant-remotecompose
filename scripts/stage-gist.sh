#!/usr/bin/env bash
#
# Stage comparison images for the preview gist:
# /tmp/ha-rc-gist/
#   ref_<card>_<variant>_<theme>.png   # committed HA screenshots
#   rc_<card>_<variant>_<theme>.png    # our latest rendered previews
#   README.md                          # side-by-side comparison
#
# Prereq: `scripts/render-previews.sh` has run recently.

set -euo pipefail
cd "$(dirname "$0")/.."

OUT=/tmp/ha-rc-gist
rm -rf "$OUT" && mkdir -p "$OUT"

# —— HA reference captures (committed under references/) ——
for f in references/*/*.png; do
    card=$(basename "$(dirname "$f")")       # e.g. tile / button / entity
    name=$(basename "$f" .png)               # e.g. temperature_sensor_light
    cp "$f" "$OUT/ref_${card}_${name}.png"
done

SRC="previews/build/compose-previews/renders"

# —— Tile previews (pinned function names for the diff test) ——
cp "$SRC/ee.schimke.ha.previews.TileCardPreviewsKt.Tile_TemperatureSensor_tile — sensor temperature.png" "$OUT/rc_tile_temperature_sensor_light.png"
cp "$SRC/ee.schimke.ha.previews.TileCardPreviewsKt.Tile_TemperatureSensor_Dark_tile — sensor temperature (dark).png" "$OUT/rc_tile_temperature_sensor_dark.png"
cp "$SRC/ee.schimke.ha.previews.TileCardPreviewsKt.Tile_LightOn_tile — light on.png" "$OUT/rc_tile_light_on_light.png"
cp "$SRC/ee.schimke.ha.previews.TileCardPreviewsKt.Tile_LightOn_Dark_tile — light on (dark).png" "$OUT/rc_tile_light_on_dark.png"

# —— @PreviewParameter state-variants (light theme only) ——
map_param() {
    local pattern="$1" out="$2"
    for f in "$SRC"/$pattern; do [ -f "$f" ] && cp "$f" "$OUT/$out" && return 0; done
}
map_param "*Tile_Light_States*PARAM_0*"  "rc_tile_light_states_on.png"
map_param "*Tile_Light_States*PARAM_1*"  "rc_tile_light_states_off.png"
map_param "*Tile_Light_States*PARAM_2*"  "rc_tile_light_states_unavailable.png"
map_param "*Tile_Cover_States*PARAM_0*"  "rc_tile_cover_states_closed.png"
map_param "*Tile_Cover_States*PARAM_1*"  "rc_tile_cover_states_open.png"
map_param "*Tile_Cover_States*PARAM_2*"  "rc_tile_cover_states_opening.png"
map_param "*Tile_Lock_States*PARAM_0*"   "rc_tile_lock_states_locked.png"
map_param "*Tile_Lock_States*PARAM_1*"   "rc_tile_lock_states_unlocked.png"
map_param "*Tile_Lock_States*PARAM_2*"   "rc_tile_lock_states_locking.png"
map_param "*Button_Light_button*PARAM_0*" "rc_button_states_on_light.png"
map_param "*Button_Light_button*PARAM_1*" "rc_button_states_off_light.png"
map_param "*Button_Dark_button*PARAM_0*"  "rc_button_states_on_dark.png"
map_param "*Button_Dark_button*PARAM_1*"  "rc_button_states_off_dark.png"

# —— Non-parameter card previews ——
map() {
    local pattern="$1" out="$2"
    for f in "$SRC"/$pattern; do [ -f "$f" ] && cp "$f" "$OUT/$out" && return 0; done
}
map "*Entity_Light_entity*"      "rc_entity_temperature_sensor_light.png"
map "*Entity_Dark_entity*"       "rc_entity_temperature_sensor_dark.png"
map "*Entities_Light_entities*"  "rc_entities_living_room_light.png"
map "*Entities_Dark_entities*"   "rc_entities_living_room_dark.png"
map "*Glance_Light_glance*"      "rc_glance_overview_light.png"
map "*Glance_Dark_glance*"       "rc_glance_overview_dark.png"
map "*Heading_Light_heading*"    "rc_heading_title_light.png"
map "*Heading_Dark_heading*"     "rc_heading_title_dark.png"
map "*Markdown_Light_markdown*"  "rc_markdown_notes_light.png"
map "*Markdown_Dark_markdown*"   "rc_markdown_notes_dark.png"
map "*VerticalStack_Light_vertical-stack*"     "rc_verticalstack_light.png"
map "*VerticalStack_Dark_vertical-stack*"      "rc_verticalstack_dark.png"
map "*HorizontalStack_Light_horizontal-stack*" "rc_horizontalstack_light.png"
map "*HorizontalStack_Dark_horizontal-stack*"  "rc_horizontalstack_dark.png"
map "*Grid_Light_grid*"          "rc_grid_light.png"
map "*Grid_Dark_grid*"           "rc_grid_dark.png"
map "*Unsupported_Light_unsupported*" "rc_unsupported_light.png"
map "*Unsupported_Dark_unsupported*"  "rc_unsupported_dark.png"

echo "Staged $(ls "$OUT"/*.png 2>/dev/null | wc -l) PNGs in $OUT"
