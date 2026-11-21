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
- **Nothing happens outside the editor.** The index is fetched when an editor shows the card,
  not when a dashboard is viewed.
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
- the index is fetched in advance (`getStubConfig` and `getConfigForm` start it, and the picker
  calls those as soon as it opens), so the hook answers from memory. Before it arrives the hook
  answers `null`, never a wait.

## The device index

`GET /designs/v1/suggestions.json` (`DeviceSuggestions`, design server): one row per design, card
type and device match, for live phone-card entries made for a device:

```json
{"serial": 12, "designs": [
  {"designId": "user:abc", "cardType": "tile",
   "device": {"integration": "bambu_lab", "model": "x1*"}, "title": "Bambu printer"}
]}
```

Generic designs are not in it; the full manifest is about 9 MB, the index is a few rows per device
design. The card matches with `DeviceMatch`'s rules (`deviceMatches`: every set field, without
case, `*` in a model), lists the most specific first, keeps the card types it binds live, and
offers at most three, labelled by the design's title.

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
a card keeps its design when the card style or dark mode changes. The card sends it to
`POST /api/cards/resolve` as `designId`; `CardResolve.resolveChosen` serves that design's entry for
the card's key and the requested style and mode, ahead of the exact and nearest paths, and the card
resolves as usual when the library has no such entry. `design` (a SHA-256) remains the way to pin
exact bytes, and the three of `design`, `design_id` and `template_url` exclude each other.

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
