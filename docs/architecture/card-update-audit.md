# Card update audit

Every field a card re-encodes for, and why no slot carries it yet
([design principle 10](../design/design-principles.md#10-named-slots-not-baked-values),
[live-card-updates.md](live-card-updates.md)). Named slots are the default. A field stays here
only while it is genuine **structure**, or until the binding it needs exists. When a card moves to
path 1, its row goes.

Classes:

- **Bindable now**: existing machinery covers it (`formatState`, `is_on`, `is_available`,
  `numeric_state`, `state_int`, attribute floats, in-document math, a `CardBindingRecipe`).
- **New binding**: it needs a host-reproducible binding kind that doesn't exist yet. The entry
  says which.
- **Structure**: it changes the document's shape (row count, a series, a list from an attribute).
  Re-encode is justified.

## Two cross-cutting gaps

1. **A signature switches the push off.** Every designed converter that re-encodes already
   splits its data: `designProperties` holds the structure and `designBindings` the values, and
   its `Render` writes those values under names (`cardText("row.$i.when")`, `$slot.*`). On the
   template path nothing re-encodes. On the direct converter path (no template cached yet,
   widgets, TV, Wear, previews), a `dataSignature` both keys the cache and turns the binding push
   off (`CachedCardPreview`, `liveBindings`). **Fix once, for about 15 cards:**
   - key the direct path on the design shape instead of the signature, through a structure-signature
     lane like `iconSignature` and `visibilitySignature` that re-keys without disabling the push;
   - push `designBindings` there too, remapped from slot ids to real ids.
2. **The push set comes from config.** `CachedCardPreview` pushes for `entityIds(card)` only.
   Bambu discovers its entities from a `printer:` device, so none of them can be bound until a
   converter hook supplies the live entity ids (`liveEntityIds(card, snapshot)`).

## Per converter

`cards/` is `rc-converter/src/commonMain/kotlin/ee/schimke/ha/rc/cards/`.

| Converter | Field | Class | What it needs, or why it re-encodes |
| --- | --- | --- | --- |
| Alarm panel | status hero and variants | bindable now | already switched by `state_int`. |
| | ARM row vs DISARM row, PIN length | structure | already design properties. The signature should narrow to those; today any attribute change re-encodes. |
| Bambu (`BambuLabCardConverter`) | progress, layer, temperatures, remaining time, control enablement | bindable now* | `numeric_state` math in-document. *Once gap 2 is fixed. |
| | stage label | new binding | a Bambu `state_int` mapping. |
| | tray remaining % and colour | new binding | an attribute float, and a colour parsed from a hex attribute. |
| | which trays and lines exist | structure | device discovery. |
| Calendar | event rows | structure | a variable list from the `events` attribute. |
| To-do list | item rows; item summary inside `update_item` taps | structure | a variable list. `designAction` covers the template path. |
| Logbook | message | bindable now | `<id>.state`. Rows are a fixed count, one per configured entity. |
| | "when" | new binding | a host-pushed `<id>.last_changed_label` on the relative-time tick. **The whole card can then be path 1.** |
| Map | "where" | new binding | latitude and longitude floats, or a host `location_label`. Whether a row has a location is shape. |
| Weather forecast | current temperature, humidity, wind | new binding | weather attribute floats. |
| | condition text | bindable now | check that `formatState` matches `formatCondition`. |
| | days and their highs and lows | structure | `snapshot.forecasts`, not entity state. |
| Sensor | value | bindable now | `HaReading`. |
| | sparkline | structure (direct path) | a history series. The signature should cover history only; today it includes the state, so every tick re-encodes. |
| History graph, statistics graph | series, buckets | structure | history and statistics are not entity state. |
| Statistic | aggregate value | new binding | a recipe over `snapshot.statistics` and `stat_type`. |
| Thermostat | fraction and labels | bindable now | climate `current_temperature` / `target_temperature`. |
| | accent, mode chip | new binding | a climate `hvac_action` / mode `state_int`. |
| Humidifier | fraction and labels | new binding | `current_humidity` / `humidity` floats. |
| Light | fraction and label | new binding | a `brightness` float. Steppers can use a relative `brightness_step`, so their payload is fixed. |
| Thermostat, humidifier steppers | payloads | new binding | absolute `SetValue` computed from a bound float (the slider machinery is close). |
| Media control | playing | bindable now | `is_on`. |
| | title, artist | new binding | a generic attribute-string binding. |
| | progress | new binding | `media_position`, `media_duration` and `updated_at` floats with in-document clock math. |
| Tile (`TileContent.bakes`) | `state_content` line, hvac badge, feature payloads and resting values | new binding | `<id>.state_label` from `TileContent.secondaryText` moved to ha-model; the climate `state_int`; value floats. Mode lists are structure. |
| Entities (input rows) | input value and options | structure | `RemoteHaInput` takes a plain string; the options are structure. |
| Message | native input values, choices | structure | — |
| Entity filter | membership | structure | the signature also folds in the shown card's entity data, so a filtered glance re-encodes on every value. It should key on membership only. |
| Stacks, grid | — | inherited | they re-encode with their children and disable the push for binding children of a mixed container (gap 1). |

**Path 1, with design properties:** area, button, clock, entities (no input rows), entity, gauge,
glance, heading, markdown, picture, picture-elements, picture-entity, picture-glance, tile (a plain
one), and both shutter cards. The shutter cards moved in this change: `HaLiveBindings.coverStateInt`
plus the `current_position` / `supported_features` floats, a `cover` binding recipe, and
everything else in-document (`CoverLive`). Only `assumed_state` is baked, as shape.

## Order of work

1. Gap 1: the structure-signature lane, and `designBindings` pushed on direct captures.
2. Logbook to path 1, with `last_changed_label`.
3. Alarm panel and sensor: narrow their signatures and keep the push on.
4. Entity filter: key on membership only.
5. A climate `hvac_action` / mode `state_int`, for the thermostat accent and chip, the tile badge,
   and suspect 1 below.
6. Light: a `brightness` float and relative steppers.
7. Bambu: gap 2, then numeric bindings.
8. Media: an attribute-string binding and progress clock math.

## Suspected stale values (to verify)

Found reading the code; none is confirmed by a test yet.

1. **Climate accent.** `HaStateColor.activeFor` returns `climateColor(mode)`, and the entity,
   glance, entities, button, picture-glance and picture-elements cards bake it without a
   signature. `is_on` stays true from heat to cool, so the accent may not follow the mode.
2. **Clock date line.** On the direct path the date is baked (`ClockCardConverter`) with no
   signature or tick, so it may be wrong after midnight, or after a DST change.
3. **Area's unitless stat** bakes `formatValueWithUnit(state, "°C")` under `<id>.state`, which the
   host's `formatState` push overwrites (the golden rule in live-card-updates.md).
4. **Weather `designBindings`** writes `formatCondition` under `$slot.state`, which may disagree
   with `formatState`.
5. **Media progress** is deliberately left out of the signature, so it doesn't move while playing.
