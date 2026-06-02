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
| `<id>.is_on` | bool | `state == "on"` |
| `<id>.numeric_state` | float | parsed numeric state (gauges/arcs derive their sweep in-document) |
| `<id>.state_int` | int | domain-keyed variant (alarm panel today) |

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

Use a custom signature when the entity id isn't in a standard `entity:` /
`entities:` field (`cardEntityIds` won't find it — e.g. Bambu's
`printer:`), or to *exclude* a volatile attribute that would re-encode
every frame (e.g. `media_position` ticks every second — exclude it, the
progress bar is baked).

Re-encoding is more expensive than a binding push and doesn't animate, so
prefer path 1. Reach for path 2 only when the content genuinely can't be
expressed as a binding.

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

- **Binding (path 1):** sensor, entity, glance, tile, area, entities,
  button, picture-entity, alarm-panel (`state_int`), gauge
  (`numeric_state`), markdown (`.state` tokens + Jinja `templateValues`).
- **Re-encode (path 2):** calendar, logbook, to-do, history-graph,
  statistics-graph, statistic, map, weather-forecast, thermostat /
  humidifier / light (arc fill + labels), media-control, Bambu (AMS /
  print status). Markdown also re-encodes on Jinja template changes via
  `templateValues`.
- **Static / self-driven:** heading, clock (RemoteCompose time ops),
  unsupported.

## Known follow-ups

- The add-on `StreamRoute` still pushes the **raw** `<id>.state`, while
  the in-process host pushes `formatState(entity)`. The documents bake
  the formatted string, so add-on clients should run the same formatter
  before applying. `formatState` lives in the Android `rc-converter`
  module; sharing it with the JVM add-on (a common, multiplatform-safe
  formatter in `ha-model`) is the proper fix.
- History sparkline points (`<id>.numeric.<index>`) are baked at capture
  and refreshed by re-encode; a windowed live push would need history in
  the binding contract.
