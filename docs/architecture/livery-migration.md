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
  - The `CardGenerator` / `CardSource` chain (`ha-model/.../model/CardGenerator.kt`, `AddonCardGenerator`,
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
| `lists` | `slots`: a **reference list** into `ha/entity@1` (see [Slots are references to entities](#slots-are-references-to-entities)). A template reads `slots.0.is_on`, and the host pins slot 0 to a real entity when the card plays |
| `inputs` | the card-scoped values only: what a converter's `designBindings` (or its recipe) computes from the card config (`card.name`, `card.min`, `card.range`), plus a slot's `name:` override. Types come from the binding's `NamedValue` type; `maxLength` and ranges from the schema where known |
| `inputs` (images) | slot and entity pictures as `image` inputs (today's `rc-slot://` becomes `livery:value/<input>`), resolved by `ImageResolver`'s `app:` callback, which fetches entity pictures through the HA connection |
| `actions` | `s<N>.tap`, `s<N>.hold`, `s<N>.double_tap` and the input submits (message, input_text, select), all of kind **`app:ha-action`** with a `slot` arg. The handler resolves the card's own Lovelace action locally, as `CardTemplatePlayback.resolveTap` does today: a template never carries a service call |
| `examples` | the demo fixtures already used for previews (`scripts/fixtures/demo`) |
| `dataSource` | `App` |

**Shared values.** `HA:sun.sun.*` is no longer special. It is an entity in the same `HA:` store
that slots point into, one of the vocabulary's few **fixed entities**: those any design may read
without a slot. `host.ambient` belongs to a small Livery-owned host vocabulary. The publisher works
out which vocabularies a template uses from the document itself. See
[Runtime contracts, vocabularies and discovery](#runtime-contracts-vocabularies-and-discovery) (L2).

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
across that user's devices. Device-local overrides are `CardDesignOverrides` (`ha-model`, by card and by type).
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
   - reference lists and the vocabularies they point into.
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
- **Entity values are computed once per entity, not once per card.** A `state_changed` event
  updates one entry in the host's `HA:` store, and every card, widget and watch slot reading that
  entity sees it. Only the names a template reads are pushed into its player.

### Vocabularies: reads outside the card's contract

A **vocabulary** is a contract with a named-value `domain` other than `USER` and
`dataSource: Host`. There are two kinds:

- **Keyed.** `ha/entity@1` is domain `HA`, and its values are keyed by an id: the fields `state`,
  `is_on`, `state_int`, `status`, `last_changed`, `attributes.<key>` of any entity, named
  `HA:<entity_id>.<field>`.
  - A design never names an entity id. It reaches entities through its slots (below), plus the
    vocabulary's **fixed entities**, the ids every Home Assistant has: `sun.sun` today.
  - So the garage card's `HA:sun.sun.elevation` is a read of a fixed entity, not a special shared
    value.
- **Plain.** Values with fixed names, such as a Livery-owned `livery/device@1` (`host.ambient`,
  battery, network) or `livery/clock@1`.

A vocabulary is the same `Contract` type with one extra field, so the binder, evolution rules
(`since`, minors) and conformance fixtures all apply unchanged (L2):

- A template entry gains `uses: ["ha/entity@1"]`. **The publisher computes it** by grouping the
  document's non-`USER` reads by domain and matching each group against known vocabularies. An
  unknown domain or name is refused, as an undeclared `USER` read is today.
- `PlayerCapabilities` gains `provides: ["ha/entity@1", …]`.
- Selection treats `uses ⊄ provides` like a missing feature: the template is ineligible, and the
  next one in order is chosen (§5).

### Discovery: "this garage card is a tile, plus the sun"

`TemplateInspector.conformance(facts, contracts, vocabularies)` returns three things:

- which contracts the document **implements**: its `USER` reads are inside the contract's inputs,
  with matching types, and its emits are inside the contract's actions;
- the leftover reads grouped by vocabulary, for example `ha/entity@1: [sun.sun.elevation]`;
- anything unexplained.

For a garage-door design that draws a standard tile but shades its door by the sun's elevation, the
answer is: implements `ha-card/tile@1` (its slot 0 is the door), uses `ha/entity@1` (the fixed
entity `sun.sun`, field `elevation`).

It runs in four places:

| Where | What it does |
| --- | --- |
| `livery import` / `livery build` | Writes `contract` and `uses` into the sidecar, so an author never has to declare them. UI Builder saves and agent drafts get the same treatment |
| `livery-mcp` `validate_template` | Tells an agent which contract its draft implements and what else it reads |
| On the device, for a bare document | The same answer, from the cached facts |
| Selection | A host that provides `ha/entity@1` gets the garage design. One that doesn't (the embed on a foreign page, a third-party Livery client) gets the plain tile design. Never a blank card, never a card computing with NaN |

**One document can implement several contracts.** A minimal tile implements `ha-card/tile@1` and,
by inclusion, a generic `livery/status-tile@1`. The publisher can list both in `implements`, so a
non-HA Livery app can play an HA design with its own data. Selection still binds one contract per
slot.

### Slots are references to entities

**Where `light.s0.is_on` comes from.** It is not from Lovelace: a card config only says
`entity: light.kitchen`. It is harc's own naming, built in two layers:

1. `LiveValues` (`rc-components/.../LiveValues.kt`) names every bound value `<entityId>.<field>` in
   the RC `USER` domain. So a converter capture reads `light.kitchen.is_on`.
2. `CardDesignSlots` makes a template reusable by capturing against fake entity ids: the domain is
   kept, and the object id becomes the slot index. So `light.s0.is_on` means "the card's first
   entity, which is a light, is on". At play time `slotBindings` computes every field of every
   slot for every card and pushes it under the slot names.

Options for declaring that in a contract:

| | What a template reads | Livery change | Cost |
| --- | --- | --- | --- |
| Expand | `light.s0.is_on` | none | hundreds of inputs per contract (domain × slot × field) |
| Naming pattern | `light.s0.is_on` | `ListSpec.names = "{domain}.s{i}.{field}"` | a protocol addition only harc uses |
| Neutral slots | `slots.0.is_on`; the domain becomes the `shape` tag | none | recapture (build output) and migrate the `.uid` designs |
| **References** (chosen) | `slots.0.is_on`, pinned at play time to `HA:light.kitchen.is_on` | reference lists (L2b) | the most Livery work, and the most harc code removed |

**References.** The contract's `slots` list is `ListSpec(ref = "ha/entity@1")`: each item is
**an entity id, not a set of values**.

- **Binding.** The host binds the card's slot map (`0 → light.kitchen`, `1 → cover.garage`, from
  `slotEntries`), checked by `ContractBinder` like any other data: ids in the vocabulary's id
  format, at most `max` items.
- **Values come from one store per home.** Entity-derived fields live in the host's `HA:` store,
  computed once per entity from the `state_changed` stream. Per-card `slotBindings` goes away.
  Only card-scoped values (`card.*`, a slot's `name:` override) stay card inputs.
- **Live players are fed from the store.** In-app, desktop and Wear-app players keep **one
  prepared document per template**. The document still reads `slots.0.is_on`.
  - Each card has a reference table (`slots.0 → light.kitchen`). Through it, its named-value map is
    fed from the store: on a change, only the cards referencing that entity are written, and only
    the names their template reads.
  - The player needs no aliasing support, and values are seeded before the first frame, as now.
  - Rewriting the bytes per card would give every card its own document and break
    `LiveryResources`' sharing, and with it the no-visible-redraws rule.
- **Instantiate paths rewrite.** The launcher, Wear widgets, the wall API and server resolve already
  produce one document per instance. They rewrite the named-variable table to
  `HA:<entity_id>.<field>` (or bake the values), at no extra cost. A wall panel's long-poll can then
  be per entity instead of per card.
- **Trust.** A published template never names a real entity id: the publisher refuses an `HA:`
  read that is neither a slot field nor a fixed entity. Only the host decides what a slot points at,
  so a design can't read an arbitrary entity in the home.
- **One server per document.** A document is pinned by one host, for one server's entities, when it
  plays. A dashboard mixing servers has separate cards, each played by its own server's host, so no
  document needs a server-qualified domain. The app keeps one store per server.
- **Old templates.** The reference list declares today's flattened names as a legacy mapping
  (`"{domain}.s{i}.{field}"`), used only to read templates captured before the change. Published
  `user:` skins and old library entries keep playing, with no flag day; the build recaptures the
  library with the new names.

The contract is plain data either way, so the design server can generate one for a new card type at
release time, and a client built before it plays it. Livery's side is
[its reference-list proposal](https://github.com/yschimke/livery/blob/main/docs/REFERENCE_LISTS.md),
which also covers vocabularies (L2) and discovery (L2c).

**Two things to confirm in step 1:**
- the CMP and AndroidX View players read host-pushed values in a non-`USER` domain. Only fixed-entity
  reads (`HA:sun.sun.*`) need it; `HaSharedValues` relies on it already;
- rc-player-edit has, or gains, a pass that renames named variables, beside `hardcodeNamedValues`.
  Only the per-entity wall API needs it; the other instantiate paths bake values.

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

**Trust: no server-signed receipts for now.** A derived document's sha differs from its source's,
so its signature can't be the source's. Derivations happen only in places that need no new kind of
signature:

- **On the device**, for clients that link the transforms (Android, Wear, the JVM). The result is
  trusted as far as the source and the client's own transforms are.
- **At build time**, for hot targets (the Wear containers, common folds). The catalog signature
  covers them like any other entry.
- **Per instance through the person's own Home Assistant**, for wall panels and ESP32. That path
  is already trusted, and those players check no signatures.
- iOS and the web embed get built-time derivations until the Swift and TS ports have the
  transforms.

The risk that matters needs no receipt. A derived document must not read, emit or use more than its
source, and every transform declares how it changes those facts. So a client checks the derived
bytes with `TemplateInspector` against the source's facts plus the declared change: one decode,
which admission already does. A receipt would only prove the pixels weren't altered. That costs a
key class, a protocol field and conformance fixtures with no consumer yet. Add receipts when a
third-party server derives for a client that verifies them.

**What this replaces in harc:**

- `PlayerFolding` becomes `fold-display` + `adapt-profile` + `canvas`.
- `WearWidgetTemplate` becomes `wear-container`.
- The launcher's local recapture becomes `fold-display`, plus the existing instantiate step.
- The glyph and bitmap-font filters become `glyph-subset` on catalog font assets.
- `/generate` is gone for every case that is "same design, other device". Only a design that was
  never captured in a shape still needs the converter.

## Agent and UI Builder flows as optional Livery modules

The agent and UI Builder flows are a large part of harc that knows little about Home Assistant. Look
at what [#1694](https://github.com/yschimke/homeassistant-remotecompose/pull/1694) touched:

- keeping **Use it** in reach;
- naming the agent ("Gemini");
- naming a comment's target plainly.

That is a co-editing UI, and only the target's name came from Home Assistant. Underneath it sits
the relay ([agent-sessions.md](agent-sessions.md)). The person and an agent both connect out to a
rendezvous, and nothing depends on an inbound connection. On top of it:

- lasting links and short codes;
- presence, a conversation with comments pinned to a target, drafts checked by a gate;
- accept, reject and undo;
- the agent seeing a render of what the person sees.

**None of that is HA-specific.** Confetti needs exactly the same loop to co-design its session
and speaker cards with an agent, and so does any Livery app with a catalog. So it moves to Livery
as **optional modules**, apart from the client core.

### What moves

| Module | Platform | Takes from harc | Lines (main) |
| --- | --- | --- | --- |
| `livery-sessions-core` | KMP, transport-free (like `livery-mcp-core`) | Every session's model and rules, with a clock: links, codes and the failed-code budget, session, revision, drafts, conversation (messages, comments with a `target`, decisions), presence, expiry, persistence (`SessionStore`, today's `AgentSessionFile`). A `DraftGate<T>` per draft **kind**, and a `TargetLabeler` the app supplies | `AgentRelay`, `AgentLinks`, `AgentSessionFile` (~1,030) |
| `livery-sessions-server` | JVM, Ktor, in `livery-server` | The person's HTTPS routes (long-polls of at most 25 s, no SSE) and the agent's MCP endpoint: `connect`, `await_request`, `request_access`, `get_session`, `set_status`, `send_message`, `submit_draft`, `put_asset`, `await_feedback`, `get_render`, served through `livery-mcp-core` | `AgentRelayRoutes`, `AgentRelayMcp`, `AgentDesignMcp` (~1,060) |
| Livery's own template gate | in `livery-sessions-server` | A template draft checked as `DocumentAdmission` checks a document (contract, target, features), then rendered with the contract's examples or the person's data | the generic part of `AgentDesignSessions` (~150) |
| `livery-sessions-client` | KMP, Ktor | The person's side: link, long-poll, comment, decision, sharing a render; resumes after a reload | `AgentRelayClient`, `AgentLiveControls` (~460) |
| `livery-sessions-compose` | Compose Multiplatform, Material 3 | `LiveryAgentPanel`: presence line, the transcript (the agent by name, its steps grouped into turns), composer with a pinned target and `@` mentions, the fixed action row (**Use it** / **Create it**, **Adjust**, **Stop**, **Undo**) and draft chips. Also a `SessionReview` scaffold with a preview slot the app fills, the session list, and "Connect an agent" | `AgentLivePanel`, `AgentTranscript`, the generic body of `AgentEditScreen`, `DashboardAgentUi` (~1,500) |
| `livery-sessions-agent-render` | Android, JVM, optional | A `TurnRenderer` on agent-render (Markdown replies, expandable tool calls) | `AgentTurnView.agentRender.kt` and the `agentRenderMain` source set |
| `livery-agent` | KMP | The built-in agent: a tool-calling loop over the same session tools, on any OpenAI-compatible chat-completions endpoint (Gemini, OpenRouter, a custom URL). Keys stay in the caller's memory | `AgentCardDesigner` (~300) |
| `livery-uibuilder` (extended) | KMP + JVM | Run or reach UI Builder (`UiBuilderConfig`, its process, tokens, the admin listing), bounded app-to-browser **handoffs** (one-time tokens, 10 min), seeding a design from a contract (already there), the **`edits` channel publisher**, and `livery ui-builder import` plus a reusable workflow that opens the pull request back to the repo | `UiBuilder`, the handoff half of `CardStudio`, `LibraryEditPublisher`, `import-card-designs.py`/`.yml` (~1,500) |

The tests move with their code: `AgentRelayTest`, `AgentSessionFileTest`, `AgentLivePanelTest`,
the generic half of `AgentEditScreenTest`, `UiBuilderTest`, and the handoff half of
`CardStudioTest` (~2,000 lines).

### What stays in harc

- **The dashboard gate** (`DashboardAgentSessions`). It checks Lovelace shape and holds a section
  session to its section. It is registered as the `dashboard` kind's `DraftGate`.
- **Applying.** Saving to Lovelace, undo against a baseline, the admin-only rule.
- **The target labeler.** "Living room temperature (view 1, section 2, card 2)" is
  `cardLabel` plus the friendly name, as an implementation of `TargetLabeler`.
- **The preview.** The app draws its own cards in the review scaffold's slot.
- **Home Assistant's direct path.** `edit_sessions.py`, `edits.py`, `mcp.py`, `dashboard_agent.py` and
  `cloud_relay.py` run inside HA, in Python, and are HA-specific by nature.
  - They keep their own code, but **speak the same session wire format**: Livery's session spec
    (`docs/SESSIONS.md`) and its conformance fixtures, which the Python tests also run.
  - So an agent or a panel can't tell which side it is talking to.
- **`UiBuilderDesignCompiler`**: compiling `.uid` designs with the `ha-cards` catalog.

### What #1700 adds, and where it goes

[#1700](https://github.com/yschimke/homeassistant-remotecompose/pull/1700) shows the built-in
agent's **steps** in the chat and adds **`@` mentions**. It was kept apart from the app's other
screens for this move, and it all maps onto the modules above:

| #1700 | Moves to | Note |
| --- | --- | --- |
| Steps on the edit session (`edit_sessions.py` `agent_step`, `describe_step`; `AgentEditSession.Step`; `AgentRelayMessage` kind `tool`) | the session model's **steps**: one sequence with the conversation, text in words, 500 characters, the newest 100 (`SessionRelay.agentStep`) | Built in `livery-sessions-core`. The Python direct path keeps its own code, held to the same limits and wire shape by the conformance fixtures. A cloud agent reports steps with the new `report_step` tool |
| Turn grouping (`AgentTranscript.chatItems`, `stepTitle`) | `transcript`, `stepTitle` in `livery-sessions-core` | Built, with its tests |
| `SharedAgentTurn`, `ChatBubble` | `livery-sessions-compose`'s default `TurnRenderer` | Every target, Wasm included |
| agent-render (`agentRenderMain`, Android and desktop) | optional `livery-sessions-agent-render` | It has no Wasm or iOS build and is at 0.2.6, so Livery's core UI module doesn't depend on it |
| prompt-bar's `@` autocomplete (`ChatMention`, `chatMentions`) | `livery-sessions-compose`'s composer, with a `MentionSource` the app supplies | The trigger detection is a few lines of Livery's own: prompt-bar (0.3.0, one maintainer) stays out of a published library. harc's `chatMentions` (the preview's cards, then entities with their ids) becomes HA's `MentionSource` |

Review notes on #1700, none blocking, to fix as the code moves:
- `chatMentions` splits a card's label at its first `" ("`. A card named "Temperature (°C)" shows
  as "Temperature", with "°C) (view 1, …)" as its subtitle. The pin still uses the whole label, so
  only the suggestion reads wrong. A `TargetLabeler` that returns the name and the place
  separately fixes it.
- The mention list is remembered on `snapshot.states.keys`, so a renamed entity keeps its old name
  in suggestions until an entity is added or removed.
- Picking a card pins the message even when inserting its name would pass the 2,000-character
  limit and is skipped.

### What Confetti gets, with no server code of its own

- A designer opens **Ask your agent** on a session-card design.
- The agent connects with a code and drafts.
- Livery's template gate checks the draft against `confetti/session@1`.
- The panel draws it with the contract's examples or the live schedule. **Use it** publishes it to
  a preview channel.
- **Edit in UI Builder** opens a handoff. Saves go to the `edits` channel, and the import workflow
  opens the pull request.

Confetti registers only `confetti/` contracts and, if it wants one, its own target labeler.

### UI Builder saves and catalog trust

`LibraryEditPublisher` today publishes a UI Builder save to `main` within about 60 s, with no CI.
Under Livery the library is signed. Neither "the server signs `main`" nor "pull requests only" is
right:

- **A server key that signs `main`** would be an online key that can push to every user's default
  library: the worst key to lose.
- **Pull requests only** loses the minute-long edit-to-device loop designers rely on.

Instead:

1. **`main` is signed only by the CI key**, so only merged pull requests reach it: the daily import,
   or a designer's own.
2. **Saves go to an `edits` channel**, signed by a server key limited to the catalog's contract
   prefix (`ha-card/`, `confetti/`).
3. **Only devices that opt in read `edits`**: a designer's phone, or "preview library edits". It is
   layered above `main` with L1, as `pr-<n>` channels are for reviewers.
4. **The add-on's mirror follows `main` only**, so a self-hosted home never trusts that server key.
5. **The import pull request moves edits to `main`.** Once CI signs it, the edit reaches everyone,
   and its `edits` entry is pruned.

If the server key leaks, the damage is limited to opted-in designers' devices and one contract
prefix. Edits still reach designers within a minute.

### Order: track A, beside steps 0–8

None of this depends on the card migration, so it can start now.

- **A1. Spec and core.** `docs/SESSIONS.md` in Livery (the wire format above), conformance
  fixtures, and `livery-sessions-core` with harc's tests moved over. harc's Python direct path runs
  the fixtures. *In progress:* `livery-sessions-core` (relay, links, steps, transcript, save and
  restore) is [yschimke/livery#132](https://github.com/yschimke/livery/pull/132); the conformance
  fixtures are next.
- **A2. Server.** *Done, with Livery 0.22.0:* design-server runs on Livery's `SessionServer`
  (`livery-sessions-server`) behind the same routes and tools.
  - **Moved to Livery wholesale:** the person's HTTP routes and their JSON, the agent's MCP
    endpoint and the save file. `AgentRelayRoutes`, `AgentRelayMcp` and `AgentLinks` are deleted.
    `AgentSessionFile` is now Livery's `SessionFile`, and it still reads files written before the
    switch.
  - **Through the extension point:** `card` and `dashboard` are `SessionKind`s
    (`AgentSessionKinds.kt`). Each one has:
    - its store;
    - its gate, through the per-kind handlers (`AgentDesignMcp`, `DashboardAgentMcp`) that the
      older per-session endpoints also use;
    - `put_card_asset` as a card tool;
    - its HTTP path;
    - its save.

    Livery passes a draft's summary to the kind, so the dashboard store keeps it
    ([yschimke/livery#136](https://github.com/yschimke/livery/pull/136)).
  - **The adapter:** `AgentRelay` is a thin facade that keeps harc's names for its callers.
    Terrazzo's instructions to agents are a `SessionText`.
  - **Stays:** the stores' rules for Lovelace and card drafts, and Home Assistant's own direct
    path in Python.
- **A3. Client and UI.** harc's app-ui and Android app move to `livery-sessions-client` and
  `-compose`, with HA's target labeler and preview. `livery-agent` replaces `AgentCardDesigner`.
- **A4. UI Builder.** Handoffs, the UI Builder process and the import move to `livery-uibuilder`.
  The `edits` channel replaces `LibraryEditPublisher`'s writes to `main`, and needs step 3's
  signing.
- **A5. Confetti.** The first app with no app-specific server code proves the modules are general.

## What moves into Livery, and what harc loses

Each item below is a Livery PR, held to Livery's rules: conformance fixtures and the Swift port in
the same PR, `PROTOCOL.md` changed with the code, and site pages. Nothing in it names Home
Assistant.

| | Livery change | Needed by | Replaces in harc |
| --- | --- | --- | --- |
| **L1** | **Several catalogs in one host**: `LayeredRepository(personal, library, pr-channel)`. A `designId` resolves in its own source first, and automatic selection takes the union. Pure selection; conformance | step 4 | the per-caller manifest folding in `UserDesignRoutes`, which also drops the signature |
| **L1b** | **Compiled contracts and incremental binding** (`CompiledContract`, `BindSession`) | step 5 | — (performance) |
| **L2** | **Vocabularies**: `Contract.domain`, `dataSource: Host`, keyed vocabularies with fixed ids, `TemplateEntry.uses`, `PlayerCapabilities.provides`, an eligibility rule, publisher inference | step 1 | `HaSharedValues` keeps only its values; the plumbing goes |
| **L2b** | **Reference lists**: `ListSpec.ref`, binding ids, a host value store, store-fed named values in `LiveryTemplate`, resolving or renaming in `livery-instantiate` | step 1 | `CardDesignSlots.slotBindings` and the per-card copies of entity values |
| **L2c** | **Discovery**: `TemplateInspector.conformance`, `implements`, the embedded `livery.sidecar` | step 2 | `meta.binding_recipe`, and the embed's own document sniffing |
| L3 | **`livery-recipes`**: data-to-inputs expressions, Kotlin, Swift and TS | step 7 | `CardBindingRecipe` (317) and its JS copy in `ha-card.js` |
| **L4** | **Devices and transforms**: `display` and `player` capabilities, named transforms, derivations, the `derive/` route (no receipts yet) | steps 6–7 | `PlayerFolding` (320), `forDisplay`, `WearWidgetTemplate` + `CardDesignWearTemplate` (546), launcher recapture, `GlyphRanges` + `WidgetFilters` (577), most of `/generate` |
| L5 | AndroidX View player plays Livery templates | sliders, lock | — |
| L6 | Manifest split by contract | only if the step-1 count is too big | — |
| L7 | `livery-server` catalog channels: upload-what-changed, mirror, server-side `derive/`, fetch counting | step 8 | `LibraryChannels` + `LibraryMirror` (656), `UsageStats` (304) |
| L8 | Phone → watch blob sync over the Data Layer | step 6, optional | the design half of `MobileWearSyncManager` |
| **L9** | **Agent sessions** (`livery-sessions-*`, `livery-agent`): see [Agent and UI Builder flows](#agent-and-ui-builder-flows-as-optional-livery-modules) | track A, any time | relay, links, MCP surface, session client, live panel, dashboard agent runner (~4,000 + tests) |
| **L10** | **UI Builder flows** in `livery-uibuilder`: handoffs, editor process, an `edits` channel publisher, import back to the repo | track A | `UiBuilder` config and process, `CardStudio` handoffs, `LibraryEditPublisher`, the import workflow (~1,500) |

**Also deleted once the steps land:** `DesignRepository`, `DesignQuery`, `DeviceMatch`,
`CardDesign`'s wire types, `BundledDesigns`, `SkinStore`, `Skins`, `CardDesignSync`,
`CardDesignSnapshot`, most of `CardDesigns` (~2,300 lines), `CardDocumentCache` and most of
`HaRcComposePlayer` / `CachedCardPreview` (~1,000 lines, replaced by `LiveryResources` /
`LiverySlot`), plus the dead code from step 0 (`CardGenerator` chain and `addon-server`, ~1,700
lines).

**Estimate: about 10,000 lines of Kotlin leave harc for the card migration, and about 5,500 more
(plus ~2,000 of tests) for track A.** In exchange harc gains generated contracts, an HA vocabulary,
a dashboard gate and a target labeler.

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
   - Generate the `ha/entity@1` vocabulary from `LiveValues`' fields, with `sun.sun` as its fixed
     entity.
   - Generate `ha-card/<type>@1` from the converters.
   - Add the inspector test over every blob in today's library.
   - Add a one-way converter `DesignManifest` → Livery `Manifest`. It is used only to count entries
     (the L6 decision) and as the parity oracle in step 4.
2. **Catalog build** (needs L2c). `card-designs` writes the Livery source tree; `livery build --inspect`; CI
   signing; the bundled copy through the Gradle plugin. Publish it beside today's manifest on the
   same `main` / `pr-<n>` channels.
3. **Serve it.** Add the `/livery/v1/...` routes, the integration proxy path and WS discovery with
   keys. Add the personal catalog with the add-on's own key, and the `edits` channel's key.
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
- **Catalog trust.** Today's clients accept unsigned manifests. With Livery, `main` is signed only
  by CI, and UI Builder saves go to an opt-in `edits` channel with a prefix-limited server key (see
  [UI Builder saves and catalog trust](#ui-builder-saves-and-catalog-trust)). Step 3 sets up both
  keys.
- **Protocol compatibility for vocabularies and derivations.**
  - An older Livery client ignores `uses`. It could select a template that reads `HA:` values it
    never pushes, and that template would then draw with the document's own defaults. For
    `ha/entity@1` that is safe for the fixed entities, since every `sun.sun` value defaults to its
    daytime value. A reference-list slot read is not: an old client never fills it.
  - An old client can't be protected by a field it doesn't know. The only gate shipped Livery
    clients honour is the manifest's `minClient`. So a channel with reference-list or vocabulary
    entries sets `minClient >= 9`, which `livery build` raises automatically, and old clients keep
    the last manifest they accepted. Today's harc clients don't read Livery manifests at all, and
    keep reading `/designs/v1` until step 8, so this costs nothing during the migration.
  - Derivations are additive. An old client sees only native templates.
- **Discovery is advisory.** `implements` is computed from facts, but a document that structurally
  fits a contract can still mean something else by a name. The publisher writes what it inferred,
  and a reviewer or `livery check` sees it in the diff. Inference never widens trust: a document
  still plays only what its signer's key may sign.
