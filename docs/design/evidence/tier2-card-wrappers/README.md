# Tier-2 plain-data card wrappers

Every new `rc-components-ui` wrapper drawn through its public plain-Compose API
(`HaGauge(HaGaugeUiData(...))`, ...), from `previews/.../Tier2CardPreviews.kt`, rendered with
`compose-preview render --module previews --filter Tier2_` (light theme). Each tile is one preview's
PNG, labelled with its function name minus the `Tier2_` prefix, downscaled into two 1800px-tall
contact sheets.

- `tier2-cards-1.png`: alarm panel (+wide), area, Bambu AMS / print control / print status / spool,
  calendar, clock, gauge (+stacked, +wide), history graph, humidifier (arc dial, wide).
- `tier2-cards-2.png`: logbook, media control (+wide), picture elements, picture entity, picture
  glance, picture, sensor, statistic, statistics graph (stacked bar), thermostat (arc dial),
  todo list, weather forecast (+wide).

There is no "before": these previews and wrappers are new. Tier-1 cards and their previews are
untouched.
