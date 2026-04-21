#!/usr/bin/env bash
#
# Generate /tmp/ha-rc-gist/README.md referencing the currently-staged
# PNGs. Call this after scripts/stage-gist.sh.

set -euo pipefail
OUT=/tmp/ha-rc-gist

cat > "$OUT/README.md" <<'EOF'
# homeassistant-remotecompose — card previews

Side-by-side comparison of rendered RemoteCompose output against HA
reference screenshots (both themes, every implemented card type). See
[yschimke/homeassistant-remotecompose](https://github.com/yschimke/homeassistant-remotecompose)
for the library source.

## End-to-end dashboard

A realistic vertical-stack — heading + tiles + entities (with toggles)
+ glance — all in one `.rc` document, side-by-side with the HA capture
of the same YAML config.

| | Home Assistant | RemoteCompose |
|---|---|---|
| Home (light) | ![](ref_dashboard_home_light.png) | ![](rc_dashboard_home_light.png) |
| Home (dark) | ![](ref_dashboard_home_dark.png) | ![](rc_dashboard_home_dark.png) |

## tile

### Light theme

| variant | Home Assistant | RemoteCompose |
|---|---|---|
| temperature sensor | ![](ref_tile_temperature_sensor_light.png) | ![](rc_tile_temperature_sensor_light.png) |
| light on | ![](ref_tile_light_on_light.png) | ![](rc_tile_light_on_light.png) |
| light off | ![](ref_tile_light_off_light.png) | ![](rc_tile_light_states_off.png) |
| lock locked | ![](ref_tile_lock_locked_light.png) | ![](rc_tile_lock_states_locked.png) |
| cover | ![](ref_tile_cover_light.png) | ![](rc_tile_cover_states_closed.png) |

### Dark theme

| variant | Home Assistant | RemoteCompose |
|---|---|---|
| temperature sensor | ![](ref_tile_temperature_sensor_dark.png) | ![](rc_tile_temperature_sensor_dark.png) |
| light on | ![](ref_tile_light_on_dark.png) | ![](rc_tile_light_on_dark.png) |

## button

### Light theme

| variant | Home Assistant | RemoteCompose |
|---|---|---|
| light on | ![](ref_button_light_on_light.png) | ![](rc_button_states_on_light.png) |
| light off | ![](ref_button_light_off_light.png) | ![](rc_button_states_off_light.png) |

### Dark theme

| variant | Home Assistant | RemoteCompose |
|---|---|---|
| light on | ![](ref_button_light_on_dark.png) | ![](rc_button_states_on_dark.png) |
| light off | ![](ref_button_light_off_dark.png) | ![](rc_button_states_off_dark.png) |

## entity

| | Home Assistant | RemoteCompose |
|---|---|---|
| temperature (light) | ![](ref_entity_temperature_sensor_light.png) | ![](rc_entity_temperature_sensor_light.png) |
| temperature (dark) | ![](ref_entity_temperature_sensor_dark.png) | ![](rc_entity_temperature_sensor_dark.png) |

## entities

| | Home Assistant | RemoteCompose |
|---|---|---|
| living room (light) | ![](ref_entities_living_room_light.png) | ![](rc_entities_living_room_light.png) |
| living room (dark) | ![](ref_entities_living_room_dark.png) | ![](rc_entities_living_room_dark.png) |

## glance

| | Home Assistant | RemoteCompose |
|---|---|---|
| overview (light) | ![](ref_glance_overview_light.png) | ![](rc_glance_overview_light.png) |
| overview (dark) | ![](ref_glance_overview_dark.png) | ![](rc_glance_overview_dark.png) |

## markdown

| | Home Assistant | RemoteCompose |
|---|---|---|
| notes (light) | ![](ref_markdown_notes_light.png) | ![](rc_markdown_notes_light.png) |
| notes (dark) | ![](ref_markdown_notes_dark.png) | ![](rc_markdown_notes_dark.png) |

## State variants (single document, binding-driven)

Each tile emits one `.rc` document whose accent + chip flip based on a
named `RemoteBoolean` that the player updates live — same bytes, three
rendered states.

### Light entity

| on | off | unavailable |
|---|---|---|
| ![](rc_tile_light_states_on.png) | ![](rc_tile_light_states_off.png) | ![](rc_tile_light_states_unavailable.png) |

### Cover entity

| closed | open | opening |
|---|---|---|
| ![](rc_tile_cover_states_closed.png) | ![](rc_tile_cover_states_open.png) | ![](rc_tile_cover_states_opening.png) |

### Lock entity

| locked | unlocked | locking |
|---|---|---|
| ![](rc_tile_lock_states_locked.png) | ![](rc_tile_lock_states_unlocked.png) | ![](rc_tile_lock_states_locking.png) |

## Layout-only cards (no HA reference — wrapper visual)

| Card | Light | Dark |
|---|---|---|
| `vertical-stack` | ![](rc_verticalstack_light.png) | ![](rc_verticalstack_dark.png) |
| `horizontal-stack` | ![](rc_horizontalstack_light.png) | ![](rc_horizontalstack_dark.png) |
| `grid` | ![](rc_grid_light.png) | ![](rc_grid_dark.png) |
| `heading` | ![](rc_heading_title_light.png) | ![](rc_heading_title_dark.png) |
| `gauge` (unsupported placeholder) | ![](rc_unsupported_light.png) | ![](rc_unsupported_dark.png) |

## Known gaps

- **Chip shape.** Tile icon chip renders as a rounded rectangle
  instead of a full circle despite `.clip(RemoteCircleShape)` — looks
  like an alpha08 player clip bug, not a composable bug.
- **Icons differ slightly in style.** Compose Material Symbols vs
  HA's MDI — same meaning, different geometry. Accepted.
- **ColorTheme.** Today we render one document per theme. When the
  creation-side DSL for
  `androidx.compose.remote.core.operations.ColorTheme` is public we
  collapse to a single document that switches palettes at playback.

## Reproducing

```sh
git clone https://github.com/yschimke/homeassistant-remotecompose
cd homeassistant-remotecompose

scripts/render-previews.sh      # our PNGs
scripts/stage-gist.sh           # stage side-by-side under /tmp/ha-rc-gist
scripts/publish-gist.sh         # push to the gist
```
EOF
echo "wrote $OUT/README.md"
