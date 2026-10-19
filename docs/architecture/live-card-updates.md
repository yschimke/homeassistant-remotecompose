# Live card updates

A rendered card is a captured RemoteCompose (`.rc`) document — a static
byte stream. Home Assistant entity values change constantly, so every
card needs a path from "new entity state" to "what the user sees". There
are exactly **two** such paths, and every card must use one of them.

This document is the contract. When you add or change a card converter,
decide which path it takes and follow the rules below — otherwise the
card silently goes stale on live data (the failure mode is invisible in a
screenshot test, which renders a single frozen snapshot).

## The two update paths

### 1. Named bindings — the default, preferred path

The converter bakes **host-reproducible** named bindings into the
document via `LiveValues`, and a host pushes new values by name into the
running player with no re-encode. The player swaps the value in place;
the document bytes never move.

The contract — names baked by `LiveValues` (rc-components), values
derived by `HaLiveBindings` / `cardSnapshotBindings` (the host):

| Binding | Type | Host value |
| --- | --- | --- |
| `<id>.state` | string | `formatState(entity)` — the **formatted** display string |
| `<id>.is_on` | bool | `HaLiveBindings.isOn(id, state)`: the typed `HaEntity.isActive` (climate `heat`/`cool`/…, cover `open`/`opening`/`closing`, lock `locked`, media `playing`, …), else `state == "on"` or `"open"`. Converters seed `LiveValues.isOn` from the same function. |
| `<id>.is_available` | bool | `HaLiveBindings.isAvailable(state)`: false for `unavailable` / `unknown`. Entity rows, tiles, glance cells and buttons read it to dim the entity and swap its accent for a neutral one, so an entity going offline or coming back restyles without a re-encode. Converters seed it through `HaStateColor.toggleAccentFor` (false too for a configured entity missing from the snapshot). |
| `<id>.numeric_state` | float | parsed numeric state (gauges/arcs derive their sweep in-document) |
| `<id>.state_int` | int | domain-keyed variant (alarm panel today) |
| `<id>.current_temperature`, `<id>.target_temperature` | float | `HaLiveBindings.attributeFloats`: a climate entity's `current_temperature` and `temperature` attributes (its state is the mode, so it has no `numeric_state`). A missing attribute — a thermostat switched off has no set point — is pushed as `HaLiveBindings.ABSENT` (−9999, not NaN), so the document clears it instead of keeping a stale value. The `ha-cards/thermostat` builder dial computes its fractions and labels from them in-document |

The golden rule: **a binding's value must be reproducible by the host
from the entity state alone, identically to what the converter bakes.**
`formatState` is the canonical formatter and the host runs it, so bake
`LiveValues.state(id, formatState(entity))` — never a bespoke per-card
string under `<id>.state`, or the host's `formatState` push will clobber
it. Format *in the document* (RemoteCompose ops on `numeric_state`) or
push a value the host can reproduce. If you can't, use path 2.

Hosts: in-process (`CachedCardPreview.pushSnapshotBindings`) and the
add-on stream (`addon-server` `StreamRoute`). Both read `HaLiveBindings`
so they stay in lockstep.

### 2. Document re-encode — for baked content

Some content can't be a host-reproducible binding: a forecast strip, a
history sparkline, a to-do / calendar / logbook list, an arc fill
fraction, a per-card-formatted label. For these, the converter overrides
`CardConverter.dataSignature` to return a fingerprint of the
snapshot-derived content it bakes. The dashboard folds that signature
into the card's render cache key, so the document **re-encodes** whenever
the content changes — and skips the named-binding push for that card (so
the fresh formatted bake isn't clobbered by a raw value).

```kotlin
override fun dataSignature(card: CardConfig, snapshot: HaSnapshot): String =
  cardDataSignature(cardEntityIds(card), snapshot)
```

Hosts read the signature through `CardRegistry.cardDataSignature`, which asks the
registry-aware `dataSignature(card, snapshot, registry)`: a container (vertical / horizontal stack,
grid) folds in its children's signatures (`childrenDataSignature`, null only when every child is),
and a wrapper (conditional, entity filter) answers with its shown card's. A re-encode card nested
in a grid therefore re-encodes the grid's document, while a grid of binding cards keeps the
binding push. Never call `converter.dataSignature(card, snapshot)` directly from a host.

Use a custom signature when the entity id isn't in a standard `entity:` /
`entities:` field (`cardEntityIds` won't find it — e.g. Bambu's
`printer:`), or to *exclude* a volatile attribute that would re-encode
every frame (e.g. `media_position` ticks every second — exclude it, the
progress bar is baked).

Re-encoding is more expensive than a binding push and doesn't animate, so
prefer path 1. Reach for path 2 only when the content genuinely can't be
expressed as a binding.

### Relative times

`formatState` shows a timestamp (`device_class: timestamp`, and `event` / `button` /
`input_button` / `scene` entities) as a relative time — `3 h ago`, `in 5 min` — and as a date
once it is a week or more away. That label moves with the clock, not the entity, so it is baked
by the host rather than computed in-document, and refreshed on a tick:

- Binding cards: a dashboard provides `LocalRelativeTimeTick` (`rememberRelativeTimeTick()`, every
  20 s); `CachedCardPreview` re-runs its push for cards whose entities `isRelativeTimeState`.
  Pushes are deduplicated, so a tick that rolls no label over writes nothing.
- Re-encode cards: `cardDataSignature` folds the current label in, and the dashboard re-reads
  the signature on the same tick.
- Previews, tests and hosts without a tick never refresh it on their own (the default tick never
  advances): their label is as of the last snapshot. Fixtures with fixed past timestamps render
  as dates, so screenshots stay stable.

## Checklist for a new / changed converter

1. Does every dynamic value the card shows update on live data? Walk each
   `Remote*` value and ask "how does this change when HA changes?"
2. For each: is it a **host-reproducible** binding (`state`, `is_on`,
   `numeric_state`, `state_int`)? Bake it with `LiveValues`, using
   `formatState(entity)` for `<id>.state`.
3. Anything else — formatted labels, lists, series, fractions, forecast
   strips — means the card is a **re-encode** card: override
   `dataSignature`.
4. Never bake a bespoke string under `<id>.state`. The host pushes
   `formatState` there; a custom string belongs under a different
   `LiveValues.named(id, "…_label", …)` suffix (refreshed by re-encode)
   or computed in-document.
5. New typed bindings (a new suffix, a new domain `state_int` mapping)
   must be taught to **both** hosts: add the derivation to
   `HaLiveBindings`, the push to `cardSnapshotBindings` /
   `pushSnapshotBindings`, and the emit to `StreamRoute`.

## Card matrix (current)

- **Binding (path 1):** sensor, entity, glance, tile (a plain one — a tile that bakes a
  `state_content` line, a climate `hvac_action` badge or `features:` re-encodes, see below),
  area, entities,
  button (its `show_state` line is `<id>.state`), picture-entity (its `entity_picture` URL is the exception: a
  picture is fetched once per document, so `CachedCardPreview` folds
  `cardPictureSignature` into the document key and a rotated camera token
  or new frame plays as a new document), alarm-panel (`state_int`), gauge
  (`numeric_state`; the severity band is picked in-document), markdown (`.state` tokens + Jinja `templateValues`).
  Every typed measurement (thermometer, battery, arc / bar meters) is path 1 by construction:
  `HaReading` binds `<id>.state` and `<id>.numeric_state`, and the fill fraction and band colour
  are computed in-document ([card-component-system.md](card-component-system.md)). The
  `ha-cards/thermostat` builder component is path 1 as well, through the climate temperature
  floats; the `thermostat` *converter* still re-encodes (its accent follows `hvac_action`).
- **Re-encode (path 2):** a tile with baked extras (`TileContent.bakes`: the climate /
  cover / humidifier / valve default secondary line such as "Heat · 22.8 °C", a configured
  `state_content`, the `hvac_action` badge, or `features:` whose stepped `set_temperature` payloads
  and selected mode are baked; its line goes under `<id>.state_label`, never `<id>.state`),
  calendar, logbook, to-do, history-graph,
  statistics-graph, statistic, map, weather-forecast, thermostat /
  humidifier / light (arc fill + labels), media-control, Bambu (AMS /
  print status), the shutter cards (`custom:enhanced-shutter-card`,
  `custom:garage-shutter-card`: door height, which buttons are enabled,
  and the state line are baked from the cover). Markdown also re-encodes on Jinja template changes via
  `templateValues`.
- **Static / self-driven:** heading, clock (RemoteCompose time ops),
  unsupported.

## Entity icons

Cards draw Home Assistant's own icons, in Material Design Icons (`HaIconMap`, generated by
`scripts/generate-mdi-icons.py`). Many of them follow the entity's state, and a binding card's
document is captured once — often from a card design captured in another state — so each kind of
state-following glyph has its path:

- **On/off pairs** (a light's `lightbulb-off` / `lightbulb`, a door sensor's `door-closed` /
  `door-open`): path 1. `HaIconMap.entityIcons` gives both glyphs and the component bakes both,
  each tinted transparent outside its own state off `<id>.is_on` (`HaToggleAccent.activeIcon`).
  A component without an `is_on` binding draws `HaIconMap.resolve`, which for these entities is
  the pair's non-negated glyph whatever the state. `is_on` is false while the entity is
  unavailable or unknown too, where HA draws the rule's default — the on glyph for a light or a
  fan — so those pairs also show their on glyph while `<id>.is_available` is false. Widgets
  (Fixed mode) carry no availability binding, so there the glyph is chosen at capture.
- **Other state glyphs** (sun, alarm panel, weather, lock, cover, update, person, …): the
  converter bakes the current state's glyph and `cardIconSignature` changes with it. The app's
  dashboard folds that into the card's cache key, so the card re-encodes when the glyph changes
  while values keep arriving as bindings. Other hosts (TV kiosk, Wear, add-on stream, launcher
  widgets) don't fold it in yet, so there these glyphs refresh only on their next re-encode.
- **Numeric ranges** (a battery's level): `resolve` draws the level-independent glyph (`battery`);
  `HaIconMap.resolveForState` gives the level's glyph for content re-encoded with it.

Card designs never see a state-following glyph in their properties: `valuesNeverChangeTheProperties`
holds.

## Known follow-ups

- History sparkline points (`<id>.numeric.<index>`) are baked at capture
  and refreshed by re-encode; a windowed live push would need history in
  the binding contract.
