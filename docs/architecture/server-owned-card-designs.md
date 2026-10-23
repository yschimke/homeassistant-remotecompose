# Server-owned card designs

> Status: **plan**. This document replaces the staged approach of the closed
> [#922](https://github.com/yschimke/homeassistant-remotecompose/pull/922). It says what changes,
> in what order, and what stays in Kotlin.

## Goals

1. **No built-in card layouts in the app.** Every card's look is a design that can change on the
   server without an app release.
2. **Regular import into this repository.** Server designs are copied back on a schedule, as
   reviewed PRs, and shipped in the app as the fallback and offline cache.
3. **Every card design is in UI Builder and editable there.**
4. **Every design is forkable.**
5. **A device's card can be switched to a fork.** The generic design for a device type (a 3D
   printer, a boiler, a Sony TV) can be replaced, per card, by a forked design.
6. **One design, every family.** A design is written once against the `ha-cards` components and
   re-themes into any card style (`HaUberTheme`: Braun, IKEA, Trinitron, …).

## Where we are

The pieces are described in [card-design-server.md](card-design-server.md#spike-status). What
matters here:

- **Kotlin is the only source of a layout.** Each `rc-converter/.../cards/*CardConverter.kt`
  draws its card. The design server's library is a build-time capture of those converters
  (`:card-designs:generateCardDesigns`), and the app captures any key the server lacks at runtime
  (`CardDesignLibrary`). No card design exists as a `.uid`.
- **Forking is approximate.** "Design in UI Builder" rebuilds a library design from `ha-cards`
  components (`HaCardsSeed`). Gauge, tile and button map across; every other card becomes entity
  rows, so a fork is a lookalike, not the card.
- **Device designs are picked, or chosen per card.** `DeviceMatch` (integration, manufacturer,
  model glob, translation key) picks the most specific device design automatically
  (`CardDesigns.cardTemplateFor`); since D5 a card can be switched to any design by its id (see
  [Switching a device's card](#switching-a-devices-card)). No UI sets the variant.
- **User designs live only on the server volume.** Nothing backs them up or imports them.
- **Families already share components.** Meters, tile layouts and surfaces switch form per
  `HaVisualFamily`, and `ha-cards/uber-theme` re-themes a whole UI Builder design from one
  property. The server still publishes one key per theme.

## What stays in Kotlin

Layout moves to designs; **data and behaviour stay in code**. Per card type, Kotlin keeps:

- the **design key**: which shape a card config has (`designProperties`, `EntityDesign`);
- the **bindings**: every named value the client pushes (`CardDesignSlots`, the binding recipes),
  so cards keep the [live-update contract](live-card-updates.md) and show current data on the
  first frame;
- the **actions**: resolving a tap on a design to the card's own action (`designAction`,
  `CardTemplatePlayback`);
- the **invalidation**: `CardConverter.dataSignature`, the fingerprint of content a card bakes
  rather than binds (tile extras, sensor history, forecasts, container children). A card type
  migrates only once everything its signature covers is either a binding or part of its design
  key; until then the signature keeps forcing a new document when that content changes.

What goes is each converter's `Render` layout, and the runtime capture that turns it into a
template. Moving values and actions into designs as well needs expression and action support in
UI Builder; it is out of scope.

## The pipeline

```
ha-cards .uid  ──(UI Builder, admin edit)──▶  revision
      │                                         │ export .rc (named host state)
      │                                         ▼
      │                                 design server: validate bindings ⊆ schema,
      │                                 op profile, snapshot invariance → manifest
      │                                         │
      └──◀── scheduled import PR ◀──────────────┘
             card-designs/ui-builder/designs/*.uid
             card-designs/library/{manifest.json, blobs/*.rc}
                                                │
                                   app assets: inbuilt fallback
```

- **Source.** `card-designs/ui-builder/designs/<cardType>[-<shape>].uid`, one per design, using
  the `ha-cards` catalog. Theme is not part of the source: the family comes from
  `ha-cards/uber-theme`, so the export step produces one `.rc` per family and mode from one
  `.uid`.
- **Export.** Two implementations of the same transformation, kept equal by a test:
  - **In this repository** (`CardDesignDocument` in `:ha-cards-catalog`): draws a `.uid` by
    calling the `ha-cards` components with each node's properties, inside the same capture harness
    as converter templates (`CardDesignTemplates.captureWith`). It needs no UI Builder release, so
    the library and the scheduled import capture designs in a Robolectric test today.
    Binding-shaped components (`ha-cards/entity-tile`) take their structural properties from the
    design key being captured, so one `.uid` covers every key of its card type.
  - **In UI Builder**, for live publishing from the design server: RemoteCompose export with state
    variables marked `"persistence": "host"` as named `User` values, which is what
    `LocalTemplateBindings` reads. Released UI Builder builds have it switched off
    (`-PuiBuilderRemoteCompose`, compiled into `ui-builder-export`), so this half waits on a release
    that enables it.
- **Validation** is the existing `userTemplateProblem` (bindings against the base schema, op
  profile), plus the parity gate below while a card type migrates.
- **Editing.** Each migrated card type's library design is the UI Builder design
  `library-<cardType>` on the design server, created from the checked-in `.uid` the first time
  one of its `LIBRARY_EDITORS` opens it ("Edit the library design" on a design's page) and edited
  in place after that (`LibrarySources`). Forking a library design of a migrated type copies that
  real design, not a reconstruction.
- **Import.** `GET /api/library/sources` serves each migrated card type's current design (the
  edited one, else the checked-in one). The daily `import-card-designs` workflow writes them over
  `card-designs/ui-builder/designs/` and opens one PR when anything changed. CI rebuilds the
  library from the imported sources (the `.rc` is made here, not on the server), and the preview
  diff on that PR is the review of what changes in the app. While a card type still has a
  converter, its parity gates fail an edit that changes its pixels; that is the migration gate
  doing its job, and the edit lands with the converter's layout deleted.
- **Fallback.** The app ships the library's default style as assets
  (`app/src/main/assets/card-designs`): the app's default theme style (Terrazzo Home), light and
  dark, with the classic card style, captured from the checked-in designs exactly as the library
  is. `ShippedCardDesignsTest` (`:card-designs`) fails when they aren't what this build captures,
  and `-PupdateShippedCardDesigns` rewrites them; the import PR does that itself. Lookup is: synced
  server design → shipped asset (`ShippedDesignLibrary`) → a capture on the device. The capture
  stays for the keys nothing ships (other theme and card styles, shapes the library's source cards
  don't produce, converter-only types); dropping it is the end state.

## Migrating a card type

One card type at a time, each its own PR:

1. **Author the `.uid`.** Generate it from the converter's shapes, then refine by hand in UI
   Builder until it draws the same card. Missing `ha-cards` components are added to
   `ha-cards-catalog`, built from the same `rc-components` the converter uses.
2. **Parity.** For each of the card's library keys, the `.uid`'s exported `.rc` and the
   converter's capture draw the same pixels across families, modes and the
   `CardPreviewMatrix` sizes (`CardDesignParityTest` already compares template against
   converter; this compares design against template).
3. **Switch.** The card type's library entries come from the exported design.
4. **Delete** the converter's `Render` layout and its capture path: the converter extends
   `DesignedCardConverter`, whose final `Render` draws the packaged `.uid`. Its key, bindings and
   actions stay, and so does whatever data its design's parts draw with.

Three checks keep a migrated type migrated:
- A branch that changes the converter's old layout code no longer compiles: `Render` is final.
- `DesignedCardConvertersTest` (`:rc-converter`) holds the checked-in `.uid`s and the
  `DesignedCardConverter`s to each other, one for one.
- `CardLayoutPolicyTest` (`:ha-cards-catalog`) holds UI Builder's shelf to the designs: every
  card-layout component has a policy record, and every slot, property and value a checked-in
  design uses is declared there.

A change to a migrated type's layout is a change to its `.uid` and the parts it arranges, proved
by the parity tests.

Card types whose look can't be expressed yet stay on their converter and are listed in
`card-designs/converter-only.md` with the reason. Likely: markdown, map, picture-elements,
the history and statistics graphs, and the Bambu Lab cards.

## One source, every surface

A person designs **the card**, once, on the `ha-cards/dashboard-card` root. Every other surface
is derived from that `.uid` by the transformation that turns a revision into `.rc` designs, so
no surface has a source of its own and no surface falls back to a converter.

The transformation reads the card document and, per target surface, rewrites its root and
environment before exporting:

| Surface | Root | What the transformation applies | Keys |
| --- | --- | --- | --- |
| Dashboard (`mobile-app`) | `ha-cards/dashboard-card` (as authored) | wrap size mode, the app profile, card chrome on | today's, per family and mode |
| Wear widget | `remote-m3/widget-container-small` / `-large` | card surface removed, the container's padding and corner radius as named values, Glance Wear op set | `wear-widget`, `container = small \| large` |
| Launcher widget | `remote-widgets/launcher-widget` | the widget profile (level 7; `COLOR_THEME` only for the system-themed variant), fixed size mode at each tier's W×H, card chrome off and the widget surface painting the background, taps as numeric host actions, the `LauncherLook` and light/dark | `launcher`, `variant = <w>x<h>`, `theme = widget:<system\|fallback>:<mode><look>` |

These are exactly what `LauncherWidgetContent` and `WearWidgetTemplate` add around a converter
today, moved from Kotlin capture into the document transformation. The size tiers come from
the policy's launcher grid, `(73n − 16) × (118m − 16)` dp, for the cells the app actually
offers.

Rules the transformation enforces, so an author never has to remember them:

- **Every surface or none.** A revision publishes only if every target surface exports and
  validates (op set, bindings ⊆ schema). A card that can't fit a surface says so in the design
  (a surface opt-out the transformation reads), never by silently missing a key.
- **Content follows area.** The derived launcher and Wear documents keep the card's adaptive
  layout (collapsible rows, fit boxes); the transformation sets the size, it doesn't re-layout.
  The builder's preview panes (round Wear at both sizes, the resizable launcher grid, phone and
  tablet) show the same derivations the server publishes, so what an author sees is what ships.
- **Taps stay slot-relative.** Launcher taps keep their slot or element ids, so
  `LauncherDesigns.bakeValues` and the existing `resolved_action` PendingIntents work unchanged.

**Device-specific transformations follow.** The surface rewrites are an open list, so later
ones narrow a surface to a host device without a new source: a round or squircle Wear screen,
a launcher with its own cell grid or corner radius, a TV or desktop player, an older RC level
for a host that can't play the current one. Each is another rewrite and another key dimension,
validated the same way, and added when a real host needs it.

The transformation lives in one place, the design server's publish path (today
`WearWidgetDesigns` for Wear), generalised to a list of surface rewrites, and the same code runs
in the scheduled import's check so the shipped assets match what the server published. The
converter's launcher and Wear captures are deleted for a card type when its migration lands, not
in a separate step.

## Forks

A fork is an **independent copy** that records its origin, not a UI Builder branch. A branch can
only merge back into its parent, can't pull the parent's later changes and can't itself be
branched, which doesn't fit a design someone keeps and republishes.

- Forking a library design copies its real `.uid` (replacing `HaCardsSeed`'s reconstruction) into
  a new design owned by the person, with `sourceDesignId` and the source revision recorded and a
  `previous` link set in UI Builder.
- The design server shows each design's forks and a fork's origin, and whether the origin has
  moved on since the fork.
- Publishing a fork is the existing `POST /api/my/designs` flow: private by default, shared on
  request, keyed like its base and optionally scoped to a device (`DeviceMatch`).

## Switching a device's card

Built (D5):

- `DesignEntry` carries a stable **`designId`** and an optional `title`, both left off the wire
  when unset, so older manifests parse and older clients ignore them. One id names one design
  across every key it covers: `library:<cardType>` (title "Built-in") from `DesignPublisher`, and
  `user:<UI Builder design id>` (its UI Builder title) from `POST /api/my/designs`, which the
  design server also fills in for designs published before ids. A library entry without an id
  still answers to `library:<cardType>` (`stableDesignId`).
- `DesignRepository` keeps today's resolution, which collapses competing entries per key by author
  and manifest order, and resolves the chosen designs **whole** beside it
  (`resolveAllWithDevices(designIds = …)` → `DesignsById`, also `byDesign(id, key, device)`).
  `designsFor(key, device)` lists, from the manifest, every live design with an entry for the key
  (any variant or author) that is generic or made for a matching device.
- `CardDesignOverrides.byCard` values gain a **`designId`**, persisted in `overrides.json` (old files
  still load).
- **Skins are installed, not assumed.** In the app a design chosen by id is a *skin*. The
  manifest lists every skin, but nothing of one is downloaded until it is **installed**, a
  separate step (`Skins.install`):
  - Installing runs the sync's refresh with the skin added, so its assets are downloaded for
    every theme the dashboard draws in.
  - The skin is recorded in `skins.json` (`SkinStore`) only once those assets are cached, so a
    failed download installs nothing.
  - `CardDesignChoice.queries` adds a `DesignQuery(designId = …)` per installed skin and theme,
    so the sync keeps an installed skin current whatever its author or variant.
  - The library's skins (`library:<cardType>`) come with every sync, so they are always installed.
  - Removing a skin stops the sync fetching it. Its cached assets stay, because the cache is never
    pruned.
  - A card switched to a skin that isn't installed draws as if it weren't switched.
- `cardTemplateFor` (`serverDesignFor`) checks that override first: the chosen design's entry for
  the card's device, else its generic one, in the preferred variant, then `default`, then any.
  Otherwise the order stays the same. A chosen design that is missing, revoked or not fetched yet
  falls through to that order, never to nothing. A Wear slot follows the same choice.
- The card's style sheet has a **Skin** section for "This card". It lists "Automatic", then every
  skin `designsFor` lists for the card's key and device (`cardDevice`), with its title, author and
  the device it is for.
  - An installed skin is a choice: choosing it stores its id, and "Automatic" clears it. A skin
    that isn't the library's can be removed.
  - A skin that isn't installed offers **Install**, shows its download, and offers a retry if the
    download fails.

## One design, every family

- Designs are written against `ha-cards` components that already change form per
  `HaVisualFamily` (meters, tile layouts, surfaces). A design never names a family's palette or
  font directly.
- The family is an input to export, not part of the source: the server exports one `.rc` per
  family and mode from the same revision, keyed `style:<id>:<mode>` as today.
- A fork can pin a family (setting `ha-cards/uber-theme` itself); its key then only covers that
  family.
- A component that doesn't yet adapt to every family is a gap in `ha-cards-catalog`, fixed
  there rather than by forking per family.

## Dependencies

| Change | Repository | Needed for |
| --- | --- | --- |
| Retain callable component bytecode in IR bundles ([#6085](https://github.com/yschimke/compose-ai-tools/pull/6085)) | compose-ai-tools | server compiles designs against the catalog's own components |
| Export supplied Compose source ([#957](https://github.com/yschimke/compose-ui-builder/pull/957)) | compose-ui-builder | only if export goes through compiled Compose rather than `.rc` |
| UI Builder built with RemoteCompose export | compose-preview-server / deployment | `.rc` export of named host state |

## Milestones

| | Deliverable |
| --- | --- |
| D1 | This plan; `card-designs/ui-builder/designs/` and the parity harness, with the tile migrated |
| D2 | Design server serves library designs from `.uid` (admin-editable, public-readable), exports per family. **Built:** editing as `library-<cardType>`, `/api/library/sources` |
| D2b | The surface transformation: one card `.uid` → dashboard, Wear widget and launcher `.rc` designs, with the preview panes matching it |
| D3 | Scheduled import workflow (**built:** `import-card-designs.yml`) and shipped assets (**built** for the default style); runtime capture removed for migrated types |
| D4 | Forks copy the real source (**built** for migrated types); fork lineage on the design server |
| D5 | Design id in the manifest and cache; per-card design override and the chooser in the card's style sheet |
| D6+ | Remaining card types, one PR each (**migrated:** `tile`, `button`, `entity`, `sensor`, `heading`, `glance`, `clock`, `thermostat`, `humidifier`, `light`); `converter-only.md` for the rest |
| D7+ | Device-specific transformations (Wear screen shape, launcher grids, TV/desktop, older RC levels), each when a host needs it |

## Open questions

1. **Who can edit library designs.** Proposal: admins listed in the deployment, matching the
   design server's existing GitHub sign-in.
2. **Asset size.** One `.rc` per key × family × mode is the existing library's size; check it
   against the APK budget before D3, and ship only the default families if it is too large.
3. **Unsupported fallback.** With no runtime capture, a card whose key has no design shows the
   unsupported placeholder until a sync. Proposal: keep runtime capture for converter-only types
   only.
