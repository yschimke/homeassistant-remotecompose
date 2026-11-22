# Device packs

> Status: **first two packs drafted** (garage door opener, thermostat) as UI Builder documents in
> `card-designs/ui-builder/packs/`. Nothing plays them yet: see [What isn't wired](#what-isnt-wired).

A **device pack** is a set of designs for one kind of device, one per *kind* of placement. They are
not reskins of one layout. Each kind answers a different question: an alert shows only when
something is wrong, a toolbar item is one glyph and a word, and a real-world view starts from a
picture of the actual device. A card style (Braun, LCD, Console) still re-themes any of them, and
the [card picker](../architecture/lovelace-card-picker.md) offers a pack's designs for an entity
whose device it fits.

## Kinds

| Kind | Variants | Surface | Shows | What it is for |
| --- | --- | --- | --- | --- |
| **Device controls** | `material3`, `skeuomorphic`, `overlay` | card; `spatial` for the overlay | always | Everything you do to the device. `material3` is the plain Material 3 card. `skeuomorphic` takes its look and controls from the real device (a garage opener's wall push button, a round thermostat dial). `overlay` is for AR and XR: pinned beside the real thing, large type and high contrast, state first. |
| **Alert** | `default` | card, notification | only while a pack's alert rule holds | What is wrong, how long it has been wrong, the one action that fixes it and a link for help. Hidden the rest of the time. |
| **Toolbar item** | `default` | toolbar, status bar, app bar | always | A glyph and a short state, with at most one action. |
| **Real world** | `default` | card | always | The physical item: a camera feed, the device's own picture, or a simulation, with its state over it. |

More kinds worth adding, each with a different job, not another look:

| Kind | What it is for |
| --- | --- |
| **Glance** (ambient) | Wall panels, always-on and e-paper screens: no controls, large, readable across a room, low refresh. |
| **Wrist** | Wear tile and complication: one value and one action within a watch's limits. |
| **Live activity** | An ongoing notification or lock-screen live activity while something is in progress (the door opening, the house warming up), ending by itself. |
| **History and insight** | How it has been used: cycles today, heating hours, energy, compared with last week. |
| **Schedule** | When it acts: thermostat programmes, the door's auto-close times. Edits automations, not state. |
| **Setup and maintenance** | Calibration, filter and battery replacement, firmware, with the device's own manual. |
| **Guest and kids** | Restricted controls: open but not unlock, comfort range only, with a confirmation. |
| **Accessible** | Large targets and high contrast, everything readable aloud, no gesture-only control. |
| **Room summary** | Several devices of a room in one card, each as its toolbar item. |
| **Voice** | Not drawn: what an assistant says and asks for this device, kept next to its designs. |

## Files

```
card-designs/ui-builder/packs/
  index.json                 every pack: id, title, the entities it fits, its manifest
  <pack>/pack.json           the pack: its designs by kind and variant, alert rules, real-world source
  <pack>/<kind>[-<variant>].uid   one UI Builder design (compose-ui-builder-document/v1, ha-cards)
```

`pack.json` (`harc-device-pack/v1`):

- **`audience`**: the facts of the entities the pack fits (`entity.domain`, `entity.device_class`),
  written as [Livery audiences](https://github.com/yschimke/livery/blob/main/docs/PROTOCOL.md)
  will match them.
- **`designs[]`**: `kind`, `variant`, `surface`, `file`, `designId` (the `.uid`'s own `id`) and
  `title`.
  - An alert adds `showWhen`: conditions on the pack entity's bindings, each with a `reason` and
    optionally `forMinutes`. It also adds `help`, a link for more help.
  - A real-world design adds `view`: its `source` (`camera`, `entity_picture`, `simulation`), the
    entity, and a fallback.

Each design is built only from the `ha-cards` catalog's components and the frame's collapsible
rows and columns, as the design server's own seeds are. Live values arrive two ways, both pushed
by the host:
- **Readings** (the door's travel, the room temperature and set point) follow the pack entity
  through each component's `entityId`. A cover's or a climate's state is a word (`open`, `heat`),
  so its readings also name the float `attribute` they follow (`current_position`,
  `current_temperature`, `target_temperature`). The thermostat dial binds both temperatures by
  itself.
- **Text** (`cover.s0.name`, `climate.s0.state`, the alert's `alert.message`) is a `host` state
  variable.
`DevicePacksTest` (`:ha-cards-catalog`) holds every pack to the catalog policy, to the bindings a
host pushes, and to its own manifest. It also checks that a cover or climate reading follows one of
its domain's floats, and that no metric shows such an entity's state.

## The packs

**Garage door opener** (`garage-door/`, a `cover` with `device_class: garage`):

| Design | What it shows |
| --- | --- |
| `controls-material3` | Door icon, name and state, a status chip, the door's travel (`current_position`) as a bar, and Open, Stop and Close. |
| `controls-skeuomorphic` | A wall console in the `rugged` style: one large push button, the travel as an inset arc, a status lamp. |
| `controls-overlay` | Dark, `cockpit` style: the state in large type, the name, and a toggle. |
| `alert` | Left open, stuck moving, or unavailable: what happened, the name, an explanation, **Close now** and **Help**. |
| `toolbar` | Door glyph and state. |
| `real-world` | The garage camera (`camera.garage`, falling back to the entity's picture) with the name, state and a toggle under it. |

**Thermostat** (`thermostat/`, a `climate` entity):

| Design | What it shows |
| --- | --- |
| `controls-material3` | Name and mode chip, the thermostat dial (room temperature and set point), and − / set point / +. |
| `controls-skeuomorphic` | The wall unit in the `braun` style: the round dial, the room reading, the name. |
| `controls-overlay` | Dark, `cockpit` style: the room reading as a glyph, the mode, the target. |
| `alert` | Unavailable, not reaching the set point for two hours, or frost risk: what is wrong, the room and target readings, **Turn off** and **Help**. |
| `toolbar` | The room temperature as a chip. |
| `real-world` | The device's own picture, with its simulation as the fallback, and the name, mode and room reading under it. |

## What isn't wired

These documents are designs to review and edit, ahead of the code that plays them:

- **Not rendered here.** The `ha-cards` catalog renders on the preview server (its policy's
  `native` lane), which this repository's build doesn't run. The in-process `.uid` renderer
  (`CardDesign`) draws card parts only, not the collapsible layouts or Material text these use.
  Baselines come from the server once the files are uploaded; see [Publishing](#publishing).
- **No distinct actions.** A button takes its card's own tap action (`entityId`). Open, Stop and
  Close, − and +, and Help need action support in UI Builder designs, which
  [server-owned-card-designs.md](../architecture/server-owned-card-designs.md) leaves out of scope
  for now.
- **Alerts are not evaluated.** No client reads `showWhen` yet, and `alert.message` and
  `alert.detail` are values the rule would push. A client should hide the alert while no condition
  holds.
- **Skeuomorphic and overlay are limited by the catalog.** It has no drawing primitives, so the
  wall console and the wall unit are a card style (`rugged`, `braun`) and a layout, not a drawing
  of the device. The overlay is a dark card, not a transparent layer over the scene.
- **Real world needs its source.** The picture is the card's entity picture
  (`picture-entity-card-image`). The camera entity and the simulation are in `pack.json`, not yet
  in the card config a host builds.
- **Not selected.** The packs aren't in the design library, so the card picker doesn't offer them.
  Their `audience` is ready for Livery's audience matching when the migration lands.

## Publishing

The files are static. Serve `card-designs/ui-builder/packs/` as it is (for example under
`https://ha.coo.ee/designs/packs/`), starting from `index.json`. To make a baseline for each design:

1. Open it in UI Builder on ha.coo.ee. Each `.uid` is a whole document on the `ha-cards` catalog,
   so it can be imported as it is, or created over MCP with `ui_builder_create_design`.
2. Render it on the server's `native` lane: `ui_builder_render_design_matrix` for light and dark,
   or `compose-preview design render --document <file> --catalog <ha-cards bundle>` against a
   preview server that has the catalog.
3. Publish each PNG beside its `.uid` as `<kind>[-<variant>].png`, and review the set before
   linking it anywhere people will see it.
