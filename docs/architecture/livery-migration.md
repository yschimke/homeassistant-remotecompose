# Moving cards onto Livery

Status: plan. Supersedes the outline in livery's
[`docs/migrating-homeassistant-remotecompose.md`](https://github.com/yschimke/livery/blob/main/docs/migrating-homeassistant-remotecompose.md),
which predates the current code on both sides.

**Goal.** Three things change, everything else stays:

1. **Selection** — which template a card plays — goes through Livery's
   `TemplateRepository` / `TemplateSelector`, not `DesignRepository` / `DesignQuery` / `DeviceMatch`.
2. **Serving** — the Terrazzo design server (the `card_designs` add-on, ha.coo.ee, and the
   `custom_components/terrazzo` proxy in front of it) publishes a **Livery catalog**: contracts,
   designs, templates, themes, fonts and previews in one signed `manifest.json` per channel.
3. **Rendering** — every client plays a card through Livery (`LiveryHost` / `LiverySlot` in
   Compose, `livery-launcher-widgets`, `livery-wear-widgets`, the Swift package on iOS).

What stays in this repo: the HA connection, Lovelace and `CardSchema`, the converters, slot binding
(`CardDesignSlots`, `CardBindingRecipe`, `HaSharedValues`), tap resolution, design choices stored in
HA, the wall API, push, edit sessions and agent tools. Livery's rule holds: nothing about Home
Assistant goes into Livery. HA concepts become contracts, tags and action kinds, or adapters this
repo owns.

## Where things stand (Nov 2026)

- **Nothing depends on Livery yet.** The only trace is the BOM-mapping rule at
  `build.gradle.kts:166`. Livery is at 0.20.0, client `PROTOCOL_LEVEL = 8`.
- **The phone already renders downloaded templates first.** `CardDesigns.cardTemplateFor`
  (`app/.../dashboard/CardDesigns.kt:274`) picks a template for a card. The converter capture runs
  only when no design is cached. The route is:
  `DesignKey` → `DesignRepository.resolve*` → bytes, then `CachedCardPreview` → `HaRcComposePlayer`
  with live named values.

  This is Livery's model already, under other names, so the refactor is mostly a re-plumbing, not a
  rewrite.
- **Some code is dead or never fed:**
  - The `CardGenerator` / `CardSource` chain (`ha-client/.../CardGenerator.kt`, `AddonCardGenerator`,
    `RecordedCardGenerator`, `LocalCardGenerator`) only runs in tests.
  - `InbuiltDesigns.None` is the only value ever used.
  - `addon-server/` is a prototype whose `.rc` endpoint answers 501. design-server plus the wall
    API took over its job.
  - Delete these first, so the migration doesn't have to carry them.
- **The manifest today:**
  - `DesignManifest` has no contracts, reads or emits.
  - Its inline `signature` is never produced, and clients accept it unsigned.
  - One flat `designs[]` list holds what Livery splits into designs and templates.
  - Blobs are already at `blobs/<sha>.rc`, the same layout as Livery.
- **Versions are skewed:**

  | | ha-rc | livery |
  | --- | --- | --- |
  | Remote Compose | `1.0.0-alpha24` | `1.0.0-alpha23` |
  | rc-players | `3.5.3` | `3.6.0` |
  | Compose Multiplatform | `1.13.0-rc01` | `1.12.2` |

  Kotlin is `2.4.21` in both. Line these up before anything else (step 0).

## The mapping

### Contracts: one per card type

`ha-card/<cardType>@1` (`ha-card/tile@1`, `ha-card/thermostat@1`, …) is generated from each
converter, not written by hand. The converter already knows its slots and the named values it
binds.

| Contract part | From |
| --- | --- |
| `inputs` | every named value a converter's `designBindings` + `CardDesignSlots.slotBindings` can write, **under today's names** (`light.s0.is_on`, `card.name`, `s0.picture`), so the 36 `.uid` designs and every captured template stay valid without a recapture. Types come from the binding's `NamedValue` type; `maxLength` and ranges from the schema where known |
| `inputs` (images) | slot and entity pictures as `image` inputs (today's `rc-slot://` becomes `livery:value/<input>`), resolved by `ImageResolver`'s `app:` callback, which fetches entity pictures through the HA connection |
| `actions` | `s<N>.tap`, `s<N>.hold`, `s<N>.double_tap` and the input submits (message, input_text, select), all of kind **`app:ha-action`** with a `slot` arg. The handler resolves the card's own Lovelace action locally, as `CardTemplatePlayback.resolveTap` does today: a template never carries a service call |
| `examples` | the demo fixtures already used for previews (`scripts/fixtures/demo`) |
| `dataSource` | `App` |

**Shared values.** `HA:sun.sun.*` and `host.ambient` are not part of any card's contract. They form
a **vocabulary**, `ha/home@1`: values in the `HA` domain that the host supplies once for all cards.
A template declares which vocabularies it uses, and the publisher works that out from the document
itself. See [Runtime contracts, vocabularies and discovery](#runtime-contracts-vocabularies-and-discovery)
(L2).

**Structure-only fields.** The ~19 converters with a `dataSignature` keep it for the fields
`card-update-audit.md` justifies. A template for such a card is keyed by that structure (a `shape`
tag), and anything outside the templates' key space falls back to the converter, as now.

**The correctness gate.** A JVM test runs livery's `TemplateInspector` over **every blob in today's
library**. It asserts that each template's reads and emits are inside its generated contract. Until
that test is green, nothing ships.

### Designs, templates and tags

| ha-rc today | Livery |
| --- | --- |
| `DesignEntry` with `designId = library:<type>` | `Design(id="library:<type>", contract="ha-card/<type>@1", install=Always)` |
| `user:<id>` (skins, Card Studio, UI Builder saves) | `Design(install=OnRequest)`; installing a skin is `repository.install(id)` |
| `DesignKey.surface` (`mobile-app`, `launcher`, `wear-widget`, `wear-app`) | `Target.surface` (`in-app`, `launcher-widget`, `wear-widget`, `wear-app`) |
| `DesignKey.container` (`small` / `large`) | `Target.container` (`wear:small` / `wear:large`) |
| `DesignKey.rcApiLevel` | `Target.rcApiLevel`; op profiles in `Target.profiles` |
| `DesignKey.widthDp` | `Target.size` range; the query passes `widthDp` |
| `DesignKey.theme = baked:<palette>:<mode>` | `Target.theme = baked:<mode>` + tag `cardStyle=<palette>` |
| `DesignKey.theme = style:<id>:<mode>` (host-themed looks, `theme-kits.md`) | `theme = host` + a `ThemePack` per look (costs no download) |
| `variant`, `shape`, `schema`, `author` | `Design.variant`; template tags `shape`, `schema`; `Design.author` |
| `DeviceMatch` (integration, manufacturer, model, translationKey) | design tags `device.integration`, `device.manufacturer`, … The client asks **most-specific first** with `resolveFirst([model, manufacturer, integration, generic])`, which works with today's exact-match tags. No Livery change is needed unless that list gets long |
| `catalog.json`, previews | `Design.previews`, contract `examples` |
| `fonts/fonts.json`, card fonts | catalog `assets` (fonts with family, weight and SPDX licence); a template's `fonts` |

The app computes the query exactly as it computes the key today: `CardDesignTemplates.keyFor`
becomes `queryFor(card) = TemplateQuery(contract, caps, widthDp, designId?, variant?, tags = {shape,
cardStyle, schema})`. That runs HA code, which is fine, because the query is the app's side. Only
selection is Livery's.

### Choices

**Per-card choices stay in HA.** `.storage/terrazzo.design_selections` is per HA user and synced
across that user's devices. Device-local overrides are `CardDesignOverrides` (by card and by type).
Both just fill `TemplateQuery.designId` / `variant` / `cardStyle`.

Livery has no per-slot choice store, and doesn't need one for this. A design that is chosen but
can't play already falls back to the automatic one (`TemplateRepository.kt:318`).

Known weakness, unchanged by this plan: the key is a fingerprint of the card config, so editing a
card loses its choice. A stable key, such as the card path plus type, can come later.

## Exposing Terrazzo as a Livery catalog

### Building it

`card-designs` stops writing a `DesignManifest` and instead writes a **Livery source tree**:

```
catalog.json
contracts/ha-card.<type>.json
designs/<id>/design.json
designs/<id>/<target>.rc + .json
themes/
```

How the tree is produced:

- Converters are already written on rc-players' CMP creation API (`rccreation`). That is what
  `livery-capture-cmp` captures, so `LiveryCapture.capture(target, w, h) { … }` replaces
  `CardDesignTemplates.capture`, and the sidecar's reads and emits come from the document.
- `CheckedInCardDesigns` keeps its rule: the `.uid` design if one is checked in, else the converter
  capture.
- `livery build --inspect` refuses a tree whose templates read anything their contract doesn't
  declare.
- CI signs the manifest with the library key, which lives in a GitHub secret. The app embeds the
  public key.
- The app's bundled copy uses the `ee.schimke.composeai.livery.catalog` Gradle plugin, which writes
  the bundle into app assets and is read by `AndroidBundled.fromAssets`. This replaces
  `BuiltInCardDesigns` and `generateBuiltInCardDesigns`.

**Measure first.** The key space is card types × shapes × palettes × modes × surfaces × widths. The
step-1 converter (below) gives the real count of manifest entries.

Livery's open question on manifest size becomes our problem if that count is in the tens of
thousands. If it is, split by contract (L6). Moving palettes to theme packs, wherever a look is
host-themable, also shrinks it.

### Serving it

| Where | URL |
| --- | --- |
| design-server (ha.coo.ee and the add-on) | `/livery/v1/catalogs/ha-cards/channels/<main|pr-n>/manifest.json`, `manifest.sig.json`, `blobs/`, `assets/`, `previews/` |
| HA, through the integration | `/api/terrazzo/designs/livery/v1/...` (add `livery/v1/` to `design_route.py`'s allowed paths); HA auth as today |
| Discovery | `terrazzo/design_server` (WS) adds `{livery: {manifest, keys}}`, so the app finds the catalog on whichever HA it is connected to, with no setting |

- **Library channel.** The channel store has to change before it can carry a signed catalog. Today
  `LibraryChannels.install` re-encodes `manifest.json` with a server-stamped serial, TTL and expiry
  (`LibraryChannels.kt:170-180`). That breaks a detached signature over the manifest's exact bytes.
  Its `LIBRARY_FILE` allowlist also rejects `manifest.sig.json` and `assets/` on upload, and drops
  them from the mirror index.

  Step 3 therefore adds a Livery channel kind to the store:
  - The manifest is stored and served byte for byte. The serial, TTL and expiry are set by the
    publisher before signing, and the server checks the serial only to refuse a rollback.
  - `manifest.sig.json`, `assets/` and `previews/` are allowed in upload, `files.json` and
    mirroring.

  With that, the dedup upload and `LibraryMirror` work as before. A mirrored manifest keeps the
  upstream's signature, so the add-on serves a library it cannot alter. `LibraryEditPublisher`
  saves, which the server stamps today, then need the signing decision under Risks.
- **Personal catalog** (`ha-cards-user`, one per HA user or GitHub login). Today, private designs
  are folded into a per-caller manifest, which drops the signature. Instead:
  - Serve each user's `OnRequest` designs as a **separate catalog**.
  - Sign it with a key the add-on generates at install.
  - The app gets that public key through the authenticated HA WS (discovery above), so trust is
    anchored in the HA login, not a third party.
  - Limit the key to the `ha-card/` contract prefix. It can't sign anything else.
- **What doesn't fit a static catalog stays an HA endpoint, rebuilt on Livery:**
  - `POST /designs/v1/generate` (capture a key the manifest lacks). Clients stop calling it: a
    missing key means local converter fallback, and the library build is fixed to cover the key.
    Keep it only for old app versions.
  - `POST /api/cards/resolve` (wall panels, embed, desktop/wasm without a cache). It becomes:
    converter `queryFor` + data, then `SlotPlanner` over a server-side `TemplateRepository` reading
    the same catalog. With `livePush=false` it uses `TemplateInstantiator`.
  - `PlayerFolding`, watch faces, bitmap-font filters, glyph packs: per-player document transforms
    with no Livery equivalent. They stay as design-server routes keyed by template sha.
- **Old clients** keep reading `/designs/v1/manifest.json`. The library build emits it from the same
  tree for a deprecation window: two app releases, or until usage stats show no fetches.

## Rendering through Livery

### Phone, desktop and wasm (Compose)

There is one `LiveryHost` per HA server. Two repositories are involved: the library and the personal
catalog (see L1). Each Lovelace card is a slot:

```kotlin
LiverySlot(
  item = card,                        // keyed by card identity: a new card gets fresh player state
  data = haData(card, states),        // CardDesignSlots + designBindings, as JsonObject
  host = host,
  slotId = card.fingerprint,          // last answer cached in LiveryResources
  fallback = { ConverterCard(card) }, // today's CachedCardPreview converter path
) { planner.forAppData(queriesFor(card), data, extra = sharedValues) }
```

| Today | Livery |
| --- | --- |
| `CardDesignPreload` (holds the dashboard up to 1,500 ms) | `LiveryResources.startup` behind the splash screen; `LiveryPrepareAhead` for the lazy grid |
| `CardDesignSnapshot`, `warmCardTemplates`, `preloadForComposePlayer`, the decode `WeakHashMap` | `LiveryResources` (prepared documents per template, prepared off the main thread) |
| `CardDesignSync` WorkManager job | a WorkManager job calling `repository.refresh()`, keeping the nightly unmetered + charging constraints |
| `onNamedAction("ha")` → `LocalHaActionDispatcher` | `ActionRouter(mapOf("app:ha-action" to { plan, action, args -> dispatcher.run(card, args.slot, action) }))` |
| seeded `namedValues`, changed keys only | `LiveryTemplate` does the same (one `SnapshotStateMap` per card, changed keys only). New state arrives as a new plan via `forAppDataNow` (#126), drawn in the same frame |
| `HaRcComposePlayer` (`forDisplay` density / font-scale fold, cached) | `LiveryPlayer.Cmp` (the same `RcComposePlayer`) + `LiveryResources.preprocessor`. the `forDisplay` fold becomes Livery's `fold-display` transform (L4), run on device |
| `rc-player-ui` `RemoteComposeCard` on desktop/wasm | `livery-compose` (jvm, wasmJs) |

**Sliders and the control lock** play in the AndroidX View player (`slider-controls.md`). Livery's
View player draws nothing for Livery templates on alpha23, so the cards that need it stay on the
converter / View path until L5 is fixed. Route them with a `features` entry on the template, which
`PlayerCapabilities.features` refuses.

The first-frame rules (`live-card-updates.md`) map one to one onto Livery's "no visible redraws" and
"a slower first frame beats a blank one". `FirstFrameTest`-style tests move with the code.

### Widgets and Wear

| Surface | Today | With Livery |
| --- | --- | --- |
| Launcher | `LauncherDesigns` (key `<w>x<h>`) captured locally every time, values baked with `bakeValues` | `LiveryLauncherWidgetProvider`: `plan()` = `WidgetSlot.query(ha-card/<type>@1)` + HA data; `onTap` → the same `app:ha-action` handler. The library publishes `launcher-widget` templates, so launcher designs come from the server for the first time. The local converter capture stays as `fallback` |
| Wear widgets | `CardWidget`: synced server design → neutral template → Glance capture, baked | `LiveryWearWidget` per container (`wear:small` / `wear:large`) |
| Phone → watch | `MobileWearSyncManager` pushes `/wear/design/<sha>` + the `WearSlotDesigns` index | Keep the transport, change the payload: push the **subset manifest** (the watch's chosen templates) + blobs. The watch reads it as a `Bundled` source, and its slot index becomes `designId` per slot. Livery's planned Data Layer sync (L8) can replace this later |
| Wear app | `WearRemoteCard` | `LiverySlot`, as on the phone |

### Other clients

| Client | Plan |
| --- | --- |
| tv | Converter only today. It gets `LiverySlot` for free once the phone path lands |
| iOS | Swift `Livery` / `LiveryUI` + the `RcNativePlayer` adapter, replacing the `v1/apple/manifest.json` gateway feed |
| embed (`ha-card.js`) | `@yschimke/livery-web` is experimental, unpublished and pinned to an exact hash. Keep the embed's own manifest code, pointed at the Livery manifest, until livery has a TypeScript client (its roadmap item 4) |
| wall-client (C++) | No change. It talks to the wall API, whose resolve is rebuilt on Livery server-side |
| glasses, quest, demo-app | No RC cards; nothing to do |

## Runtime contracts, vocabularies and discovery

We lean on contracts, but a contract cannot be something a client must have at build time. The
embed, the wall API, iOS, an ESP32 panel, an agent's draft and a third-party Livery app all meet
contracts only at runtime. A card type added in a design server release must reach an app that has
never heard of it. So **contracts are data first**. `ContractDef` and generated Kotlin are a
convenience on top.

### Three ways a client learns a contract

1. **Compiled in.** harc's Kotlin clients use the generated `ContractDef`s, with typed handles.
2. **From the manifest.** Contracts already ship in `Manifest.contracts`. Nothing new is needed to
   receive them. What is new is that the client **compiles** each contract once per catalog
   revision, cached by the contract's hash (L1b):
   - an interned name table;
   - the type and limits per name;
   - list-name patterns.
3. **From the document.** A bare `.rc` that arrives without a catalog can still be understood:
   - It can carry its sidecar embedded as one named string, `livery.sidecar`, holding the contract
     ref, vocabularies, reads, emits and an optional recipe. This is how harc's
     `meta.binding_recipe` already works, generalised.
   - Failing that, the client infers the contract from the document: `TemplateInspector.facts` gives
     every name and type it reads and every action it emits.

   `DocumentAdmission` decodes such bytes once anyway. Its facts are kept by sha, so discovery adds
   no second decode.

### Keeping it cheap

- **Facts are computed at publish time, never at render time.** `TemplateEntry.reads` / `emits`
  already hold them. They gain `types` and `uses` (the vocabularies). At runtime a client only does
  set operations over interned ids.
- **Binding is incremental.** This is still the one gate, `ContractBinder`, extended rather than
  forked (L1b).
  - A `BindSession(compiled, data)` checks the key set and every value once, when a card is first
    planned.
  - After that, each state change re-checks only the keys that changed. That is O(changed), not
    O(contract), which matters for a dashboard getting `state_changed` events many times a second.
  - `forAppDataNow` feeds it.
- **Vocabulary values are bound once per host, not once per card.** They are pushed only to the
  slots whose entry `uses` them, and only under the names that entry reads.

### Vocabularies: reads outside the card's contract

A **vocabulary** is a contract with a named-value `domain` other than `USER` and
`dataSource: Host`. Examples:

- `ha/home@1` is domain `HA`, holding `sun.sun.elevation`, `sun.sun.next_rising` and the rest.
- Livery itself could own generic ones such as `livery/clock@1` and `livery/device@1` (ambient,
  battery, network).

A vocabulary is the same `Contract` type with one extra field, so the binder, evolution rules
(`since`, minors) and conformance fixtures all apply unchanged (L2):

- A template entry gains `uses: ["ha/home@1"]`. **The publisher computes it** by grouping the
  document's non-`USER` reads by domain and matching each group against known vocabularies. An
  unknown domain or name is refused, as an undeclared `USER` read is today.
- `PlayerCapabilities` gains `provides: ["ha/home@1", …]`.
- Selection treats `uses ⊄ provides` like a missing feature: the template is ineligible, and the
  next one in order is chosen (§5).

### Discovery: "this garage card is a tile, plus the sun"

`TemplateInspector.conformance(facts, contracts, vocabularies)` returns three things:

- which contracts the document **implements**: its `USER` reads are inside the contract's inputs,
  with matching types, and its emits are inside the contract's actions;
- the leftover reads grouped by vocabulary, for example `ha/home@1: [sun.sun.elevation]`;
- anything unexplained.

For a garage-door design that draws a standard tile but shades its door by the sun's elevation, the
answer is: implements `ha-card/tile@1`, uses `ha/home@1` (`sun.sun.elevation`).

It runs in four places:

| Where | What it does |
| --- | --- |
| `livery import` / `livery build` | Writes `contract` and `uses` into the sidecar, so an author never has to declare them. UI Builder saves and agent drafts get the same treatment |
| `livery-mcp` `validate_template` | Tells an agent which contract its draft implements and what else it reads |
| On the device, for a bare document | The same answer, from the cached facts |
| Selection | A host that provides `ha/home@1` gets the garage design. One that doesn't (the embed on a foreign page, a third-party Livery client) gets the plain tile design. Never a blank card, never a card computing with NaN |

**One document can implement several contracts.** A minimal tile implements `ha-card/tile@1` and,
by inclusion, a generic `livery/status-tile@1`. The publisher can list both in `implements`, so a
non-HA Livery app can play an HA design with its own data. Selection still binds one contract per
slot.

### Contracts generated from a card, at runtime

harc's slot names are positional with the domain inside the name (`light.s0.is_on`,
`cover.s1.position`). A card's shape fixes its domain sequence, so for any one template the names
are fixed. Across a card type, though, they aren't.

Two ways to declare them:

- **Expand.** Generate every domain × slot × field up to a maximum. That is finite, but hundreds of
  inputs per contract.
- **Pattern** (recommended, L2b). Add `ListSpec.names`, a flattening pattern that defaults to
  `{list}.{i}.{field}`. harc's `slots` list would use `{domain}.s{i}.{field}`, where `domain` is a
  key field of the item, and items could be sparse.

  Binding, inspection and evolution all go through the one pattern. The rest of Livery's flat-list
  model is unchanged, and a feed that wants `items.0.title` keeps the default.

Either way the contract is plain data, so the design server can generate one for a new card type at
release time, and a client built before it plays it.

**The binding recipe becomes Livery's.** `CardBindingRecipe` is a small, host-evaluated expression
language that maps a source dataset to a contract's inputs. Today it has a Kotlin copy and a
hand-written JS copy in `embed/ha-card.js`.

As `livery-recipes` (L3) it becomes generic and pinned by conformance across the Kotlin, Swift and
TypeScript ports:

- the expression ops `literal`, `path`, `number`, `first`, `ref` and `fn`, with a fixed function
  set;
- the recipe carried in the sidecar or in `livery.sidecar`.

harc keeps only its source adapter: `config.*`, `state[i]` and `attr[i].name` become paths into a
JSON object it builds from the card and the states. A client with no converter then binds any card
from the recipe alone.

## Multi-device targeting and transforms

harc already turns one design into many devices' documents, in five separate places, each with its
own rules:

| Code | Lines | Transform |
| --- | --- | --- |
| `PlayerFolding` | 320 | Bakes density and font scale, freezes host values, adapts to a player profile, optimises, sets a screen canvas (ESP32, wall panels) |
| `CardDesignTemplates.forDisplay` | — | Density and font-scale fold on the phone |
| `LauncherDesigns` | 431 | Recaptures for the launcher; `bakeValues` |
| `WearWidgetTemplate` + `CardDesignWearTemplate` | 546 | Phone template to a Glance Wear container |
| `GlyphRanges` + `WidgetFilters` | 577 | Font subsetting to glyph packs and bitmap fonts |
| `/designs/v1/generate` | — | Capture a key the manifest lacks |

Livery should own this as one mechanism (L4).

**Devices, described.** `PlayerCapabilities` and `Target` gain a few fields:

- `display`: density, font scale, shape (rect or round), colour depth (mono, e-ink, full) and an
  optional fixed canvas;
- `player`: `cmp`, `androidx-view`, `ios-native`, `js` or `micro`, so known player quirks
  (`RcPlayerFeature`) are data rather than code;
- `profiles`, which already exists.

A wall panel, an ESP32, a Glance tile and a phone are then just four capability sets.

**Transforms, named and versioned.** A transform is a deterministic `bytes → bytes` step over
rc-player-edit operations, with a stable id:

- `fold-display@1`
- `adapt-profile:<p>@1`
- `wear-container:<c>@1`
- `bake-theme:<mode>@1`
- `glyph-subset@1`
- `canvas@1`

Each transform also declares how it changes a template's facts. For example, baking `host.ambient`
removes that read, and `wear-container` adds the `wear.container.*` host names. The derived
template's reads, emits and uses are then known without decoding it.

**Derivations in the manifest.** A derivation says: "target B can be made from a target-A template
by chain T". Selection considers native templates first, then derivable ones, and returns
`Derived(source, chain)`. The client then does one of three things:

1. **Derives on device**, if it links the transforms. Android and the JVM can.
2. **Fetches** `derive/<chain>/<sourceSha>` from the catalog's server. The output is content
   addressed and cached by the server forever, because it is deterministic.
3. **Reads it from the catalog**, if the publisher **materialised** it at build time. Do that for
   hot targets, for example every phone design for the two Wear containers.

**Trust.** A derived document's sha differs from its source's, so its signature can't be the
source's. Two options:

- A client that derives on device trusts the result as far as it trusts the source and its own
  transforms.
- A server answers with a **derivation receipt**, `{source sha, chain, output sha}`, signed by a key
  limited to derivations. The client can verify by re-deriving whenever it can.

Either way, a malicious server can't make a document read or emit more than its source does,
because transforms declare their fact changes and the inspector checks them.

**What this replaces in harc:**

- `PlayerFolding` becomes `fold-display` + `adapt-profile` + `canvas`.
- `WearWidgetTemplate` becomes `wear-container`.
- The launcher's local recapture becomes `fold-display`, plus the existing instantiate step.
- The glyph and bitmap-font filters become `glyph-subset` on catalog font assets.
- `/generate` is gone for every case that is "same design, other device". Only a design that was
  never captured in a shape still needs the converter.

## What moves into Livery, and what harc loses

Each item below is a Livery PR, held to Livery's rules: conformance fixtures and the Swift port in
the same PR, `PROTOCOL.md` changed with the code, and site pages. Nothing in it names Home
Assistant.

| | Livery change | Needed by | Replaces in harc |
| --- | --- | --- | --- |
| **L1** | **Several catalogs in one host**: `LayeredRepository(personal, library, pr-channel)`. A `designId` resolves in its own source first, and automatic selection takes the union. Pure selection; conformance | step 4 | the per-caller manifest folding in `UserDesignRoutes`, which also drops the signature |
| **L1b** | **Compiled contracts and incremental binding** (`CompiledContract`, `BindSession`) | step 5 | — (performance) |
| **L2** | **Vocabularies**: `Contract.domain`, `dataSource: Host`, `TemplateEntry.uses`, `PlayerCapabilities.provides`, an eligibility rule, publisher inference | step 1 | `HaSharedValues` keeps only its values; the plumbing goes |
| L2b | `ListSpec.names` flattening pattern, sparse items | step 1 | hand-expanded slot names |
| **L2c** | **Discovery**: `TemplateInspector.conformance`, `implements`, the embedded `livery.sidecar` | step 2 | `meta.binding_recipe`, and the embed's own document sniffing |
| L3 | **`livery-recipes`**: data-to-inputs expressions, Kotlin, Swift and TS | step 7 | `CardBindingRecipe` (317) and its JS copy in `ha-card.js` |
| **L4** | **Devices and transforms**: `display` and `player` capabilities, named transforms, derivations, the `derive/` route, receipts | steps 6–7 | `PlayerFolding` (320), `forDisplay`, `WearWidgetTemplate` + `CardDesignWearTemplate` (546), launcher recapture, `GlyphRanges` + `WidgetFilters` (577), most of `/generate` |
| L5 | AndroidX View player plays Livery templates | sliders, lock | — |
| L6 | Manifest split by contract | only if the step-1 count is too big | — |
| L7 | `livery-server` catalog channels: upload-what-changed, mirror, server-side `derive/`, fetch counting | step 8 | `LibraryChannels` + `LibraryMirror` (656), `UsageStats` (304) |
| L8 | Phone → watch blob sync over the Data Layer | step 6, optional | the design half of `MobileWearSyncManager` |
| L9 | Agent sessions and relay in `livery-server` / `livery-mcp`, with app gates as validators | after step 8 | `AgentRelay*` (~1,300), `AgentRelayClient` (411) |

**Also deleted once the steps land:** `DesignRepository`, `DesignQuery`, `DeviceMatch`,
`CardDesign`'s wire types, `BundledDesigns`, `SkinStore`, `Skins`, `CardDesignSync`,
`CardDesignSnapshot`, most of `CardDesigns` (~2,300 lines), `CardDocumentCache` and most of
`HaRcComposePlayer` / `CachedCardPreview` (~1,000 lines, replaced by `LiveryResources` /
`LiverySlot`), plus the dead code from step 0 (`CardGenerator` chain and `addon-server`, ~1,700
lines).

**Estimate: about 10,000 lines of Kotlin leave harc.** In exchange it gains generated contracts and
an HA vocabulary.

**Not needed** (the old plan said "to add"):

- A device tag matcher: `resolveFirst` with most-specific queries covers it.
- A per-slot choice store: choices stay in HA.

## Order

Each step is one PR (or a short series) that keeps every surface working, with an
`designs.engine = legacy | livery` setting (a debug setting, then a remote flag) from step 4 on.

0. **Clean up and align.**
   - Delete the `CardGenerator` chain, `InbuiltDesigns` and `addon-server/`. Retire `CLIENT_PLAN.md`
     and `PLAN.md` into this doc's history.
   - Mark `card-design-server.md` and `server-owned-card-designs.md` superseded.
   - Align Remote Compose, rc-players and CMP versions with livery (in whichever direction is
     newer). Add `livery-bom`.
1. **Contracts** (needs L2, L2b).
   - Generate the `ha/home@1` vocabulary from `HaSharedValues`.
   - Generate `ha-card/<type>@1` from the converters.
   - Add the inspector test over every blob in today's library.
   - Add a one-way converter `DesignManifest` → Livery `Manifest`. It is used only to count entries
     (the L6 decision) and as the parity oracle in step 4.
2. **Catalog build** (needs L2c). `card-designs` writes the Livery source tree; `livery build --inspect`; CI
   signing; the bundled copy through the Gradle plugin. Publish it beside today's manifest on the
   same `main` / `pr-<n>` channels.
3. **Serve it.** Add the `/livery/v1/...` routes, the integration proxy path and WS discovery with
   keys. Add the personal catalog with the add-on's own key.
4. **Phone selection** (needs L1).
   - Move `cardTemplateFor` onto `TemplateRepository.resolveFirst`. Skins become `install`;
     overrides and HA selections become `designId`.
   - **Parity test:** for every demo dashboard card × surface × theme, both engines pick the same
     sha.
   - Ship on the flag, then make it the default.
   - Then delete `DesignRepository`, `DesignQuery`, `DesignTags`, `DeviceMatch`, `SkinStore`,
     `CardDesignSync` and `CardDesignSnapshot`.
5. **Phone rendering.** Dashboard cards become `LiverySlot`s under `LiveryHost`. The converter is the
   fallback, taps go through `ActionRouter`, entity pictures through `ImageResolver`. Port the
   first-frame tests and the app benchmarks (`app-benchmark` cold start / fling, against today's
   numbers).
6. **Widgets and Wear** (needs L4).
   - Materialise Wear container derivations at build time.
   - Fold the launcher on device.
   - Delete `PlayerFolding`, `WearWidgetTemplate`, `CardDesignWearTemplate` and `LauncherDesigns`' recapture.

   Then: Launcher onto `livery-launcher-widgets`, with launcher templates in the
   catalog. Wear widgets onto `livery-wear-widgets`. The phone → watch payload becomes a subset
   manifest.
7. **Other clients** (needs L3 for the embed; L4 for the wall API's `derive/`). desktop/wasm (`app-ui`), tv, server-side `/api/cards/resolve`, then iOS. The
   embed changes last, when livery's TypeScript client exists.
8. **Retire the old protocol.** Stop emitting `DesignManifest` after the deprecation window. Remove
   `/designs/v1/generate`. Optionally move channels and mirroring onto `livery-server` (L7).

Steps 1–3 are server-side only and can land without touching any client. Steps 4 and 5 are where
users see a change, and both sit behind the flag.

## Risks

- **Contract completeness.** One undeclared named value makes `livery build` refuse a template, or
  makes `ContractBinder` refuse a card's data at runtime, and the card falls back to the converter.
  Two things guard against it: the step-1 inspector test over the real library, and a `Fallback`
  event counter in analytics.
- **Data volume.** `ContractBinder` now runs on every state change for every visible card. It
  should be cheap, since it is flat maps, but measure it in the fling benchmark before step 5
  becomes the default.
- **Two players during the transition.** The View player for sliders, and the CMP player for
  everything else via Livery.
- **Catalog trust.** Today's clients accept unsigned manifests. With Livery the library must be
  signed, so a channel CI never signed (a hand-edited `main` via `LibraryEditPublisher`) needs the
  server to sign with a key the app trusts. Alternatively, UI Builder edits go through the PR import
  only. Decide this in step 3.
- **Protocol compatibility for vocabularies and derivations.**
  - An older Livery client ignores `uses`. It could select a template that reads `HA:` values it
    never pushes, and that template would then draw with the document's own defaults. For
    `ha/home@1` that is safe by design, since every shared value defaults to its daytime value.
  - In general it isn't, so L2 must make such entries ineligible below the new protocol level: a
    per-entry `requiresLevel`, or `minClient` in strict mode, mirroring how `requiresMinor` works.
  - Derivations are additive. An old client sees only native templates.
- **Discovery is advisory.** `implements` is computed from facts, but a document that structurally
  fits a contract can still mean something else by a name. The publisher writes what it inferred,
  and a reviewer or `livery check` sees it in the diff. Inference never widens trust: a document
  still plays only what its signer's key may sign.
