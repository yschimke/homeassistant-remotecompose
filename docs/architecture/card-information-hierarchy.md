# Card information hierarchy

What a card keeps and what it drops as its space shrinks, from the phone dashboard down to a 1×1
launcher widget and the Wear Small container. [adaptive-card-layouts.md](adaptive-card-layouts.md)
has the ladder mechanics (tiers, gates, the P1–P5 data priorities); this page is the order a
converter drops parts in, and the one exception to it.

## The priority order

A card's parts, most important first. A tier drops from the bottom of this list, and reflows
(column to row, smaller type, tighter padding) before it drops anything.

| # | Part | Examples | Drops |
|---|---|---|---|
| 1 | **State / value** | `21.5 °C`, `100%`, `Locked`, the accent colour | never; shrink its type instead, and never ellipsise it |
| 2 | **Icon** (or the card's visual) | the bulb, the dial, the thermometer | never; a smaller form (the icon chip) replaces a bigger one |
| 3 | **Name** | `Kitchen`, `Front door` | only at a 1×1 launcher widget, and only when the icon is evocative (below) |
| 4 | **Secondary text** | the target `↑ 21.5 °C`, a mode chip that repeats the value, a range, a unit caption | below a Wear Large container or a 2×1 launcher widget |
| 5 | **Controls** | steppers, feature rows, an inline switch | anywhere smaller than the card's full form; a tap on the card still acts |

The name ellipsises before the value does. A state the value already says (a light's `On` beside
`100%`) is secondary text, not state, so it is the first text to give its line to the name.

**Multi-entity cards** (glance, entity-filter, entities, area) apply the order per entity, plus
two parts of their own: the **card title** ranks below the entities' icons and states, and
**per-entity names** below the title. In a Wear Small container they keep a row of icon-over-state
cells and drop both; a title shows only where it fits without displacing a cell.

## The icon exception

The name exists so two cards can be told apart. An icon that already does that may carry the
identity alone, at the one size where the name has to compete with the value for the only line.

- **Evocative**, so the name may drop at 1×1: a domain with one obvious instance per room or home,
  drawn with a distinctive pictogram: `lock`, `cover` with a `garage` / `gate` / `door` device
  class, `climate`, `alarm_control_panel`, `vacuum`, `media_player`, `camera`, `water_heater`. Also
  any card with a configured `icon:` — someone picked it to mean this entity.
- **Generic**, so the name stays: a pictogram repeated across many entities of a home: `light`,
  `switch`, `input_boolean`, `fan`, `sensor` / `binary_sensor` (a thermometer is the same on every
  room's sensor), `script`, `scene`, `button`.

## How a converter decides

1. List the card's parts in the order above.
2. Take the tier's budget: a Wear Small container holds one line beside an icon (or two short
   lines beside a dial), a 1×1 launcher widget an icon over one or two short lines.
3. Reflow first. Only when no arrangement fits, drop from the bottom of the list.
4. Before dropping the name, check the icon. Drop it only for an evocative icon; for a generic one,
   set the name a type step smaller, ellipsised, and give up secondary text instead.
5. Keep every kept value on its binding (`LiveValues`, [live-card-updates.md](live-card-updates.md)):
   dropping a part never turns a live value into a baked one.

## Worked examples

**Phone dashboard (Wrap mode): the light card.** Room for everything: the name over the dial, the
bulb, `On` and the brightness in it, the steppers below. Nothing drops.

**Launcher widget: the button card** (its state line only with `show_state`). At 2×2 and up, the
icon hero: icon, name and state, set large. At 2×1, a row: icon, then name, then state. At 1×1,
the icon chip. For `lock.front_door` the padlock is evocative and the name drops. For
`light.kitchen` the bulb is generic, so the name stays under a slightly smaller chip at label size,
ellipsised. The same rule holds for the icon chip that tile, entity, sensor and statistic share at
1×1: icon, then the name a step below the state, then the state (`Living Roo…` over `21.4 °C`); a
temperature sensor's thermometer is generic too, so it shrinks to fit the name. One helper,
`HaIconIdentity` in `rc-converter`, decides evocative or generic from the domain, the device class
and a configured `icon:`; every 1×1 chip asks it. Inside a stack or grid the cells are the
multi-entity card's, and drop the per-entity name whatever the icon (`LocalInCardContainer`).

**Wear Small (200 × 60 dp, about 44 dp of content).** A single-entity card keeps its name.
`button` · `light.kitchen`: the tinted bulb chip, then `Kitchen`, then the state with `show_state`,
on one line. `light`: the dial, then `Kitchen` over `100%`; the `On` chip drops (the value says
it). `thermostat` and `humidifier`: the dial, then `Living Room` over `20.5°C`; the mode chip
(`Heating`) is secondary text and gives its line to the name, while the arc's accent still shows
the mode. `glance` over four entities: four icon-over-state cells, no names, no title.

**Wear Large (200 × 112 dp).** The name, a larger visual and the value at value size; secondary
text where it fits (a thermostat's target), controls never. `light`: the 72 dp dial beside
`Kitchen` over `100%`. `thermostat`: the dial beside `Living Room`, `Heating`, `20.5°C` and the
target `↑ 21.5°C`; the mode chip fits here, and it says what the accent cannot (`Idle` in heat mode
is still orange; a humidifier's fixed accent never says `Off`), so it stays. `glance`: the cells
with their names; the title drops.

**Launcher widget: the arc-dial cards** (light, thermostat, humidifier). At `2×2` and `3×3` the
dial-filled tier: the name over a dial that takes the rest of the cell, the value (and a
thermostat's mode and target, a light's bulb and `On`) inside it; no steppers, a tap on the card
still acts. From about 260 × 290 dp (`4×4`) the full card with its steppers. Smaller than two
columns by two rows, the Wide row beside the dial, which names the entity in place of the mode chip
from two columns up. A captured widget whose dial is under 120 dp sets the centre a step smaller
and drops the light's `On` and the target first. A captured widget one column wide (`1×1`, 57 dp)
has no room beside the dial: the dial over the value at label size, over the name under a generic
icon (`light`, `humidifier`: `78%` over `Kitchen`); a `thermostat`'s climate dial is evocative, so
it keeps the value alone.

**Launcher widget: the value stays whole.** A one-column gauge sets its value at label size over the
name and drops the range, instead of `21.4 …`. A `2×1` statistic keeps its period by trimming the
card's padding. A `show_state` button too short for the state under the name (`1×1`, `2×1`) takes
a dense body, a smaller chip over the name and the state, both whole. An alarm panel narrower than
its full card but tall enough (`3×3`, `3×4`) keeps the ARM buttons, and the keypad where it fits;
a full-width one too short for the keypad (`4×1`, `4×2`) keeps the buttons without it; a
captured one two columns wide (`2×2`, `2×3`) stacks them, one a row.

**Wear: one line per container.** The weather card stacks the condition over the temperature
beside its icon (the Large container adds the entity's name), rather than three columns that cut
the condition to `Par…`. The Bambu print status puts the printer's name over the percentage beside
a small ring in the Small container, and the name over a bigger ring beside the percentage and the
stage in the Large one. A to-do list in the Large container lists its first two items under the
title, without the section captions.

