#!/usr/bin/env bash
#
# Generate /tmp/ha-rc-gist/README.md referencing the currently-staged
# PNGs. Call this after scripts/stage-gist.sh.

set -euo pipefail
OUT=/tmp/ha-rc-gist

cat > "$OUT/README.md" <<'EOF'
# homeassistant-remotecompose — card previews

Side-by-side comparison of the rendered RemoteCompose output against
reference screenshots captured from a real Home Assistant frontend
(light + dark themes). See
[yschimke/homeassistant-remotecompose](https://github.com/yschimke/homeassistant-remotecompose)
for the library source.

## Tile — side by side

### Light theme

| | Home Assistant | RemoteCompose |
|---|---|---|
| **tile** — temperature sensor | ![](ref_tile_temperaturesensor_light.png) | ![](rc_tile_temperaturesensor_light.png) |
| **tile** — light on | ![](ref_tile_lighton_light.png) | ![](rc_tile_lighton_light.png) |

### Dark theme

| | Home Assistant | RemoteCompose |
|---|---|---|
| **tile** — temperature sensor | ![](ref_tile_temperaturesensor_dark.png) | ![](rc_tile_temperaturesensor_dark.png) |
| **tile** — light on | ![](ref_tile_lighton_dark.png) | ![](rc_tile_lighton_dark.png) |

## State variants (single document, binding-driven)

Each tile emits one `.rc` document whose accent + chip flip based on a
named `RemoteBoolean` that the player updates live. These previews
illustrate what the same document looks like at different states.

### Light entity

| on | off | unavailable |
|---|---|---|
| ![](rc_tile_light_on.png) | ![](rc_tile_light_off.png) | ![](rc_tile_light_unavailable.png) |

### Cover entity

| closed | open | opening |
|---|---|---|
| ![](rc_tile_cover_closed.png) | ![](rc_tile_cover_open.png) | ![](rc_tile_cover_opening.png) |

### Lock entity

| locked | unlocked | locking |
|---|---|---|
| ![](rc_tile_lock_locked.png) | ![](rc_tile_lock_unlocked.png) | ![](rc_tile_lock_locking.png) |

### Button

| on | off | unavailable |
|---|---|---|
| ![](rc_button_light_on.png) | ![](rc_button_light_off.png) | ![](rc_button_light_unavailable.png) |

## Other card types (RemoteCompose output only)

Reference capture for these is still TODO (extending the
`ui-lovelace.yaml` in the integration harness).

### Light theme

| Card | RemoteCompose |
|---|---|
| `entity` | ![](rc_entity_light.png) |
| `entities` | ![](rc_entities_light.png) |
| `glance` | ![](rc_glance_light.png) |
| `heading` | ![](rc_heading_light.png) |
| `markdown` | ![](rc_markdown_light.png) |
| `vertical-stack` | ![](rc_verticalstack_light.png) |
| `horizontal-stack` | ![](rc_horizontalstack_light.png) |
| `grid` | ![](rc_grid_light.png) |
| `gauge` (unsupported placeholder) | ![](rc_unsupported_light.png) |

### Dark theme

| Card | RemoteCompose |
|---|---|
| `entity` | ![](rc_entity_dark.png) |
| `entities` | ![](rc_entities_dark.png) |
| `glance` | ![](rc_glance_dark.png) |
| `heading` | ![](rc_heading_dark.png) |
| `markdown` | ![](rc_markdown_dark.png) |
| `vertical-stack` | ![](rc_verticalstack_dark.png) |
| `horizontal-stack` | ![](rc_horizontalstack_dark.png) |
| `grid` | ![](rc_grid_dark.png) |
| `gauge` (unsupported placeholder) | ![](rc_unsupported_dark.png) |

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
