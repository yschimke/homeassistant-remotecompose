# Remote Compose in Home Assistant's card picker

> Status: **stage 1 built** (picker entry, visual editor, device suggestions, `design_id`); the
> later stages are **planned**.

How the Remote Compose Lovelace card (`custom_components/terrazzo/frontend/remote-compose-card.js`)
takes part in building a dashboard in Home Assistant's own editor, without asking anyone to write
YAML and without crowding out Home Assistant's cards.

## Principles

- **A skin over a Home Assistant card, not a second card system.** The config is always
  `card:`, an ordinary `tile`, `button`, `entity` or `gauge` config; Remote Compose chooses only how
  it looks (`card_style`, `design_id`). Taking the wrapper off leaves the card Home Assistant
  would have drawn.
- **Suggest only what Home Assistant can't.** HA already suggests a tile for every light. Remote
  Compose is offered for an entity only when the catalog has a design **made for its device** (a
  printer, a boiler, a TV). A generic Remote Compose tile on every entity would be noise, which HA's
  own guidance for the hook warns against.
- **Device facts stay in Home Assistant.** The browser matches the entity's device against a
  small public index; the resolver is sent the chosen design's id, never a manufacturer, model or
  state.
- **Only editors fetch.** The index is fetched on a page that can edit dashboards (an
  administrator's), or when an editor shows the card; anyone else's dashboard never fetches it.
- **No takeover.** No dashboard strategy, no rewriting of existing cards, no dependency on the
  `/terrazzo` panel.

## Home Assistant's hooks

| Hook | Used for |
| --- | --- |
| `window.customCards` entry: `preview: true`, `documentationURL` | The picker draws the card live, with a help link |
| `getStubConfig(hass, entities, entitiesFallback)` | A new card is a tile of an unused light, switch or sensor |
| `getConfigForm()` | The visual editor: HA's card type, entity and name under `card`, and the card style. Watch faces, message cards and cards with their own template stay in YAML (`assertConfig`) |
| `getEntitySuggestion(hass, entityId)` (HA 2026.6+) | The **Community** suggestions when someone picks an entity |

`getEntitySuggestion` is synchronous and gets an entity, not a device. Neither is a limit here:

- the frontend already holds `hass.entities[id]` (`platform`, `device_id`, `translation_key`) and
  `hass.devices[device_id]` (`manufacturer`, `model`), which are exactly `DeviceInfo`'s matching
  fields (`deviceInfo` in the card);
- the index is fetched in advance, so the hook answers from memory. The picker's **By entity**
  tab asks before any other hook runs and keeps its first answer for an entity
  (`hui-suggestion-picker`), so the card starts the fetch when an administrator's page is idle
  (`preloadForEditors`; only administrators can save dashboards), and `getStubConfig` and
  `getConfigForm` start it too. Before it arrives the hook answers `null`, never a wait, and a
  failed fetch is asked for again by the next hook.

## The device index

`GET /designs/v1/suggestions.json` (`DeviceSuggestions`, design server): one row per design, card
type, entity domain and device match, for live phone-card entries made for a device:

```json
{"serial": 12, "designs": [
  {"designId": "user:abc", "cardType": "tile",
   "device": {"integration": "bambu_lab", "model": "x1*"}, "title": "Bambu printer",
   "domain": "light"}
]}
```

Generic designs are not in it; the full manifest is about 9 MB, the index is a few rows per device
design. The card matches with `DeviceMatch`'s rules (`deviceMatches`: every set field, without
case, `*` in a model), keeps only designs for the entity's own domain (a printer's light design
is no suggestion for its sensors, which the resolver could not draw with it) and the card types it
binds live, lists the best ranked first (see [Ranking](#ranking-by-scope)), and offers at most three, labelled by the design's
title.

A suggestion is the card set to that design:

```yaml
type: custom:remote-compose-card
card:
  type: tile
  entity: light.printer_chamber
design_id: user:abc
```

## Choosing a design by id

`design_id` names a design across its themes, modes and shapes (`DesignEntry.stableDesignId`), so
a card keeps its design when the card style or dark mode changes. A design that doesn't follow one
of its card's shape properties publishes it as `*` (`DesignProperties.ANY`; a device pack's
`state_icon`), and the app's lookup of a chosen design (`DesignKey.accepts`) matches it to any
value. A card's own key and automatic lookups stay exact. The card sends it to
`POST /api/cards/resolve` as `designId`; `CardResolve.resolveChosen` serves that design's entry for
the card's key and the requested style and mode, ahead of the exact and nearest paths, and the card
resolves as usual when the library has no such entry. `design` (a SHA-256) remains the way to pin
exact bytes, and the three of `design`, `design_id` and `template_url` exclude each other.

## Many designs per device

Three questions, kept apart: **who a design is for** (its device match), **how it looks** (the card
style), and **which of several fitting designs a card uses** (a choice per card).

### Scope: who a design is for

A design's `DeviceMatch` sets its scope (`DeviceMatch.scope`, `DeviceScope`), widest first:

| Scope | Example | Matches on | Status |
| --- | --- | --- | --- |
| Generic | any light tile | no device match; the key's `domain`, `device_class` | built |
| Class (`DeviceScope.Class`) | any garage door | `deviceClass` alone: the entity's `device_class` | built |
| Role (`DeviceScope.Role`) | a boiler's flow temperature on any boiler | `translationKey` alone | built |
| Class across brands, without a `device_class` | any 3D printer | a `kind` (below) | planned |
| Brand (`DeviceScope.Brand`) | every Bambu Lab printer | `integration` or `manufacturer` | built |
| Model (`DeviceScope.Model`) | X1 Carbon | `model`, a glob (`X1*`) | built |
| One device | *my* printer | none: a choice, not a scope | built as `design_id` |

- **Classes with a `device_class` (built).** An entity's `device_class` (`garage`, `gate`,
  `shade`) says what kind of thing it is, whoever made it. A design for a class
  (`DeviceMatch(deviceClass = "garage")`) matches any entity of that class: the card reads it from
  the entity's state, as Home Assistant draws it, and so does the app (`cardDevice`). It is the
  widest scope, so a design made for the device itself ranks first. The
  [device packs](../design/device-packs.md)' garage door designs are this scope.
- **Classes across brands without one (planned).** Home Assistant has no device category: a Bambu Lab, an
  OctoPrint and a PrusaLink printer share nothing in the registry that says "printer". Two changes:
  - the match moves from each `DesignEntry` to the design, as a list (`devices: [match…]`), so a
    class design lists its integrations once instead of copying every entry per integration (the
    Livery migration's repeatable `device.*` design tags are the same shape);
  - a `kind` table, catalog data in this repository and not Livery code
    (`printer: [bambu_lab, octoprint, prusalink]`), lets a design say `kind: printer`.
- **One device is not a published scope.** A device's registry id belongs to one home, so a design
  for it is useless to anyone else. Picking a design for that device is a choice: the card's
  `design_id`, or a person's choice kept by `card_selections.py`. A personal design is a design
  published privately and chosen there.

### Ranking by scope

Designs that match one device rank by scope, narrowest first (model, brand, role, class), then by
how many fields they set within a scope (`DeviceMatch.rank`): a model design beats a brand design
however many fields the brand design sets, a brand's chamber-light design (integration and
translation key) beats the brand's general one, and every device design beats a class design. `DeviceMatch.pick` (the app's automatic choice), `designOptions` (its
picker), the suggestion index's order and the card's `deviceRank` all use it. Before, they counted
fields, so `integration` plus `manufacturer` outranked `model: X1*`.

### Look: theme packs are card styles, not designs

A design is written once and re-themed into every card style
([server-owned-card-designs.md](server-owned-card-designs.md), goal 6), so one printer design is a
Classic, a Braun and an LCD card, chosen per card by `card_style`. In Livery a theme pack is values
pushed beside the template, with nothing to download. A layout that differs (a Braun printer
arranged differently, or one that needs its own typeface, which can't be bound) is a **variant**
or a separate design, chosen like any other. A device's choice is designs × card styles, and the
two are picked separately.

Beyond looks, a device has designs of different **kinds** (controls, an alert, a toolbar item,
its real-world view): [device packs](../design/device-packs.md) group them per kind of device.

### Several designs in one scope

Every design has its own `designId`, so two community designs for the X1 can both exist.

- **Automatic** (no choice made): the best-ranked curated library design. *Planned* to be the
  only automatic one: the app's sync orders authors by its preference queries
  (`DesignRepository.resolveDeviceDesigns`) rather than leaving shared designs out. As Livery's
  `OnRequest` designs aren't drawn until installed, a card shouldn't change because someone else
  published a design.
- **Chosen**: the card's `design_id`; a design that has gone draws the automatic one, never nothing.
- **Picker (planned)**: HA shows a label per suggestion and the card offers three, so offer the
  best design at each scope rather than three from one ("For your X1 Carbon", "For Bambu Lab
  printers", "For 3D printers"). Within a scope, library designs before shared ones, then by use
  ([template-usage-tracking.md](../followups/template-usage-tracking.md)).
- **Gallery (planned, stage 2 below)**: every fitting design grouped by scope, with a card-style
  switcher redrawing all of them live.

### Order of work

1. Rank by scope (built: `DeviceMatch.scope` and `rank`, `deviceRank` in the card).
2. Move the device match to the design, as a list.
3. Add the `kind` table for classes across brands.
4. Offer one suggestion per scope in the picker.
5. Keep one device a choice per card or person, never a published scope.

## Later stages (planned)

1. **Shared designs in the index.** Today the index and `resolveChosen` read the library only.
   Designs people share from UI Builder (`UserDesignStore.shared()`) join both together, so a
   shared device design can be suggested and drawn.
2. **A design gallery in the editor.** `getConfigElement` in place of `getConfigForm`: HA's entity
   field, then the designs that fit the entity (device ones first, then card styles) played live
   with the person's own state by the local player; **Use Home Assistant's card**, which replaces
   the wrapper with its `card:`; and **Edit in Card Studio** to fork the design.
3. **Device cards.** A design for a whole device (a printer's progress, temperatures and light)
   is suggested on any of its main entities; the suggestion fills the device's other entities from
   `hass.entities` by `translation_key` (`device_id` plus an `entities:` map), so picking a device
   is picking any one of its entities. Check whether the device page's **Add to dashboard** also
   shows Community suggestions before depending on it.
4. **One source of suggestions.** The Terrazzo panel's editor and the Android **Template** swap
   read the same index with the same matching, so a device is offered the same designs everywhere.
   With the Livery migration this is `TemplateSelector` with `device.*` design tags
   ([livery-migration.md](livery-migration.md)), held to the ports' conformance fixtures.
5. **The person's own design server.** With the Card Designs add-on, read the index from the
   server `terrazzo/design_server` names rather than ha.coo.ee.
