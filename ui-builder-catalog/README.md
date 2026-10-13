# Home Assistant typed component catalog

This module wires real Home Assistant Compose UI components (`rc-components-ui`) into the typed
UI-builder adapter API. The native catalog runs on Android and is built during design-artifact
publication. Its renderer SDK still comes from the pinned compose-ui-builder release checkout.
Each definition publishes its own ID and properties/slots, and the registry invokes the actual
component with compiled calls.
A card adapter has one `data` property whose object codec names the card's own `*UiData` model and
decodes straight into it, so the registry calls `HaTile(data = value(tile.data), modifier)`.

| Component ID (`homeassistant-remotecompose/…`) | Real composable | Typed surface |
| --- | --- | --- |
| `heading` | `HaHeading` | `data: HaHeadingUiData` |
| `metric` | `HaMetric` | `label`, `value`, `unit`, `valueColor` |
| `tile` | `HaTile` | `data: HaTileUiData` |
| `button` | `HaButton` | `data: HaButtonUiData` |
| `toggle-button` | `HaToggleButton` | `data: HaButtonUiData` |
| `entity-row` | `HaEntityRow` | `data: HaEntityRowUiData` |
| `entities` | `HaEntities` | `data: HaEntitiesUiData` (`rows: List<HaEntityRowUiData>`) |
| `glance` | `HaGlance` | `data: HaGlanceUiData` (`cells: List<HaGlanceCellUiData>`) |
| `glance-cell` | `HaGlanceCell` | `data: HaGlanceCellUiData` |
| `markdown` | `HaMarkdown` | `data: HaMarkdownUiData` (`lines: List<String>`) |
| `unsupported` | `HaUnsupported` | `data: HaUnsupportedUiData` |
| `toggle-switch` | `HaToggleSwitch` | `initiallyOn: Boolean`, `activeAccent`/`inactiveAccent: Color`, `tapAction: HaAction` |
| `gauge` | `HaGauge` | `data: HaGaugeUiData`, `style: HaGaugeStyle` |
| `arc-dial` | `HaArcDial` | `data: HaArcDialUiData` (`modeChip: HaModeChipUiData?`), `style: HaArcDialStyle` |
| `history-graph` | `HaHistoryGraph` | `data: HaHistoryGraphUiData` |
| `statistics-graph` | `HaStatisticsGraph` | `data: HaStatisticsGraphUiData` (`chartType` enum) |
| `statistic` | `HaStatisticCard` | `data: HaStatisticCardUiData` |
| `sensor` | `HaSensorCard` | `data: HaSensorCardUiData` |
| `logbook` | `HaLogbook` | `data: HaLogbookUiData` |
| `calendar` | `HaCalendar` | `data: HaCalendarUiData` |
| `todo-list` | `HaTodoList` | `data: HaTodoListUiData` |
| `weather-forecast` | `HaWeatherForecast` | `data: HaWeatherForecastUiData`, `layout: HaCardLayout` |
| `media-control` | `HaMediaControl` | `data: HaMediaControlUiData`, `layout: HaCardLayout` |
| `alarm-panel` | `HaAlarmPanel` | `data: HaAlarmPanelUiData`, `layout: HaCardLayout` |
| `area` | `HaArea` | `data: HaAreaCardUiData` |
| `clock` | `HaClock` | `data: HaClockUiData` |
| `picture` | `HaPicture` | `data: HaPictureCardUiData` |
| `picture-glance` | `HaPictureGlance` | `data: HaPictureGlanceUiData` |
| `picture-elements` | `HaPictureElements` | `data: HaPictureElementsUiData` (`elements: List<HaPictureElementUiData>`) |
| `picture-entity` | `HaPictureEntity` | `data: HaPictureEntityUiData` |
| `bambu-print-status` | `HaBambuPrintStatus` | `data: HaBambuPrintStatusUiData` |
| `bambu-print-control` | `HaBambuPrintControl` | `data: HaBambuPrintControlUiData` |
| `bambu-ams` | `HaBambuAms` | `data: HaBambuAmsUiData` |
| `bambu-spool` | `HaBambuSpool` | `data: HaBambuSpoolDetailUiData` |
| `vertical-stack` | `HaVerticalStack` | `content` slot |
| `horizontal-stack` | `HaHorizontalStack` | `content` slot |
| `grid` | `HaGrid` | `content` slot |

Coverage: 37 of the 37 public Tier-2 composables in `rc-components-ui` (100%). Not counted, as
infrastructure rather than cards: `HaUiHost` (internal) and `HaEmbeddedPlayer` (the document
player every card draws through, whose `content` is a Remote Compose lambda). The insertion default
of every card is the plain data its `previews/…/Tier2CardPreviews.kt` preview draws
(`HomeAssistantDefaults`), so a card dropped on the canvas looks like its preview.

## Codec choices

Codecs live in `HomeAssistantCodecs.kt`. Every field of a model is a field of its `obj` codec,
named after the model's property, and defaults where the model's constructor does (plus `null`
for each nullable field and `""` for a `state`); the model's own `init` checks (an alarm panel's
non-empty, distinct statuses) still refuse a malformed value.

- **Icons (`ImageVector`).** `AdapterValueCodecs.materialIcon(allowed)` over a closed set of 40
  keys in the builder's Material icon-table spelling (`filled/lightbulb`, `filled/lockOpen`):
  every glyph `rc-converter`'s `HaIconMap` resolves an entity or `mdi:` name to, plus the ones
  the preview fixtures draw. The codec publishes exactly the `materialIcon` shape (so the editor
  offers its icon picker and an export writes `Icons.Filled.Lightbulb`) and resolves the key to
  the `Icons.Filled` glyph when it decodes. A key outside the set, or a glyph outside it as a
  default, is refused. The catalog depends on `material-icons-extended`; an independent Android
  host supplies that library like the Compose engines (the cards' own Remote Compose code
  draws Material glyphs too).
- **Colours (`Color`).** `AdapterValueCodecs.color(…)` decoding the `WireColor` to a `Color`:
  `#RRGGBB`, `#AARRGGBB` or `transparent`, exported as a `Color(0x…)` literal. Theme roles are
  refused (the colour export declares an empty token set): the cards draw with the Home Assistant
  Remote Compose theme, not a Material colour scheme, so a Material role would resolve against
  the wrong palette. The empty "component default" is refused too, since every card colour is
  required.
- **`HaAction`.** A `union` of every case: `none` (a singleton), `toggle`, `moreInfo`,
  `navigate`, `url`, `callService`, `alarmKey`, `alarmPin` and `alarmIntent`. `callService` keeps
  its `serviceData: JsonObject` as **parsed JSON text** (`AdapterValueCodecs.parsed`, format
  `json`, default `{}`): the text must be a JSON object, and an export writes
  `haServiceData("…")`, a one-line parser added beside `HaAction` in `rc-components`.
- **Enums.** `HaGaugeStyle`, `HaArcDialStyle`, `HaCardLayout`, `HaStatisticsChartType` and
  `HaHeadingStyle` are `enumOf` codecs whose wire values are the members' lower-camel names
  (`standard`, `stacked`, `wide`). The layout enum is a top-level property under the composable's
  own parameter name (`style` or `layout`).
- **Sealed UI data.** `HaModeChipUiData` (`static` / `toggle`) and `HaPictureElementUiData`
  (`stateIcon` / `stateLabel` / `serviceButton`) are unions of `obj` cases; nullable fields
  (`modeChip`, `centerIcon`, `position`, the Bambu buttons) are `nullable` codecs.

`HaToggleSwitch` takes `Color` parameters, an inline value class, so its JVM method is mangled
(`HaToggleSwitch-DTcfvLk`); the test's discovery list names that JVM name, and a changed signature
fails discovery rather than silently dropping the card.

No card composable takes a callback (taps are `HaAction` values the Remote Compose document
carries), so none declares an `event`. `toggle-switch.initiallyOn` binds to a Boolean state
variable; every other property is literal-only.

A design stores a card as nested wrappers; a tile, for example:

```json
"data": {"type": "object", "fields": {
  "name": {"type": "string", "value": "Kitchen"},
  "icon": {"type": "string", "value": "filled/lightbulb"},
  "accent": {"type": "object", "fields": {
    "activeAccent": {"type": "string", "value": "#FFFFC107"},
    "inactiveAccent": {"type": "string", "value": "#FF8F8F8F"},
    "isOn": {"type": "bool", "value": true}}},
  "tapAction": {"type": "union", "case": "toggle", "fields": {
    "entityId": {"type": "string", "value": "light.kitchen"}}}}}
```

### Designs saved against the first catalog

The first catalog adapted six cards through stand-in `Catalog*` wrappers with flat scalar
properties and one fixed icon and accent. Those wrappers are gone; their adapter ids are kept.
A node of one of those ids that has no `data` property is read through `HomeAssistantLegacy`: its
old literals (`title`, `name`, `state`, `showName`, the `rows` / `cells` lists of
`CatalogEntity(name, state)` objects, an empty title meaning no header) are laid over the card's
insertion default, so it still renders what it said. The published catalog no longer offers those
properties; editing such a node in the builder writes `data`.

Check out compose-ai-tools and compose-ui-builder beside this repository at the immutable revisions
in `.github/workflows/typed-catalog.yml`. The workflow validates the same source integration on
catalog/component/dependency changes. Existing public preview publication remains on released tools.

```sh
scripts/validate-typed-catalog.sh
```

The script accepts optional tools and builder checkout paths. It scans current compiled app classes,
generates only explicitly selected records, checks JSON readback and stale-record refusal, then
compares every actual Android Remote Compose card with its typed adapter pixel for pixel
(`CatalogRenderTest`, one case per card plus an omitted-property default and two first-catalog
designs) and checks children delivered to the real vertical stack, horizontal stack and grid slots.
`CatalogCodecTest` checks the stored shapes decode into the real models, every default round
trips, and malformed icons, colours, enum members, actions, service data and models are refused. A separate builder process reads the resulting pair through
`PublishedUiBuilderCatalog` with no app implementation on its classpath. PNG comparisons are saved
under `ui-builder-catalog/build/renders`; neither rendering test connects to a real device or server.

To generate the standard pair directly:

```sh
./gradlew -PtypedAdapterCatalog=true -PlocalBuilds=tools \
  -PlocalBuild.tools=../compose-ai-tools -PuiBuilderSource=../compose-ui-builder \
  :ui-builder-catalog:exportTypedCatalog
```

Output: `ui-builder-catalog/build/catalog/testDebugUnitTest/components.json` and `ui-builder.json`.
The export task runs the contract and native render tests against current compiled classes. Its
Robolectric Android 35 runtime is a pinned Gradle input, with test-time downloads disabled. Task-owned previous output is
replaced on regeneration; the underlying pair writer refuses arbitrary existing destinations.

To stage this in a catalog delivery generation, copy both files together and set the delivery
`catalog.json` fields `componentsFile` and `uiBuilderFile` to those relative paths. Do not combine
records from one source revision with policy from another. Keep the executable renderer from the
same revision with the catalog when installing it in a native host. CI uploads the generated pair
and comparison renders as review artifacts; this module does not push a delivery branch.

These app libraries currently target Android/JVM, not Wasm. Records explicitly declare `nativeOnly`,
so a browser editor must show its existing native placeholder until a compatible runtime is
packaged. The independent Android host below now proves executable delivery; a general IDE installation UI is still separate work. The existing sticker-sheet catalog and
its live preview bundles are not replaced by this opt-in proof. General event capabilities and
omitted-property default/export parity retain the limits documented in the authoring API.

The committed `evidence/` images show the direct native call and the typed adapter for the heading
and metric parity tests; every other card's pair (`native-<card>.png` / `typed-<card>.png`) is in the CI render
artifact. Regenerate them from `build/renders` after component changes; CI uploads current renders.

## Independent Android host and public publication

The initial catalog can be packaged without converting the app components into desktop replicas:

```sh
./gradlew -PtypedAdapterCatalog=true -PlocalBuilds=tools \
  -PlocalBuild.tools=../compose-ai-tools -PuiBuilderSource=../compose-ui-builder \
  :ui-builder-catalog:unpackAndroidCatalogBundle
```

The ZIP contains the standard pair, a saved `heading.uid` design (a heading, a slot child and a tile
in a horizontal stack), and the four app library JARs and AARs. Its provider is registered using JVM service metadata. The host supplies matching Android
Compose, Material icons and Remote Compose engines; Android resources in the AARs remain available for packaging.
The prototype heading/card/stack proof does not dynamically install arbitrary Android resources.

`:ui-builder-catalog-host` has no dependency on Home Assistant modules. It checks app classes are
absent from the host, loads them from the installed JARs, draws the real Remote Compose heading,
visible stack child and tile, changes the title, saves/reopens, and compares the reopened pixels. Each card is
keyed on its decoded model, so a changed title recaptures the Remote Compose document and edits
appear immediately. The generic renderer is UI Builder's existing document host and registry interpreter.

The independent host validation remains part of `typed-catalog.yml`. Production catalog metadata
and seeds now travel through `design-artifacts.yml` to the output repository; the older experimental
prerelease publisher is no longer a second workflow publication path. The Android JAR/AAR bundle
remains a CI validation artifact for installed native hosts.

## Catalog delivery

`design-artifacts.yml` publishes the typed metadata pair and the heading/stack/tile seed to
`homeassistant-remotecompose-out`. The renderer SDK remains a source dependency pinned to builder
`v3.111.0`; supply `-PcomposeUiBuilderDir=/path/to/compose-ui-builder` for catalog tasks.
`typed-catalog.yml` validates the source pin and native bundle rather than publishing a second
experimental prerelease.

Publication requires tools **2.56.1**, which contains #5935's JVM-default interface fix. The
validation workflow temporarily pins the source commit containing that fix; remove the tools
composite when the release is available. This catalog is native-only: its Android Remote Compose
capture and player remain real Android implementations, and no Wasm stand-in is published.
