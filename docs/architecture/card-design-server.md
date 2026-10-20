# Card design server

> Status: **plan**. Nothing here is implemented yet. This document says
> what to build, in what order, and which questions need a spike first.

## Goal

Let card designs change without an app release. After a client loads a
dashboard, it asks a server for the newest **card designs** for the
surfaces it renders on. A design is a RemoteCompose document that holds
layout, theme hooks and **named bindings** but **no Home Assistant
values**. The client fills those bindings from its own HA connection.

- The server never sees a user's entities, states or dashboard config.
  It only knows the design and the binding names the design reads.
- The client caches designs on disk. It refreshes them on simple rules,
  and when it can't, it uses stale cached designs. If it has none, it
  uses the **inbuilt** designs bundled in the app. Every client ships
  the same design library it would download; the bundled copy is just
  older.
- Each design is built for one target: RC document API level (6, 7, 8),
  operation profile, surface, size mode and variant (for example
  `material` or `modern`). Designs can be themed by the host.
- The phone fetches designs for every surface it serves: the app, the
  launcher widget (when one is installed), the Wear app (when a watch is
  paired) and the Wear widget (when the watch has the widget
  installed).
- Each design is published in the `homeassistant-remotecompose` catalog,
  so the catalog shows exactly what ships.
- Each design has a reference design made in UI Builder, and the shipped
  document is checked against it.
- Nothing in a design is decided from a value when it is built. Colours,
  bands, icons and visibility are worked out in the document from bound
  values, so a variant can restyle them.

## Non-goals

- Server-side rendering with values. That is the add-on's
  `/v1/cards/{id}.rc` route (`addon-server/PLAN.md`), and this plan
  does not replace it. The design server knows nothing about any user.
- Executing remote code. A design is an RC document played by the
  existing player. It can only reach actions the client passes in for
  its slots (see [Actions](#actions)).
- Converting arbitrary custom cards. Card types with no converter stay
  `Unsupported`.

## Spike status

A first end-to-end slice is in the codebase, with **one rendering path**: every templatable card
(today: tile, entity, button, heading, glance, entities, alarm panel, gauge, clock, area, picture-entity,
sensor, history-graph, statistic, line statistics-graph, thermostat, humidifier, vertical / horizontal stacks, grids,
conditional and entity-filter cards, markdown, picture, picture-glance, logbook, calendar,
to-do list, weather forecast, light, media control, map, picture-elements, bar statistics graphs, the Bambu Lab cards and the
unsupported placeholder) — every built-in card type renders from a card design. The design comes from the server when one is
cached for the card's key, and from the **local design library** otherwise: the fallback for a
key the server lacks, or before the first sync. The library captures the same template from the
app's own converters on first use and keeps it on disk (`FileLocalDesignStore`, scoped to the
installed build and the display's density and font scale), so a key is captured once per build
rather than on every start, and never once the server covers it. With no server cards still take
the design path, just with built-in designs. Settings lists how many of a session's designs came
from the server and the card types and shapes the server lacked (`CardDesignSources`, never sent
anywhere): the shapes the library should cover next.
Cards that have no template shape render through their converter as before.

The server overrides the library, and it is on by default: the "Card design server" setting
starts at `https://ha.coo.ee/designs/v1`, and only an empty value someone saves turns it off (then
nothing is fetched). The app keeps the designs its **choice** (`CardDesignChoice`) matches: the
user's palette and card style in either mode, their preferred **variant** and **author** (both
picked in Settings from the tags the cached manifest offers), and the `default` variant and the
built-in author to fall back on. A sync downloads the whole manifest but only the blobs the choice
matches, so the server learns the choice, never which cards a dashboard holds. A card plays the
preferred variant's design for its key, then the `default` one, then the library's; between two
authors' designs for one key, the preferred author's wins. A revoked server design falls back to the
library's, never to nothing.

| Piece | Where |
| --- | --- |
| Wire model: `DesignKey`, `DesignSurface`, `DesignManifest`, `DesignEntry` | `ha-model/.../design/CardDesign.kt` |
| Slot mapping and slot bindings (with `status`, neutral defaults); `DesignProperties` | `ha-model/.../design/CardDesignSlots.kt`, `DesignProperties.kt` |
| `CardConverter.designProperties` / `designTemplate`; shared entity properties (`EntityDesign`) | `rc-converter/.../CardConverter.kt`, `cards/EntityDesign.kt`, each `*CardConverter` |
| Bound name and title text in templates (`LocalTemplateBindings`, `entityName`, `cardTitle`) | `rc-components/.../TemplateBindings.kt` |
| Client: config (a choice of designs by tag; no URL is off), refresh rules, TTL, back-off, serial rollback check, expiry, sha256-verified blobs, stale and inbuilt fallback, revocation; `DesignPublisher` writes the static files | `ha-client/.../client/design/` |
| Template capture, keys, spike key matrix, local design library (`CardDesignLibrary`) | `rc-converter/.../CardDesignTemplates.kt` |
| Template playback: slot-binding push; a tap runs the card's own action for the tapped slot | `CachedCardPreview(template = …)`, `CardTemplatePlayback.resolveTap` |
| App: server, variant and author settings, `CardDesignChoice`, `FileDesignStore`, `LocalCardDesigns`, dashboard hook (reads the cache only) | `app/.../dashboard/CardDesigns.kt`, `PreferencesStore.designServerUrl` / `designVariant` / `designAuthor` |
| App: nightly manifest sync while charging on an unmetered network, plus one sync whenever the choice changes (and on start) that fetches only the blobs it lacks | `app/.../dashboard/CardDesignSync.kt` |
| End-to-end test: capture → publish → fetch from a fake host → resolve → bind | `rc-converter/src/test/.../CardDesignTemplatesEndToEndTest.kt` |
| Pixel parity: each card through its template and through its converter draws identical pixels | `rc-converter/src/test/.../CardDesignParityTest.kt` |

Deliberate spike limits, each owned by a milestone below:

- **Which cards:** tile, entity and button (`entity` plus optional `name` / `icon`, and the
  button's `show_name`), heading (`heading_style`), and glance and entities (`title` plus rows
  that are a bare entity id or `{ entity, name?, icon?, tap_action? }`). Entities must be `light`, `switch`,
  `sensor` or `binary_sensor`, on the mobile app surface. Any other config has no shape and stays
  local. The alarm panel is keyed on its ARM buttons (`states`) and `show_keypad`; its status
  variants already switch in-document on `<slot>.state_int`.
  The gauge's properties are its entity's domain (which names the slot) and whether its range
  line shows (`has_unit`): `min`, `max` and the
  severity thresholds are card-level float bindings (`card.min`, `card.severity_red`, …), the
  range text is `card.range`, and the document picks the band from the live value. Those
  card-level values are a **binding recipe** (see [Binding recipes](#binding-recipes)), so hosts
  without the converters compute them too.
  The clock is keyed on which lines show and its time format; the date line is `card.date` and
  another zone's offset `card.zone_offset_minutes`, so neither DST nor midnight changes its
  structure. The unsupported placeholder shows only its type, which is already in the key.
  The area card is keyed per row on domain and `device_class`, its name is `card.name`. A
  picture-entity template carries `rc-slot://<slot>/entity_picture` in place of the image URL;
  playback resolves it to the card's own entity's picture and refuses every other URL a
  template names (`CardTemplatePlayback.bitmapUrl`).
  Sparklines (sensor, history-graph) always draw `SPARKLINE_SAMPLES` resampled points, so the
  history length is never structure: the samples are `<slot>.numeric.<i>`, the y-range
  `<slot>.series_min` / `series_span`, a history graph's per-row summary `<slot>.summary`, and
  the only property the data decides is whether a row has a line at all (`has_data`).
  The arc dials (thermostat, humidifier) already read `value_fraction`, `target_fraction` and
  their label attributes as bindings; the client now pushes them, a state-picked accent is an
  index into the card's own palette (`<slot>.accent_index`, selected in-document), and a
  stepper tap is matched against the template's own steppers and rebuilt from the live target.
  A container (stack, grid) is templated from its children (`CompositeDesign`): each child's
  type and properties under `c<k>.`, one slot sequence over all children (child templates are
  renumbered), child k's card-level bindings under `card.c<k>.` (`ProvideChildCardBindings`),
  and a tap resolved by the tapped child's own playback.
  Conditional and entity-filter cards render as the card they currently show
  (`CardConverter.designDelegate`, resolved by `CardDesignTemplates.designCard`): the client
  evaluates the conditions or the filter on live data and keys, binds and taps that card.
  Markdown renders its content (tokens and Jinja alike) on the client; the template is keyed
  on the rendered block structure and each block's text is `card.md.<i>`. Blocks with links or
  images keep the converter, since their URLs can't be in a template.
  List cards (logbook, calendar, to-do, weather) key on their row counts and each row's discrete
  look (an entity's icon, a calendar's colour, a forecast day's condition group) and bind every
  row's text (`card.row.<i>.*`, `card.day.<i>.*`, …); a to-do tap is matched against the
  template's own items and rebuilt from the live item at that position.
  A light and a media player key on a discrete state index (on, playing) that decides their
  controls; brightness, progress and every label are bindings, and their taps are matched
  against the template's own controls and rebuilt for the live entity.
  Picture-elements key on each element's type, its position in whole dp on the card's canvas
  and its entity's properties; every template element taps as `#el-<k>`, and playback runs
  element k's own configured tap (a slot-less tap reaches `designAction` with `NO_SLOT`).
  The Bambu Lab cards find their printer's entities by prefix, so a template names them with a
  placeholder prefix (`entity_prefix: rcslot`) the client renames its printer onto; a spool's
  colour is `<id>.color.r/g/b/a` floats composed in-document (`entityColor`), and a control tap
  presses only the client's printer's own pause / resume / stop button.
- **Generation properties** (`EntityDesign`) are what changes the look, per bound entity:
  `domain` (icon family, toggle chrome), `device_class` for sensors and binary sensors (icon,
  colour), the `icon` attribute and a config `icon` (`card_icon`). A tile whose sensor draws a
  meter glyph (`HaMeasureKind`, docs/design/everyday-cards.md §1) adds its `unit`, which with
  `device_class` picks the glyph's kind and scale (°C or °F); like the rest, it is an attribute,
  never the state. List cards prefix them with the
  row's slot (`e0.domain`, …) and add `count` and the title's font-size bucket (`title_size`).
  Slot ids keep the domain (`light.s0`, not `s0`) for the same reason. A library template
  therefore draws exactly what the converter drew, with names, titles and states as bindings
  (`<slot>.name`, `card.title`).
- **Taps** come from the card's own config. A template's encoded action only says which slot was
  tapped, so a glance cell runs that row's `tap_action`. A converter may also rebuild a more
  specific action from its own config (`CardConverter.designAction`): the alarm panel accepts an
  ARM service only if its `states` offer it, and a key only if the keypad has it, with the code
  length taken from the live entity; per-card `tap_action` keeps the converter
  path for now because `none` drops the clickable.
- **One known difference:** templates are captured with a placeholder `off` state, so an
  unavailable light or switch keeps its toggle chip (inactive colour) where the converter drew a
  plain grey icon. `<slot>.status` is bound, and moving that visual in-document is M1.
- **Baked themes.** Classic designs are keyed `baked:<ThemeStyle>:<light|dark>`, so the matrix has
  one template per theme. Every other card style brings its own palette, so its designs are keyed
  `style:<card style>:<light|dark>`, once per mode the style has. `theme.*` bindings (S2) replace
  the baked palettes.
- **Still decided at capture:** the tile icon and accent colours, from the shape (domain,
  `device_class`, `icon`). They don't depend on the state for the templated domains; the on/off
  accent switches in-document from `is_on`. Binding them instead of keying on them is M1.
- **No manifest signature** yet: the app accepts unsigned manifests (`allowUnsigned`). M4 adds
  signing, and the client already reserves `signature` and checks `serial` and expiry.
- **The library is captured at runtime**, not shipped as assets, and kept on disk per build. It
  covers the dashboard, launcher widgets and Wear widgets. No UI Builder references (R1, R2, M2b), and no public
  deployment of the server yet (M4).
- **The server and its library exist** ([below](#the-server-design-server)):
  `:card-designs:generateCardDesigns` builds the mobile-app library, and `:design-server` hosts it
  for the app with a browser of its templates.
- **Wear widgets are baked too** (`SlotWidget`): Glance Wear captures the slot's template itself
  (its own op set and canvas, keyed by card shape, theme, container and size) and
  `LauncherDesigns.bakeValues` writes the card's values in. The Wear widget has no taps. The watch
  can't fetch a picture, so the phone does: for every entity a widget slot card binds that has an
  `entity_picture`, `MobileWearSyncManager` sends a downscaled PNG (`WearPicture`,
  `/wear/picture/<entityId>`, at most every 5 min, skipping the image cache, and only when it
  changed), and baking embeds it. The widget reads its slots, pinned cards, values and pictures
  from the Data Layer when it renders, so they arrive whether or not the watch app was running;
  the phone writes the values DataItem whenever the app isn't open (its live stream only reaches a
  running app).
  Until one has synced, the card keeps the direct capture, which shows the card's icon
  (`SlotWidgetDesignTest`, `WearPictureSyncTest`).
- **Wear widgets play the card's own design.** A Wear widget isn't a separate design: it is
  the card's design rooted on the Glance Wear container, Small or Large. Glance Wear draws the
  container's shape and background into the document, so the card's own surface (background,
  border) goes and the container's takes its place; the rest adapts to the container as the card
  does to a dashboard. The design's mobile bytes can't be played as they are: they use ops the
  Wear widget profile rejects (`CORE_TEXT`, `FlowLayout`), and draw their own surface. Built-in
  designs are captured on the watch from the card's converter, as above. A design published from
  UI Builder gets its Wear widgets when it is published: the design server roots the same UI
  Builder revision on `remote-m3/widget-container-small` and `-large` without its card surfaces
  (`WearWidgetDesigns`), exports each, and keeps those that pass the Wear widget profile and bind
  what the card does, as `wear-widget` designs keyed like the card's with a `container`
  (`small` / `large`). They are shared, unshared and replaced with the card's design. The phone
  sends each widget slot card's Wear designs (its dark design's, in the card's variant, then
  `default`; `wearSlotDesigns`) as `/wear/design/<sha256>` blobs and a `/wear/slot-designs`
  index, and the watch bakes the one for the widget's container, falling back to its own capture
  (`SlotWidgetServerDesignTest`, `WearSlotDesignPickerTest`).
- **Launcher widgets are baked** (S1, `LauncherDesigns`): a launcher can't receive bindings, so
  the widget's launcher template — keyed by size (`variant = <w>x<h>`) and widget theme, captured
  from the same tree as a live widget (`LauncherWidgetContent`) — gets the card's values written
  in on the device with `rc-player-edit` (`hardcodeNamedValues`, then
  `collapseConstantStateLayouts` and `optimize`). Its host actions keep their ids; each
  PendingIntent carries the card-local action the template's payload resolves to
  (`resolved_action`, which the receiver prefers over the document's slot-named metadata). A
  template's slot pictures are resolved to the card's own entity pictures, fetched through the
  app's image stack and embedded (`embedBitmapUrls`); a PIN typed into the widget arrives as an
  `AlarmPin` on the real panel with the digits from the tap's metadata. Live widgets read the
  full-state snapshot the app's session keeps on disk (`OfflineCache`).

To try it, generate the library, run the server, and paste `http://<host>:8080/designs/v1` into
the app's "card design server" setting:

```sh
./gradlew :card-designs:generateCardDesigns :design-server:installDist
design-server/build/install/design-server/bin/design-server card-designs/build/card-designs
```

## Where we are today

What the codebase already gives us, and what blocks a values-free
design:

| Exists | Where |
| --- | --- |
| One converter per card type, `Render(card, snapshot)` | `rc-converter/.../CardConverter.kt`, `cards/DefaultConverters.kt` |
| Named bindings pushed by name, with no re-encode | `rc-components/.../LiveValues.kt`, `ha-model/.../rc/CardEntities.kt` (`cardSnapshotBindings`), `CachedCardPreview.pushSnapshotBindings` |
| Named bitmaps (picture bindings) | `rc-components/.../LocalNamedRemoteBitmap.kt`, `PictureBindingName.kt` |
| Profiles for each surface | `AndroidxExperimentalProfile.kt` and `WrapModeProfile.kt` (latest level), `WidgetProfile.kt` (level 7 widgets, with and without `COLOR_THEME`), plus the Glance Wear op set for wear widgets |
| Host-resolved colours | `SystemRemoteHaTheme` (`ColorTheme` op, API 37+ launcher), `WearM3.*` named colours (wear widget) |
| Theme styles | `ThemeStyle` (Material3, Home, Mushroom, Minimalist, Kiosk) |
| Fixed-mode tier ladders inside a single document | `docs/architecture/adaptive-card-layouts.md`, `RemoteSizeBreakpoint.kt` |
| A priority chain of generators | `ha-client/.../CardGenerator.kt` (`CardSource`), not yet wired into the app |
| Signals for a paired watch and installed widgets | `WearCapabilityProbe` (`terrazzo_wear_widgets`), `WidgetSync.liveWidgetIds()` |
| `.rc` export for each preview, published to the catalog | `previews/.../RcDocumentCapture.kt`, `.github/workflows/design-artifacts.yml` |

What blocks it:

1. **Binding names contain the entity ID** (`light.kitchen.state`), so
   a document only works for the entities it was captured with.
2. **Values are baked as initial values**, along with static config
   text (names, titles), icons and, on the dashboard, concrete theme
   colours.
3. **Re-encode cards** (path 2 in `live-card-updates.md`) bake content
   that has no binding: lists, series, forecast strips, arc fractions,
   per-card labels, Jinja output.
4. **No document is persisted.** Only the in-memory LRU
   (`CardDocumentCache`) holds them.
5. **Capture needs an Android `Context`**, so it can't run in a JVM
   server. This doesn't block us if designs are built at build time
   (see [Hosting](#hosting-static-first)).

## Core idea: slot-relative templates

A **design** is an RC document whose named bindings refer to **slots**,
not entities. The client maps each slot to an entity using the card
config, then pushes values under the slot names.

### Binding schema v1

| Binding | Type | Client value |
| --- | --- | --- |
| `s<N>.state` | string | `formatState(entity)` for the entity in slot N |
| `s<N>.is_on` | bool | `state == "on"` |
| `s<N>.numeric_state` | float | parsed numeric state |
| `s<N>.state_int` | int | domain-keyed variant, same tables as today |
| `s<N>.status` | int | `0` available, `1` unavailable, `2` unknown, `3` entity missing |
| `s<N>.name` | string | `name:` from config, else `friendly_name` |
| `s<N>.icon` | bitmap | icon rendered by the client (MDI path → bitmap) |
| `card.min`, `card.max` | float | range from config (`min:` / `max:`), else the card's default; from the template's binding recipe |
| `card.severity_<band>` | float | configured band starts (gauge `severity.green` / `yellow` / `red`), `1e30` when unset; from the recipe |
| `card.range` | string | the gauge's range line, `0 – 100 %`, only when it has a unit; from the recipe |
| `s<N>.rgb` | color | `rgb_color` attribute passed through as-is (lights) |
| `card.title` | string | card-level `title:` / `name:` |
| `card.count` | int | number of populated rows, for list-shaped cards |
| `theme.<role>` | color | theme role (primary, surface, onSurface, …) where the target has no `ColorTheme` / `WearM3` |

The `<id>.state` rules in `live-card-updates.md` still hold, with
`s<N>` standing in for `<id>`. The client must be able to rebuild every
value from the entity state plus the card config alone.

### Binding recipes

Slot bindings are the same for every card. **Card-level** bindings (`card.min`, `card.range`, a
slot value a card reads its own way) differ per card type: `CardConverter.designBindings` computes
them in Kotlin, which a host without the converters cannot run. So a converter declares them as
data instead, `CardConverter.designRecipe()` returning a `CardBindingRecipe` (ha-model), and a
template captured from it carries that recipe as the named string `meta.binding_recipe`. Nothing
draws it. A host reads it once when the template loads and evaluates it on every update.

```json
{ "v": 1, "prototype": "gauge",
  "floats": {
    "card.min": { "first": [{ "number": { "config": "min" } }, 0] },
    "card.severity_red": { "first": [{ "number": { "config": "severity.red" } }, 1e30] },
    "$0.numeric_state": { "first": [{ "number": { "state": 0 } }, { "ref": "card.min" }] } },
  "strings": {
    "card.range": { "fn": "range_text", "args": [{ "ref": "card.min" }, { "ref": "card.max" },
                    { "first": [{ "config": "unit" }, { "attr": "unit_of_measurement", "slot": 0 }] }] } } }
```

- **Expressions** are a closed set: literals, `config` (a dotted path into the card config),
  `state` / `attr` (slot `i`'s raw state or an attribute), `number`, `first` (first non-null),
  `ref` (an earlier binding) and `fn` (a named function: `range_text`). A binding whose expression
  is null is left out; `$<i>.` names slot `i`. A new function or expression form is a new `v`, and
  a host ignores a recipe whose `v` it doesn't know.
- **`prototype`** names the card type the recipe was written for. It never changes the result; a
  host may use it for diagnostics or a built-in implementation.
- **One source.** A converter with a recipe leaves `designBindings` to its default, which evaluates
  that recipe, so the app's template playback and every other host compute the same values. The
  embed's port (`recipeBindings` in `embed/ha-card.js`) is pinned to Kotlin by recipe cases in
  `embed/test/bindings-golden.json`.
- **Strict hosts** (`require-host-bindings`, the Home Assistant card) accept a template only when
  every data binding it declares has a value: slot bindings, its recipe's bindings, or the recipe
  itself. A template declaring a card-level binding with no recipe is still rejected, never shown
  with its fixture values.

A converter can scope its recipe to some shapes (`CardConverter.designRecipe(properties)`) and then
derives `designBindings` from the card's own properties, so template and host still agree. The
tile does: only a metered sensor tile's template carries `CardBindingRecipes.TILE`, which pushes
`$0.numeric_state` as the state's number, else 0, so the glyph never reads the slot bindings' NaN.

The gauge declares one (`CardBindingRecipes.GAUGE`). Other converters with card-level bindings
still compute them in Kotlin only, so their templates work in the app but not yet in the embed.

**Producing templates costs almost nothing.** Run the existing
converters against a **design snapshot** whose entity IDs are the slot
names (`s0`, `s1`, …) and whose values are neutral placeholders.
`LiveValues` then emits `s0.state` with no change to any converter. The
work is in the parts that bake content today: names, icons, colours and
path-2 content must move to bindings. That is the same direction the
live-updates contract already pushes converters.

**Slot mapping** lives in `ha-model` common code, so the app, the watch
and the add-on agree on it: `fun slotsFor(card: CardConfig):
List<SlotBinding>`, one mapper per card type (tile → 1 slot, entities →
N rows, glance → N cells). The output goes into a design manifest entry,
so the client can check that a template's slot count fits the config.

### No decisions at capture time

A template must not contain anything **chosen** from a value. The
converter output may depend on the config **shape** (below) and nothing
else. A value or a config number never picks a branch, a colour, an icon
or a size while the document is being built. Every visual that depends
on a value is worked out **in the document**, from bindings, every time
the value changes:

| Today (picked at capture) | In the template |
| --- | --- |
| Gauge severity band, from the value and `severity:` thresholds (`GaugeCardConverter`) | RC float expressions compare `s<N>.numeric_state` with `s<N>.threshold.*` and pick the band colour from theme roles |
| State colour (`HaStateColor`) | `s<N>.is_on` / `s<N>.state_int` select a theme role in-document; lights mix in `s<N>.rgb` |
| On/off icon, lock/unlock pictogram | `RemoteStateLayout` over `s<N>.state_int`, with every variant in the document |
| Arc fraction (thermostat, humidifier, light) | `(numeric_state - min) / (max - min)`, computed in-document |
| Shown or hidden parts (empty name, unavailable state) | visibility driven by bindings (`s<N>.status`, an empty `s<N>.name`), not by leaving nodes out |

The client never computes a presentation value either. It pushes raw,
host-reproducible values (`state`, `numeric_state`, thresholds, ranges)
and the design decides what they look like. A variant can therefore
restyle severity colours or the on/off look without a client change,
which is the point of serving designs.

The client pushes **every** binding of a slot on every update, never a
subset. Today `cardSnapshotBindings` leaves out `numeric_state` and
`state_int` for an unavailable entity, which would leave the previous
number in the player. For templates, a value with no meaning gets a
defined neutral value (`numeric_state = NaN`, `state_int = -1`), and
`s<N>.status` says why. The design never infers availability from the
formatted, localised `s<N>.state` string.

This is enforced in the pipeline, not by review. The validator captures
every template twice, with two **different** design snapshots (different
placeholder values, thresholds, ranges and `status`), and fails if the two
documents differ anywhere except the initial values of named variables.
A converter that branches on a value at capture time produces two
different documents, and the build stops.

### Variable-length cards

Entities, glance and similar cards have N rows. In v1, publish templates
in **row-count buckets** (1, 2, 3, 4, 6, 8) and hide unused rows with
`card.count`. A card with more rows than the largest bucket uses the
inbuilt converter. A data-list op in RC could later replace the buckets
(spike S3).

### Generation properties

Bindings carry values, and slot mapping carries entities, but some inputs change the **structure**
a converter emits, and no binding can apply them after capture. For example, `HeadingCardConverter`
bakes `heading_style`, `ClockCardConverter` bakes `clock_size`, `show_seconds` and whether
`time_zone` is set, `AlarmPanelCardConverter` bakes `states` and `show_keypad`, and the tile picks
its icon and colour from the entity's domain, `device_class` and `icon` attribute.

Each converter therefore declares its **generation properties**:
`CardConverter.designProperties(card, snapshot): DesignProperties?`. They are the discrete inputs
that change its document, taken from the card config and the entity's attributes:

- **Strings and enum ints only** (`DesignProperties.Builder` offers `string` and `enum`, nothing
  else). A float such as `numeric_state` would mean one template per reading, and display text is
  a binding, so neither is ever a property.
- **They are the index.** Their canonical encoding (`DesignProperties.shape`, sorted `name=value`
  pairs) is the template part of the `DesignKey`. Each distinct set has its own template: the
  server manifest is the remote index, and the local design library is the same index, filled on
  demand.
- **The converter can rebuild a template from them.** `CardConverter.designTemplate(properties)`
  returns the config and placeholder snapshot to capture: slot entity ids, with the properties
  mapped back onto the placeholder entity.
- **Null means "not templatable"**: an unsupported config, or an entity that isn't loaded yet. The
  card then captures through its converter.

An enum-like **state** can also be a property (for example a lock's state index). Every state then
gets its own template variant, and a state change swaps templates instead of pushing a binding.
That is the right trade for hosts that can't receive bindings at all.

**Launcher widgets and other push-less hosts.** These hosts play the bytes they're handed. They
pick the template by its generation properties, then **rewrite** the remaining bindings (names,
formatted states, numbers) into the document's initial values before handing it over (spike S1).
Discrete structure is chosen by the properties, so the rewrite only has to touch named values.

Non-structural config values (gauge `min` / `max`, severity
thresholds, a time zone ID) are bindings, not shapes, or the matrix
explodes. When a structural choice depends on the entity's domain (a
tile for a lock and a tile for a light differ in shape, not just
colour), the domain is part of the shape.

### Which cards fit

- **v1 (binding cards):** sensor, entity, tile, button, glance, area,
  entities, alarm-panel, gauge, heading, clock. Alarm-panel, heading and
  clock need [generation properties](#generation-properties). Gauge needs its
  severity band moved in-document first (see
  [No decisions at capture time](#no-decisions-at-capture-time)).
- **Later:** thermostat, humidifier and light, once their arc fraction
  and labels are computed in-document; media-control, once its title and
  progress are bindings.
- **Stay local:** calendar, logbook, to-do, history and statistics
  graphs, map, weather-forecast, markdown with Jinja. They need list or
  series data, so they keep re-encoding on the device. The design server
  just doesn't list them, and the chain falls through.

### Initial values and instantiation

A template has no real values, but some hosts can't receive pushes. The
launcher's `RemoteViews.DrawInstructions` and the Glance Wear widget
both play bytes they are handed. Every client therefore needs an
**instantiate** step: take the template and a binding map, and write
the values into the document's named-variable initial values. Hosts
that support pushes (the in-app player) still push afterwards, so the
first frame is right and live updates stay cheap.

This is the **biggest technical risk** (spike S1). Options, best first:

1. Patch the initial-value ops of named `User`-domain variables in the
   byte stream (decode the op list, rewrite those ops, re-serialise).
   The header and the rest of the document stay untouched.
2. Load the document into a `CoreDocument`, apply the named values,
   then serialise again, if the library supports serialising.
3. If neither works: in-app only, push before first draw, and keep
   launcher and wear widgets on local converters.

### Actions

A template never contains a URL or a service call. Tap targets emit a
**slot action ID** (`s<N>.tap`, `s<N>.hold`). The client resolves that
ID to the slot's `tap_action` from the card config, using the dispatch
it has today (`HaActionDispatcher`, and on the launcher the numeric host
action IDs collected at capture). A malicious or broken design can at
worst trigger an action the user already configured on that card.

## Design key and target matrix

```
DesignKey(
  cardType,        // "tile"
  variant,         // "material" | "modern" | … ("default" always exists)
  surface,         // mobile-app | launcher | wear-widget | wear-app | desktop | tv
  rcApiLevel,      // 6 | 7 | 8 …
  opProfile,       // bitmask: ANDROIDX | WIDGETS | EXPERIMENTAL | …
  sizeMode,        // wrap(widthDp range) | fixed(range of W×H)
  themeMode,       // host (ColorTheme / WearM3 / theme.* bindings) | baked(style, dark)
  shape,           // generation properties (DesignProperties.shape), see "Generation properties"
  bucket,          // row-count bucket, or 1
  schema,          // binding schema version, "1"
)
```

Each surface starts from today's profiles:

| Surface | Profile | Size mode | Theme | Gate |
| --- | --- | --- | --- | --- |
| `mobile-app` | `androidXExperimentalWrap` (latest level) | Wrap | `theme.*` bindings | always |
| `launcher` | `widgetsProfile` (level 7) / `systemThemedWidgetsProfile` (API 37+) | Fixed, tiered | `ColorTheme` on 37+, otherwise `theme.*` | `WidgetSync.liveWidgetIds()` not empty |
| `wear-widget` | Glance Wear stable op set (no FlowLayout) | Fixed, one design per container (`small` / `large`) | the card's design's, dark | a widget slot card with a Wear design |
| `wear-app` | AndroidX profile, round-safe | Wrap | `theme.*` / Wear M3 | watch paired with Terrazzo installed |
| `desktop`, `tv` | to be decided with the CMP player | Wrap | `theme.*` | later |

**Version negotiation.** The client sends the highest document API
level it can play and its op-profile bitmask. The server answers with
the best design at or below that level. A level-6 player gets a
level-6 design, or nothing, in which case it uses inbuilt designs. We
publish only the levels real clients run: level 7 for widgets, and the
library's current level for the app. Levels 6 and 8 get added once a
real host needs them (for example an older launcher runtime, or a newer
player).

**Size.** Wrap designs declare a supported width range. Fixed designs
carry their tier ladder inside the document (`RemoteStateLayout` on
`componentWidth/Height`, per `adaptive-card-layouts.md`), so one
document covers a range of cells, and resizing doesn't trigger a fetch.
The manifest lists the W×H range each design covers. The client picks
the entry that covers its cell. A design can also be marked
`fixedSize` for hosts that never resize.

**Theming.** A design is themeable when its colours come from the host
and are not baked. In order of preference: `ColorTheme` ops,
`WearM3.*`, then `theme.<role>` named colours that the client fills from
`haThemeFor(style, dark)`. Fonts can't be bound, so typography belongs
to the variant: a `mushroom` variant ships Figtree. A `baked` theme mode
exists only for targets that can't bind colours. It multiplies the
matrix, so avoid it.

**Variants vs theme styles.** A *variant* is a layout and shape language
(`material`, `modern`, `compact`, …). A *theme style* is colours and
fonts (`ThemeStyle`). They combine freely. Variants can be added on the
server with no client change: the manifest lists the variants for each
surface, the settings picker reads that list, and `default` always
exists, both in the app and on the server.

## Server API

Designs are values-free and the same for every user, so the API is
read-only, cacheable and content-addressed.

```
GET /designs/v1/manifest.json
  → { schema: 1, serial, generatedAt, expiresAt, minClient, ttlSeconds,
      designs: [ { key: DesignKey, sha256, bytes, slots, author, sizeRange,
                   catalogId, deprecated? } … ],
      variants: { "<surface>": ["default", "material", "modern"] },
      signature }

GET /designs/v1/blobs/<sha256>.rc     (immutable, Cache-Control: max-age=31536000, immutable)

```

**Tags.** A design is chosen by four tags: its **card type**, its **style** (the card style it is
drawn in, `DesignKey.cardStyle`: `classic` for a design baked in an app palette), its **variant**
and its **author** (`DesignEntry.author`: `terrazzo` for the library generated from the app's own
converters, `github:<login>` for one published from UI Builder). Every manifest entry carries
them, so the client chooses on its own copy of the manifest (`DesignQuery`, compared without case)
and works out what a picker offers from it (`DesignTags`: every value with its count). There is no
lookup endpoint: the server only ever sees a manifest fetch and the blobs a client's choice of tags
matches, never which cards it renders.

- One manifest lists every target. The client filters it to the
  surfaces it serves. The manifest is small, and a single file keeps
  hosting static. Split it by surface only if size becomes a problem.
- Use ETag / `If-None-Match` on the manifest. Blobs never change,
  because the name is the hash.
- **Integrity.** The client checks each blob's sha256 against the
  manifest. The manifest is signed (Ed25519) with a key whose public half
  ships in the app, so a compromised host or CDN can't push designs. A
  bad signature is treated like a network failure.
- **Freshness.** A signature proves who wrote a manifest, not that it is
  current: a compromised host could keep serving an older signed manifest
  and hide later `revoked` flags. The signed body therefore carries a
  monotonic `serial` and an `expiresAt` (for example 14 days after
  `generatedAt`). The client stores the highest `serial` it has accepted
  and rejects any manifest with a lower one. A manifest past `expiresAt`
  doesn't count as a successful refresh: the client keeps rendering from
  cache but keeps retrying, and after a grace period it stops using
  remote designs and drops to inbuilt ones.
- **Kill switch.** `minClient`, plus a `deprecated` / `revoked` flag on
  each design, lets us pull a broken design. The client then drops to its
  inbuilt copy.

### Hosting: static first

The server needs no runtime, because designs are build outputs. The
design pipeline (below) writes the manifest and blobs, and any static
host can serve them:

- **Recommended:** a `/designs/v1/` path on the Pages deployment that
  `.github/workflows/wasm-pages.yml` already builds. That workflow must
  keep `docs/auth/` and `docs/terrazzo-auth/`, so the designs path is
  added next to them, never in their place.
- Alternative: the `design-artifacts/homeassistant-remotecompose` branch
  (in the `yschimke/homeassistant-remotecompose-out` output repository)
  that preview.coo.ee serves. This keeps designs and catalog in one
  bundle, but couples app runtime to the preview server's uptime.
- Optional mirror: the add-on serves the same files at
  `/v1/designs/...`, so a home without internet still gets designs from
  inside the LAN.

A dynamic Ktor service only becomes worthwhile for on-demand
combinations we don't want to pre-build. It would need the JVM capture
path from `addon-server/PLAN.md` (option A, `remote-creation-jvm`).

### The server (`design-server`)

The server stays static in what it serves — a library generated at build time, nothing captured
per request — but adds what a static host can't: verification, a template browser and hot reload.

- **Library** (`:card-designs:generateCardDesigns`, a Robolectric test; output in
  `card-designs/build/card-designs/`). Its source cards are the curated `LibraryFixtures` (one per
  card type and shape the parity tests cover) and every card in the bundled demo dashboards,
  nested cards included. Each card's generation properties give a key; each key is captured for
  every theme style, light and dark, and for every other card style in each mode it has
  (mobile-app surface, the one the client fetches). Output:
  `manifest.json` and `blobs/` (written by `DesignPublisher`, the client's format), plus
  `catalog.json` (`DesignCatalog`: an example card and preview paths per design) and `previews/`
  (each design rendered bound to its example's values, and as published with its placeholders).
- **Client protocol.** `GET /designs/v1/manifest.json` (ETag = its SHA-256, `no-cache`, `304` on
  `If-None-Match`) and `GET /designs/v1/blobs/<sha256>.rc` (immutable). Only names the manifest
  lists are served. Loading verifies every blob's size and hash against the manifest and refuses a
  library that doesn't match. `ClientCompatibilityTest` runs the app's `DesignRepository` against
  it.
- **Template browser.** `/` lists designs with their previews, filtered by card type, theme,
  mode, a search over type and properties, and example-or-placeholder preview (by default, the
  first theme in light: each shape once). `/designs/<id>` shows a design's key and generation
  properties, both previews, its other themes, the bindings decoded from the template
  (`rc-player-edit`: names and types a client pushes), op count and bytes by operation, the
  mobile-app op-set validation, and its example card. `/api/designs[/<id>]` serves the same as
  JSON. Server-rendered HTML, no scripts.
- **Hot reload.** A regenerated library goes live when `manifest.json` changes (checked at most
  every 5 s); one that fails to load keeps the previous one serving.
- **Resolving a card.** `POST /api/cards/resolve` takes a card's config (with optional states,
  card style and mode) and returns the closest library design (same card type, slots, style, mode
  and the default variant), its blob and the values to push: the card's own slot values from its
  states, and the converter-only ones from the library example. Without the converters the server
  can't compute a card's shape, so when several shapes fit it returns the first with
  `exact: false`. It serves players that have no converters: UI Builder's Lovelace canvas and the
  embed.
- **Designing in UI Builder.** A design's page and `/schema` (paste a card's JSON) show the
  card's schema — slots and bindings with example values (`/api/designs/<id>/schema`,
  `POST /api/schema`) — and, with UI Builder bundled (`UI_BUILDER_BINARY`) or configured
  (`UI_BUILDER_URL`), seed a UI Builder design from it. A library design forks from its source
  (`/api/designs/<id>/source.uid`): the card rebuilt in `ha-cards` components on a card surface
  inside its `ha-cards/uber-theme`, all inside an `ha-cards/card-template` (which binds names and
  titles as a template capture does), entities named by slot (`light.s0`), so the export binds the
  template's `<domain>.s<N>.*` and `card.*` values; every binding is declared as a state variable. What that does, what the spike found and
  what a full pipeline still needs: [card-design-ui-builder.md](card-design-ui-builder.md).
- **Running.** `DESIGN_LIBRARY` (or the first argument), `PORT` (8080), `HOST`.
  `design-server/Dockerfile` bakes a library into a JRE image with UI Builder bundled.
  `.github/workflows/design-server.yml` generates the library, tests and smoke-tests the server,
  builds the image and uploads the library as an artifact; it deploys nothing yet (M4).

## Client

### `DesignRepository` (common interface, platform storage)

```
filesDir/terrazzo/designs/
  manifest.json          # last good manifest, verified signature
  manifest.meta.json     # etag, fetchedAt, lastFailureAt, failures
  blobs/<sha256>.rc      # content-addressed, shared by every instance
```

Designs are not per HA instance and contain no user data, so they are
not cleared on sign-out (unlike `OfflineCache`). Writes are atomic
(tmp + rename), as elsewhere.

**Refresh rules**

- Refresh in a **nightly background job** that runs only while the
  device is charging and on an unmetered network (WorkManager on
  Android). Designs change rarely, so the dashboard never fetches; it only
  reads the disk cache. The job is a no-op until the manifest is older
  than `ttlSeconds` (default 24h), and never runs two refreshes in
  parallel.
- Run one extra sync whenever the design choice changes (server URL,
  palette, card style, variant, author) and on start, so a setting takes
  effect without waiting for the night. Within the manifest's TTL it only
  downloads the chosen blobs the cache lacks, so it costs nothing when the
  cache is complete.
- Download only the blobs the choice matches (`DesignServerConfig.choice`),
  never the whole library; the manifest itself is always whole.
- Also refresh on app upgrade, and when a new surface is gated on (first
  widget installed, watch paired).
- Stale-while-revalidate: always render with what is on disk, swap in
  new designs on the next recapture, and never block a frame on the
  network.
- Back off on failures (1h, 4h, 24h). Respect `Retry-After`.
- Prefetch only the blobs for surfaces that are gated on, and only for
  card types that appear in cached dashboards plus the default variant.
  Delete blobs no manifest entry has referenced for 30 days.

**Fallback order for each card** (the `CardSource` chain):

1. A fresh remote design from the cache that matches the `DesignKey`
2. A stale cached design, if it isn't revoked and the schema is
   supported
3. An inbuilt design from APK assets, produced by the same pipeline at
   build time
4. The local converter, encoding live (today's path, still needed for
   path-2 cards and for anything above a row bucket)
5. `Unsupported`

Before a blob is accepted, it is decoded once with the player under the
target profile. A failed decode marks it bad locally, and the chain
falls through.

**`CardSource` changes.** Add a `DesignCardGenerator` (priority 50,
between the add-on at 0 and local at 100), and a new result kind
`CardRender.Template(bytes, slots, schema)`. The host then instantiates
and pushes: it builds the slot binding map from `slotsFor(card)` plus
the snapshot, using `cardSnapshotBindings` re-keyed to slots. This is
also when `CardSource` finally gets wired into the app; today only tests
use it.

### Per surface

- **Mobile app.** Fetches the manifest and keeps the `mobile-app` blobs.
  Plays templates in `CachedCardPreview` and pushes slot bindings. The
  cache key drops `dataSignature` for template cards, because they no
  longer re-encode.
- **Launcher.** Only while `liveWidgetIds()` is not empty. On each
  update, `TerrazzoWidgetProvider` instantiates the `launcher` template
  with current values and hands over the bytes. This needs spike S1. The
  ColorTheme vs no-ColorTheme profile pick stays as it is today.
- **Wear (widget and app).** The watch never calls the design server.
  The phone picks the `wear-widget` / `wear-app` blobs, checks them, and
  sends them over the Data Layer as content-addressed assets next to
  `PinnedCardSet`. The watch stores them in `WearOfflineStore`
  (`designs/<sha256>.rc`) and instantiates locally with the values it
  already receives. The watch app bundles its own inbuilt designs, so a
  watch that never syncs still renders. Watches that render standalone
  (LTE) can be added later. Built for `wear-widget` (see
  [Spike status](#spike-status));
  `wear-app` not yet.
- **Desktop, Wasm, TV.** Out of scope until those hosts play RC
  documents. Wasm would also need CORS on the static host.

## Build pipeline (one source for app, server and catalog)

A new module (`card-designs`, Android, Robolectric like `previews`)
with one task, `generateCardDesigns`:

1. Enumerate the target matrix from a checked-in
   `card-designs/matrix.json`: card types × variants × surfaces ×
   levels × shapes × buckets.
2. For each target, capture with the target's `Profile`, size mode,
   theme mode and the design snapshot (slot IDs, placeholder values).
3. Validate the result:
   - Every named variable in the document belongs to binding schema v1
     for its slot count. Nothing matching an entity ID, and no leftover
     literal text from the design snapshot.
   - The document decodes under that target's op set (level, profile,
     no `COLOR_THEME` on `widgetsProfile`).
   - Size tiers are present for Fixed targets.
   - **Snapshot invariance:** a second capture with different placeholder
     values, thresholds and ranges gives the same document apart from
     named-variable initial values (see
     [No decisions at capture time](#no-decisions-at-capture-time)).
   - A committed UI Builder reference exists for the target, and the
     comparison of this blob against it passes (milestone M2b). Until
     M2b exists the pipeline can build blobs, but it writes no manifest
     entries.
4. Write `blobs/<sha256>.rc` and `manifest.json`, then sign the
   manifest in CI with a repository secret. Local builds produce an
   unsigned dev manifest, which only debug builds accept.

The same task output goes into `app`, `wear` and later `tv` as
`assets/designs/`: these are the inbuilt designs. A release builds them
from the same commit, so the inbuilt library equals what the server
served at that commit. It ages from there until the next refresh.

**Tests.**

- Unit tests for each `slotsFor` mapper.
- A binding-schema validator test over the whole matrix.
- A round-trip test: template + instantiate(demo values) renders the
  same as today's local capture of the same card (screenshot diff
  within a tolerance). This is the guard that a template is equivalent
  to the live converter.
- A live-update test: push a new value, then assert the frame changes
  with no re-encode.

## Reference designs in UI Builder

Every design starts life as a **UI Builder reference**: a `.uid`
document in the `homeassistant-remotecompose` catalog that says what the
card should look like, before any converter code changes. The shipped
template is then checked against it. This is the same workflow
`adaptive-uid-pilot/` already runs for app screens (`.uid` references,
`references.json`, Reference / Diff / Actual evidence in the PR),
applied to cards.

**Components.** References are built from the typed components in
`ui-builder-catalog/` (`homeassistant-remotecompose/tile`, `button`,
`entity-row`, `heading`, the stacks). Those adapters need to grow in two
ways:

- Cover every v1 card type, gauge and glance included.
- Expose **binding-shaped properties**, not just scalars. A tile
  reference sets `state`, `is_on`, `numeric_state`, thresholds and ranges
  the same way the template's bindings receive them, and the component
  works out the look in-document. The reference then exercises the same
  rule as the template. Today's `Catalog*` wrappers fill icons and accents
  with fixed values; that becomes binding-driven.

**Layout.** References live next to the pipeline:

```
card-designs/
  matrix.json
  references.json                         # compose-ui-builder-references/v1, like adaptive-uid-pilot
  ui-builder/designs/<cardType>-<variant>-<surface>[-<shape>].uid
```

`references.json` lists the captures for each reference: surface frame
(dashboard width, launcher cells base ±1, Wear widget small/large, round
Wear), light/dark, and **binding fixtures** that cover each visual state:
on and off, each severity band, unavailable, a long name. Value-driven
visuals are designed and checked, not just the default frame.

**Flow for a new or changed design.**

1. Create or open the reference in UI Builder
   (`ui_builder_create_design`, or `ui_builder_branch_design` /
   `ui_builder_fork_design` from the `default` reference for a new
   variant like `modern`). Discussion happens in the design's comments,
   in the catalog.
2. Agree on it there (`ui_builder_record_decision`), then download and
   commit the `.uid` in the PR.
3. Implement or adjust the converter (variant), and link it with
   `ui_builder_set_implementation`.
4. CI renders each shipped blob, instantiated with each capture's
   binding fixtures, and compares it with the reference render
   (`ui_builder_compare_reference`, or the shared references publisher
   the pilot uses). While the components settle, a failing comparison
   is only reported on the PR, but the target still gets no manifest
   entry and no inbuilt asset. Once settled, it also fails the build.

**What may ship.** The manifest only lists a `(cardType, variant,
surface)` that has a committed reference, a linked implementation and a
passing comparison. A variant that exists only in UI Builder is a
proposal, not a design clients can receive.

## Catalog

Each manifest entry gets a `catalogId` and appears in the
`homeassistant-remotecompose` catalog, next to its UI Builder reference:

- Entries are **generated** from `matrix.json`, so nobody keeps
  `catalog.spec.json` in sync by hand: a `Designs` group with
  componentId `Design/<cardType>/<variant>/<surface>[/<bucket>]` and
  light/dark modes.
- Each preview renders **the shipped blob**, instantiated with
  `DemoData` values, not a fresh capture. What reviewers see is the byte
  stream clients download. The existing `RcDocumentCapture` `.rc` export
  publishes the template itself as the preview's `ir/<id>.rc`.
- Each surface uses its own frame: launcher cells from
  `CardPreviewMatrix` (base and ±1), round Wear for `wear-app`, and the
  Wear widget small/large shells for `wear-widget`.
- Captions list the slots, bindings and size range, so the catalog
  doubles as the binding reference.
- Each entry links to its UI Builder reference (`ui_builder_set_links`),
  so a reviewer can open the reference, the shipped render and the diff
  from one place.
- A design PR shows up as a catalog diff (`catalog_history_diff`), which
  becomes the review step for "change a design on the fly".

## Milestones

| | Deliverable | Depends on |
| --- | --- | --- |
| **S1** | Spike: instantiate a template by patching named-variable initial values; prove on the launcher (`DrawInstructions`) and a Glance Wear widget | — |
| **S2** | Spike: icon as a named bitmap (`s<N>.icon`), `theme.*` named colours in the Wrap profile, and in-document colour selection (state colour, gauge severity band) from bindings | — |
| **S3** | Spike: list op vs row-count buckets for entities/glance | — |
| R1 | Typed UI Builder components for the v1 cards, with binding-shaped properties | — |
| R2 | Reference `.uid` designs for tile, sensor, entity, button, gauge (`default` variant, each surface) with binding fixtures in `card-designs/references.json` | R1 |
| M1 | Binding schema v1 + `slotsFor` / `shapeOf` in `ha-model`; tile, sensor, entity, button and gauge converters move names, icons, colours and severity in-document; design-snapshot capture | S1, S2 |
| M2 | `card-designs` module: matrix, capture, validator (including snapshot invariance), blobs; inbuilt assets in `app` for debug builds only | M1 |
| M2b | CI comparison of shipped blobs against the UI Builder references, gating the manifest: only passing targets get entries | M2, R2 |
| M3 | Client: `DesignRepository` (disk cache, refresh rules, signature check), `DesignCardGenerator`, `CardRender.Template`, wire `CardSource` into the dashboard. Inbuilt assets ship in a release only after M2b | M2b |
| M4 | Static hosting on Pages (`/designs/v1/`), CI signing, kill switch | M2b |
| M5 | Launcher surface (instantiate in `TerrazzoWidgetProvider`) | M3, S1 |
| M6 | Wear: phone syncs blobs over the Data Layer, watch cache + inbuilt designs, wear-widget and wear-app targets | M3 |
| M7 | Catalog: generated `Designs` group rendering shipped blobs | M2 |
| M8 | Second variant (`modern`): branched from the `default` references in UI Builder, agreed there, implemented, compared; plus the variant picker in settings | M3, M7, M2b |
| M9 | More cards, each reference first: entities/glance buckets, alarm-panel, area, heading (with generation properties); the arc cards once their fraction is in-document | M1, R1 |

## Open questions

1. **Variant authority.** Does the user pick a variant per app, per
   surface, or per card? Proposal: per surface, with a per-card override
   later.
2. **Add-on overlap.** When the add-on renders with values (priority 0),
   does it beat a design template? Proposal: templates win for the card
   types they cover, so the add-on stays an offload for path-2 cards.
3. **Older levels.** Which real host needs level 6? If none, drop it
   from the matrix until one does.
4. **Who can publish.** Signing in CI means anyone who can merge to
   `main` can ship UI to every client. Is that acceptable, or do design
   releases need their own approval step?
5. **Telemetry.** Decided: the server counts design fetches per day by
   template and app release (a fixed User-Agent, no identifiers), and
   apps whose users opt in report bucketed render counts per template
   and source. Counts under 5 are never shown. See
   [template-usage-tracking.md](../followups/template-usage-tracking.md)
   and [the privacy policy](../privacy.md).
