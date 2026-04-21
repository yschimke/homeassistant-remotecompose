# `entity` card: redesign to match HA's multi-row layout

The `entity` Lovelace card isn't a single row. HA renders it as a
small card with:

```
┌──────────────────────────┐
│ Living Room          🌡️  │   ← name top-left, icon top-right
│                          │
│ 21.4 °C                  │   ← big state value below, unit small
└──────────────────────────┘
```

Our `RemoteHaEntityRow` is a single horizontal row: `[icon] name
.......... state`. That's the right primitive for rows inside an
`entities` card, but wrong for a standalone `entity` card.

## Fix

Introduce a new typed composable `RemoteHaEntitySummary`:

```kotlin
@Composable
fun RemoteHaEntitySummary(data: HaEntitySummaryData, modifier: RemoteModifier = RemoteModifier) {
    // Column: [name row with icon aligned end]
    //         [state big] [unit small]
}
```

Data model: reuse `HaEntityRowData` (has `name`, `state`, `icon`,
`accent`, `tapAction`) — same fields, different layout.

`EntityCardConverter.Render` switches from `RemoteHaEntityRow` to
`RemoteHaEntitySummary`. `EntitiesCardConverter` / the `entity` row
inside `entities` keeps using `RemoteHaEntityRow`.

## References

- HA source: `home-assistant/frontend`
  `src/panels/lovelace/cards/hui-entity-card.ts`
- HA-rendered reference:
  `references/entity/temperature_sensor_light.png`
