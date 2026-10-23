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
- **Device designs are picked, never chosen.** `DeviceMatch` (integration, manufacturer, model
  glob, translation key) picks the most specific device design automatically
  (`CardDesigns.cardTemplateFor`). A user can only override a card's style and variant
  (`CardDesignOverrides`), and no UI sets the variant.
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
- **Export.** UI Builder exports RemoteCompose with state variables marked
  `"persistence": "host"` as named `User` values, which is what `LocalTemplateBindings` reads.
  The deployed UI Builder must be built with `-PuiBuilderRemoteCompose=true`.
- **Validation** is the existing `userTemplateProblem` (bindings against the base schema, op
  profile), plus the parity gate below while a card type migrates.
- **Import.** A scheduled workflow fetches the server's library designs (`.uid` sources, `.rc`
  blobs, manifest) and opens a PR. CI renders the imported blobs in the catalog, so a design
  change on the server shows up as a catalog diff before it lands.
- **Fallback.** The app ships the imported manifest and blobs as assets. Lookup becomes: synced
  server design → shipped asset → nothing (an "unsupported" card), with no runtime capture.

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
4. **Delete** the converter's `Render` layout and its capture path. Its key, bindings and
   actions stay.

Card types whose look can't be expressed yet stay on their converter and are listed in
`card-designs/converter-only.md` with the reason. Likely: markdown, map, picture-elements,
the history and statistics graphs, and the Bambu Lab cards.

**Wear widgets** follow the card: the design server already roots a published design on the
Wear containers (`WearWidgetDesigns`), so they need no separate source.

**Launcher widgets don't yet.** `LauncherDesigns.capture` renders `LauncherWidgetContent`
through the converter per size and widget theme, and `bakeValues` only writes values into that
capture. Each migration therefore also needs a launcher export: the design server roots the same
revision on the launcher widget root (`ha-cards-catalog`'s launcher root) for each size tier and
widget theme, validated against the widget profile (level 7, `COLOR_THEME` only on API 37+), and
publishes them as `launcher` designs keyed like today's (`variant = <w>x<h>`). The converter's
launcher capture is deleted only when those exist.

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

- `DesignEntry` gains a stable **design id** (the design server's id for a published design, the
  `.uid` file name for a library one), and `DesignRepository` keeps every entry's bytes by id
  alongside today's resolution, which collapses competing entries per key by author and manifest
  order. Without that, a stored id has nothing to resolve against.
- `CardDesignOverrides` gains a **design id** per card (`byCard`, keyed by the card's config
  fingerprint), next to the existing style and variant.
- `cardTemplateFor` checks that override first. Otherwise the order stays the same: the device
  design for the variant, then the generic design, then the shipped asset.
- The card's style sheet lists every design that fits the card: the generic one, the device
  designs whose `DeviceMatch` matches the card's device, and the forks of either the person can
  see. Choosing one stores its id; "Automatic" clears it.

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
| D2 | Design server serves library designs from `.uid` (admin-editable, public-readable), exports per family |
| D3 | Scheduled import workflow and shipped assets; launcher export per size and widget theme; runtime capture removed for migrated types |
| D4 | Forks copy the real source; fork lineage on the design server |
| D5 | Design id in the manifest and cache; per-card design override and the chooser in the card's style sheet |
| D6+ | Remaining card types, one PR each; `converter-only.md` for the rest |

## Open questions

1. **Who can edit library designs.** Proposal: admins listed in the deployment, matching the
   design server's existing GitHub sign-in.
2. **Asset size.** One `.rc` per key × family × mode is the existing library's size; check it
   against the APK budget before D3, and ship only the default families if it is too large.
3. **Unsupported fallback.** With no runtime capture, a card whose key has no design shows the
   unsupported placeholder until a sync. Proposal: keep runtime capture for converter-only types
   only.
