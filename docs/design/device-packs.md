# Device packs

> Status: **first two packs drafted** (garage door opener, thermostat) as UI Builder documents in
> `card-designs/ui-builder/packs/`, **previewed** in this repository's build, and all six of their
> controls designs **published in the card design library**, where the card picker and the
> dashboard agent offer them: see [In the design library](#in-the-design-library).

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
  index.json                 every pack: id, title, the entities it fits, its contract, its manifest
  contracts/<id>.json        the Livery contracts the packs implement (below)
  <pack>/pack.json           the pack: its designs by kind and variant, alert rules, real-world source
  <pack>/<kind>[-<variant>].uid   one UI Builder design (compose-ui-builder-document/v1, ha-cards)
```

`pack.json` (`harc-device-pack/v1`):

- **`audience`**: the facts of the entities the pack fits (`entity.domain`, `entity.device_class`),
  written as [Livery audiences](https://github.com/yschimke/livery/blob/main/docs/PROTOCOL.md)
  will match them.
- **`contract`**: the Livery contract every design in the pack implements (`ha-device/cover@1`).
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
`DevicePacksTest` (`:ha-cards-catalog`) holds every pack to the catalog policy, to its contract,
and to its own manifest. It also checks that a cover or climate reading follows one of its
contract's float attributes, and that no metric shows such an entity's state.

## Contracts

A pack's designs implement one [Livery contract](https://github.com/yschimke/livery/blob/main/docs/PROTOCOL.md#4-contracts-and-data)
for the kind of entity they draw. It lists every value a design may read, each action it may
emit, and example datasets:

| Contract | File | For |
| --- | --- | --- |
| `ha-device/cover@1` | `contracts/ha-device-cover.json` | a garage door, gate, door, shutter or blind |
| `ha-device/climate@1` | `contracts/ha-device-climate.json` | a thermostat, heat pump or air conditioner |

- **Inputs are the names designs read today:** the entity's slot 0, `cover.s0.state`,
  `climate.s0.target_temperature`. The [Livery migration](../architecture/livery-migration.md)'s
  reference lists (`slots.0.*`) will replace them with a new major.
- **Only the name and the formatted state are required.** Every other input is optional with a
  default, because devices differ:
  - **Absent numbers:** a number a device may not report (a cover's position, a thermostat's set
    point while it is off) defaults to `-9999`, the `HaLiveBindings.ABSENT` a host pushes. A design
    shows a dash for it, as the attribute readings do.
  - **Optional covers' fields:** `device_class`, tilt, an obstruction sensor, the opener's light,
    a lockout and a sensor battery.
  - **Optional climate fields:** the raw and active modes, a heat/cool range, humidity, °C or °F
    with its own limits and step, presets and fan modes.
  - **Alert text:** `alert.message`, `alert.detail` and `alert.help_url`, empty unless an alert
    rule fills them.
  - **A picture:** `<domain>.s0.picture` for a real-world design.
- **Actions:**
  - **Changing a value** is a `set` of the input it changes, which the host turns into the service
    call: `set_position`, `set_temperature`, `set_hvac_mode` and the range's two set points.
  - **The rest are `app:ha.*` kinds** the app handles: `open`, `close`, `stop`, `toggle`,
    `turn_off`, `temperature_down`, `temperature_up` and `more_info`. A design sends the ones that
    name a service as [entity actions](#entity-actions).
  - **`help`** is a built-in `open-url`.
- **Examples** cover the variance: a cover that's closed, open, opening, with no position, stopped
  by an obstruction, left open, or unavailable; a thermostat that's heating, idle, off with no set
  point, holding a range, in °F, not reaching its set point, or unavailable.

The test reads the contracts with Livery's own types and binds every example, and the least a
host could push (the required inputs alone), through `ContractBinder` (`livery-client`), the gate
every dataset passes.

**Not wired yet:**
- **Hosts don't push every optional input yet.** They push `name`, `state`, `is_on`,
  `is_available`, `status`, `last_changed`, a cover's `state_int`, `current_position` and
  `supported_features`, and a climate's two temperatures, `min_temp`, `max_temp`,
  `target_temp_step`, `unit` and the three `*_label`s (`CardBindingRecipes.CLIMATE`). The others
  take their defaults until a recipe pushes them.
- **Designs emit only the actions that need no value from the person:** the entity actions (Open,
  Stop, Close, a toggle, a thermostat's step). The `set` actions (a dragged position, a chosen
  mode) and `help` aren't emitted yet: see below.

## The packs

**Garage door opener** (`garage-door/`, a `cover` with `device_class: garage`):

| Design | What it shows |
| --- | --- |
| `controls-material3` | Door icon, name and state, a status chip, the door's travel (`current_position`) as a bar, and Open, Stop and Close, each its own cover service (`open_cover`, `stop_cover`, `close_cover`). |
| `controls-skeuomorphic` | The door itself (`ha-cards/garage-door`): a sectional door in a brick wall that rises with `current_position`, sections sliding up under the lintel, the garage showing beneath and the porch light on while it moves. Then the state and the wall push button. |
| `controls-overlay` | Dark, `cockpit` style: the state in large type, the name, and a toggle. |
| `alert` | Left open, stuck moving, or unavailable: what happened, the name, an explanation, **Close now** and **Help**. |
| `toolbar` | Door glyph and state. |
| `real-world` | The garage camera (`camera.garage`, falling back to the entity's picture) with the name, state and a toggle under it. |

**Thermostat** (`thermostat/`, a `climate` entity):

| Design | What it shows |
| --- | --- |
| `controls-material3` | Name and mode chip, the thermostat dial (room temperature and set point, on the entity's own range and in its unit), and − / set point / +, which step the set point (`temperature_down`, `temperature_up`). |
| `controls-skeuomorphic` | A plain wall thermostat (`ha-cards/wall-thermostat`): a white plate with a grey display of the room temperature, the set point and mode, ▲ ▼ (which step the set point) and a status light. |
| `controls-overlay` | Dark, `cockpit` style: the room reading as a glyph, the mode, the target. |
| `alert` | Unavailable, not reaching the set point for two hours, or frost risk: what is wrong, the room and target readings, **Turn off** and **Help**. |
| `toolbar` | The room temperature as a chip. |
| `real-world` | The device's own picture, with its simulation as the fallback, and the name, mode and room reading under it. |

## In the design library

The card design library (`:card-designs:generateCardDesigns`, served by the design server)
publishes every pack's controls designs (`DevicePackLibrary`): the garage door's Material 3, wall
console and overlay designs, and the thermostat's.

- **Their own designs.** Each is a library design of its own, `pack:<designId>`
  (`pack:garage-door-controls-skeuomorphic`), titled as `pack.json` names it. It is captured for
  every theme the library keys its card by: a tile of the garage door (the thermostat card, for a
  climate entity). Its keys carry its own variant (`pack-<designId>`), so the card's library design
  stays the automatic one: a card draws a pack design only when it is set to it (`design_id`).
  - **One key for every state:** a pack draws its entity from bindings, so its keys publish every
    shape property but `domain` and `device_class` as `*` (`DesignProperties.ANY`). A garage door
    tile's `state_icon` follows the door (`garage`, `garage-open`, `arrow-up-box`,
    `arrow-down-box`), and `state_icon=*` matches each of them.
  - **Where the wildcard applies:** only a design chosen by id is matched that way
    (`DesignKey.accepts`, in the app's `DesignsById.find` and `designOptions`). A card's own key never
    carries `*`, and an automatic lookup stays exact.
- **The card's own entity, live.** Each is drawn by `CardDesignDocument` with its example entity
  named as the contract's slot (`DevicePacks.forSlots`: `cover.garage_door` is `cover.s0`), so it
  binds the entity of whichever card it draws.
  - **Recipes for attributes:** the slot bindings carry a state, never an attribute, so each
    template carries a binding recipe for the attributes it reads (`DevicePackLibrary.recipe`).
    `CardBindingRecipes.COVER` gives the door's `current_position`; `CLIMATE` gives the room
    temperature and the set point, the range (`min_temp`, `max_temp`) and step, the unit, and the
    overlay's labels (`current_temperature_label`, `temperature_label`, `hvac_action_label`).
  - **The thermostat's own unit:** `climate.s0.unit` is the entity's `temperature_unit` (or
    `unit_of_measurement`), else Home Assistant's own unit system (the recipe's `host` expression:
    `HaSnapshot.temperatureUnit` in the app, `hass.config.unit_system` or `get_config` in the
    browser), else °C. The designs bind their unit and dial range to it (`state` properties on
    `ha-cards/thermostat` and `ha-cards/wall-thermostat`), so a °F thermostat reads °F on its own
    45–95 range. A host older than the `host` expression shows a bare `°` and `—` labels, never a
    wrong unit.
  - **Both hosts:** the browser card evaluates a template's recipe, and so does the app's playback
    (`CardTemplatePlayback`'s bindings, ahead of the card type's converter).
  - **The guard:** `DevicePackLibraryTest` refuses a published design that reads a value no host
    pushes (checked for a °F thermostat), so none draws its example's numbers, and one whose
    buttons don't do what they say (below).
- **Buttons do what they say.** A button either toggles its entity (the wall console's push button,
  as a garage's own wall button does) or sends an [entity action](#entity-actions) its contract
  declares: the Material 3 garage door's Open, Stop and Close send `open_cover`, `stop_cover` and
  `close_cover`, so Close on a closed door closes it (Home Assistant ignores it) rather than toggle
  it open. The thermostat's − and + (and the wall thermostat's ▼ ▲) send `temperature_down` and
  `temperature_up`.
- **Suggested for its class.** A pack for a `device_class` is a class design
  (`DeviceMatch(deviceClass = "garage")`): the suggestion index lists it, so the card picker offers
  the garage door designs on any garage door, after any design made for that device. A pack for a
  whole domain (the thermostat) has no match: it is chosen by id, not suggested on every climate
  entity. Only cards `custom:remote-compose-card` wraps (tile, button, entity, gauge) are
  suggested; the thermostat's designs are keyed as the thermostat card, which the app and the
  `<ha-card>` embed draw and the Home Assistant card's visual editor doesn't offer.
- **To the dashboard agent.** The agent's `find_designs` tool reads the same index (see
  [dashboard-editing.md](../architecture/dashboard-editing.md)), so asking for "the actual garage
  door" gets a draft card set to it.

Alerts, toolbar items and real-world views are placements other than a dashboard card (and an
alert shows only while its rule holds), so they stay out of the library until those placements
exist.

### Entity actions

A design's control that does one particular thing to the card's entity names it: `ha-cards/button`'s
`action` property (`close_cover`). The control sends it as its own host action,
`ha.entity_action` (`HaEntityAction`: the slot, `cover.s0`, and the name), not as the card's `ha`
tap. The host resolves it with `CardEntityActions` (ha-model), against the slot's real entity:

| Domain | Actions | Service |
| --- | --- | --- |
| `cover` | `open_cover`, `close_cover`, `stop_cover`, `toggle` | the same, when `supported_features` has its bit (1, 2, 8), or reports none |
| `climate` | `turn_on`, `turn_off` | the same |
| `climate` | `temperature_down`, `temperature_up` | `set_temperature`: the set point one `target_temp_step` (else 0.5, 1 in °F) on, within `min_temp`..`max_temp`; none without a set point |
| `light`, `switch`, `fan`, `input_boolean` | `turn_on`, `turn_off`, `toggle` | the same |

- **The template names no service.** Only a name the table lists for the slot entity's own domain
  runs, on that entity alone, with data the host computes; a slot the card doesn't have, another
  domain or another name runs nothing.
- **Every host.** The app resolves it in `CardTemplatePlayback.resolveEntityAction`
  (`CachedCardPreview`; a directly captured card against its real entity), and the browser card in
  `resolveEntityAction` (`embed/ha-card.js`, which the Home Assistant card shares), a port held to
  the same cases (`CardEntityActionsTest`, `embed/test/entity-actions.test.mjs`). The Home
  Assistant card runs it on the card's own entity, never on its `tap_action`'s `target`, and
  still asks for the `tap_action`'s `confirmation` when it has one.
- **The control lock** holds it back as it does any write: a locked card sends an empty payload.
- **Older hosts and other surfaces do nothing.** A host that predates entity actions only listens
  for `ha`, so such a button does nothing there rather than run the card's tap (a cover's toggle).
  So does a launcher widget, whose numeric host actions can't carry one, and a watch, which
  doesn't resolve them yet.

## As a Livery catalog

`:device-pack-catalog` builds the packs into a Livery catalog with Livery's Gradle plugin, with no UI
Builder server (`livery { capture { uiBuilder() } }`, `livery-uibuilder-capture`):

- `devicePackSource` lays the packs out as a catalog source tree in `build/livery/source`:
  - `catalog.json` and the contracts;
  - per design, `designs/<designId>/design.json`, holding its contract, title, `<kind>-<variant>`
    as its variant, and the pack's `audience`;
  - the `.uid` beside it as `in-app.uid`.
- `captureLiveryCatalog` draws each document with `HaCardsUiBuilderCatalog`, the `ha-cards`
  catalog registered for Livery's capture, which renders with `CardDesignDocument` as the previews
  do. Its `prepare` names each design's example entity (`cover.garage_door`) as the contract's slot
  (`cover.s0`).
- `buildLiveryCatalog` builds the catalog with `livery build --inspect`'s checks. Every template
  must read only what its contract declares, and emit only the actions it declares.
- `renderLiveryPreviews` draws every design with each of its contract's examples, light and dark,
  into `build/livery/previews`.

The contracts declare two values every Home Assistant card uses:
- **`host.ambient`:** the host's always-on flag.
- **`ha`:** a card's own tap, an encoded `HaAction` the host runs.

The real-world designs are left out: their picture part draws a sample camera entity, not the card's
own.

## What isn't wired

These documents are designs to review and edit, ahead of the code that plays them:

- **Previews are this build's drawing, not UI Builder's.** `DevicePacks` (`:ha-cards-catalog`)
  bundles the `.uid` files and plays them with `CardDesignDocument`, which adds the frame's rows and
  columns, `RemoteText`, `RemoteButton` and the reading components to the card parts
  (`HaCardDesignLayouts`); `DevicePackPreviews` (`:previews`) renders each design, plus the door at
  three positions. `RemoteText` and `RemoteButton` are drawn in the HA theme, not by Wear Remote
  Material 3, and collapsible layouts draw as plain ones. A `host` state variable shows its initial
  value and is a named value the host can push. The server's `native` lane remains the reference;
  see [Publishing](#publishing).
- **Only entity actions.** A button sends its card's own tap action (`entityId`) or an
  [entity action](#entity-actions) (`action`). The contracts' `set` actions (a dragged position, a
  chosen mode or preset, the range's two set points) and Help aren't emitted: a value the person
  picks needs an action that carries it, which
  [server-owned-card-designs.md](../architecture/server-owned-card-designs.md) leaves out of scope
  for now. Entity actions aren't resolved on a watch or a launcher widget yet, where those buttons
  do nothing.
- **Alerts are not evaluated.** No client reads `showWhen` yet, and `alert.message` and
  `alert.detail` are values the rule would push. A client should hide the alert while no condition
  holds.
- **The overlay is a dark card**, not a transparent layer over the scene. The skeuomorphic designs
  draw the device (`ha-cards/garage-door`, `ha-cards/wall-thermostat`, in `HaCardDevices.kt`); the
  wall thermostat's ▲ ▼ keys step its set point.
- **Real world needs its source.** The picture is the card's entity picture
  (`picture-entity-card-image`). The camera entity and the simulation are in `pack.json`, not yet
  in the card config a host builds.
- **Only the controls are selected.** They are in the design library
  ([above](#in-the-design-library)); the other kinds aren't. Their Livery catalog
  (`:device-pack-catalog`) carries each pack's `audience`, ready for Livery's audience matching
  when the migration lands.
- **One entity per card.** A pack design draws one entity (slot 0). A garage opener's other
  entities (its light, a vent or a half-open button) are its contract's optional inputs, not yet
  filled from the device's other entities: that needs device cards
  ([lovelace-card-picker.md](../architecture/lovelace-card-picker.md), stage 3).

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
