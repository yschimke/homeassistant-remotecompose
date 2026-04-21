#!/usr/bin/env bash
#
# Stage comparison images for the preview gist:
# /tmp/ha-rc-gist/
#   ref_<card>_{light,dark}.png    # committed HA screenshots
#   rc_<card>_{light,dark}.png     # our latest rendered previews
#   README.md                      # side-by-side comparison
#
# Prereq: `scripts/render-previews.sh` has run recently (renders must
# match current @Preview set).

set -euo pipefail
cd "$(dirname "$0")/.."

OUT=/tmp/ha-rc-gist
rm -rf "$OUT" && mkdir -p "$OUT"

# HA reference captures (already committed) — rename under gist scheme.
cp references/tile/temperature_sensor_light.png "$OUT/ref_tile_temperaturesensor_light.png"
cp references/tile/temperature_sensor_dark.png "$OUT/ref_tile_temperaturesensor_dark.png"
cp references/tile/light_on_light.png "$OUT/ref_tile_lighton_light.png"
cp references/tile/light_on_dark.png "$OUT/ref_tile_lighton_dark.png"

SRC="previews/build/compose-previews/renders"

# Tile previews use specific function names (pinned by the diff test).
cp "$SRC/ee.schimke.ha.previews.TileCardPreviewsKt.Tile_TemperatureSensor_tile — sensor temperature.png" "$OUT/rc_tile_temperaturesensor_light.png"
cp "$SRC/ee.schimke.ha.previews.TileCardPreviewsKt.Tile_TemperatureSensor_Dark_tile — sensor temperature (dark).png" "$OUT/rc_tile_temperaturesensor_dark.png"
cp "$SRC/ee.schimke.ha.previews.TileCardPreviewsKt.Tile_LightOn_tile — light on.png" "$OUT/rc_tile_lighton_light.png"
cp "$SRC/ee.schimke.ha.previews.TileCardPreviewsKt.Tile_LightOn_Dark_tile — light on (dark).png" "$OUT/rc_tile_lighton_dark.png"

# All remaining @Preview outputs: copy with a clean `rc_<kind>_<theme>.png` name.
for f in "$SRC"/ee.schimke.ha.previews.CardPreviewsKt.*_Light_*.png \
         "$SRC"/ee.schimke.ha.previews.CardPreviewsKt.*_Dark_*.png; do
    [ -f "$f" ] || continue
    name=$(basename "$f")
    case "$name" in
      *Button_LightToggle_Light*) cp "$f" "$OUT/rc_button_lighttoggle_light.png" ;;
      *Button_LightToggle_Dark*)  cp "$f" "$OUT/rc_button_lighttoggle_dark.png" ;;
      *Entity_TemperatureSensor_Light*) cp "$f" "$OUT/rc_entity_temperaturesensor_light.png" ;;
      *Entity_TemperatureSensor_Dark*)  cp "$f" "$OUT/rc_entity_temperaturesensor_dark.png" ;;
      *Entities_MixedList_Light*) cp "$f" "$OUT/rc_entities_mixedlist_light.png" ;;
      *Entities_MixedList_Dark*)  cp "$f" "$OUT/rc_entities_mixedlist_dark.png" ;;
      *Glance_Mixed_Light*) cp "$f" "$OUT/rc_glance_mixed_light.png" ;;
      *Glance_Mixed_Dark*)  cp "$f" "$OUT/rc_glance_mixed_dark.png" ;;
      *Heading_Title_Light*) cp "$f" "$OUT/rc_heading_title_light.png" ;;
      *Heading_Title_Dark*)  cp "$f" "$OUT/rc_heading_title_dark.png" ;;
      *Markdown_Paragraph_Light*) cp "$f" "$OUT/rc_markdown_paragraph_light.png" ;;
      *Markdown_Paragraph_Dark*)  cp "$f" "$OUT/rc_markdown_paragraph_dark.png" ;;
      *VerticalStack_TwoTiles_Light*) cp "$f" "$OUT/rc_verticalstack_twotiles_light.png" ;;
      *VerticalStack_TwoTiles_Dark*)  cp "$f" "$OUT/rc_verticalstack_twotiles_dark.png" ;;
      *HorizontalStack_TwoButtons_Light*) cp "$f" "$OUT/rc_horizontalstack_twobuttons_light.png" ;;
      *HorizontalStack_TwoButtons_Dark*)  cp "$f" "$OUT/rc_horizontalstack_twobuttons_dark.png" ;;
      *Grid_FourButtons_Light*) cp "$f" "$OUT/rc_grid_fourbuttons_light.png" ;;
      *Grid_FourButtons_Dark*)  cp "$f" "$OUT/rc_grid_fourbuttons_dark.png" ;;
      *Unsupported_Gauge_Light*) cp "$f" "$OUT/rc_unsupported_gauge_light.png" ;;
      *Unsupported_Gauge_Dark*)  cp "$f" "$OUT/rc_unsupported_gauge_dark.png" ;;
    esac
done

# State-variant previews (KitchenLightStatesProvider / lock / cover).
for f in "$SRC"/ee.schimke.ha.previews.CardPreviewsKt.Button_*_PARAM_*.png \
         "$SRC"/ee.schimke.ha.previews.CardPreviewsKt.Tile_*_States_*_PARAM_*.png; do
    [ -f "$f" ] || continue
    name=$(basename "$f")
    short=$(echo "$name" | sed -E 's/^ee\.schimke\.ha\.previews\.CardPreviewsKt\.//; s/ .*_PARAM_/_/; s/\.png$//' | tr 'A-Z' 'a-z')
    cp "$f" "$OUT/rc_${short}.png"
done

echo "Staged $(ls "$OUT"/*.png 2>/dev/null | wc -l) PNGs in $OUT"
