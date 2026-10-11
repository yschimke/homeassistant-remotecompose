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

## Non-goals

- Server-side rendering with values. That is the add-on's
  `/v1/cards/{id}.rc` route (`addon-server/PLAN.md`), and this plan
  does not replace it. The design server knows nothing about any user.
- Executing remote code. A design is an RC document played by the
  existing player. It can only reach actions the client passes in for
  its slots (see [Actions](#actions)).
- Converting arbitrary custom cards. Card types with no converter stay
  `Unsupported`.

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
| `s<N>.name` | string | `name:` from config, else `friendly_name` |
| `s<N>.icon` | bitmap | icon rendered by the client (MDI path → bitmap) |
| `s<N>.color` | color | state colour (`HaStateColor`), resolved by the client |
| `card.title` | string | card-level `title:` / `name:` |
| `card.count` | int | number of populated rows, for list-shaped cards |
| `theme.<role>` | color | theme role (primary, surface, onSurface, …) where the target has no `ColorTheme` / `WearM3` |

The `<id>.state` rules in `live-card-updates.md` still hold, with
`s<N>` standing in for `<id>`. The client must be able to rebuild every
value from the entity state plus the card config alone.

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

### Variable-length cards

Entities, glance and similar cards have N rows. In v1, publish templates
in **row-count buckets** (1, 2, 3, 4, 6, 8) and hide unused rows with
`card.count`. A card with more rows than the largest bucket uses the
inbuilt converter. A data-list op in RC could later replace the buckets
(spike S3).

### Configuration shape

Bindings carry values, and slot mapping carries entities, but some card
config changes the **structure** a converter emits, and no binding can
apply it after capture. For example, `HeadingCardConverter` bakes
`heading_style`, `ClockCardConverter` bakes `clock_size`,
`show_seconds` and whether `time_zone` is set, and
`AlarmPanelCardConverter` bakes `states` and `show_keypad`.

Each card type therefore declares a **shape function** in `ha-model`
next to `slotsFor`: `fun shapeOf(card: CardConfig): String?`. It
normalises only the structural keys (`heading_style=title`,
`clock_size=large;seconds=1;tz=1`), sorted and stable. The shape is part
of the `DesignKey`, and the pipeline captures one template for each
shape listed in `matrix.json`. A config whose shape the manifest doesn't
list, or a key the shape function doesn't recognise (`null`), falls
through to the local converter. Two cards with different structures can
never share bytes.

Non-structural config values (gauge `min` / `max`, a time zone ID) are
bindings, not shapes, or the matrix explodes.

### Which cards fit

- **v1 (binding cards):** sensor, entity, tile, button, glance, area,
  entities, alarm-panel, heading, clock. Alarm-panel, heading and clock
  need a [config shape](#configuration-shape).
- **Later, once the schema grows:** gauge (`GaugeCardConverter` picks
  the severity band from the current value and the configured
  thresholds at capture time, so a template would freeze the
  placeholder's colour; it needs bound thresholds plus an in-document
  band pick, or an `s<N>.severity_int` binding), thermostat, humidifier
  and light
  (arc fraction derived in-document from `numeric_state` plus
  `s<N>.min` / `s<N>.max`), media-control (split title and progress
  into bindings).
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
  shape,           // normalised structural config, see "Configuration shape"
  bucket,          // row-count bucket, or 1
  schema,          // binding schema version, "1"
)
```

Each surface starts from today's profiles:

| Surface | Profile | Size mode | Theme | Gate |
| --- | --- | --- | --- | --- |
| `mobile-app` | `androidXExperimentalWrap` (latest level) | Wrap | `theme.*` bindings | always |
| `launcher` | `widgetsProfile` (level 7) / `systemThemedWidgetsProfile` (API 37+) | Fixed, tiered | `ColorTheme` on 37+, otherwise `theme.*` | `WidgetSync.liveWidgetIds()` not empty |
| `wear-widget` | Glance Wear stable op set (no FlowLayout) | Fixed, small/large | `WearM3.*` | watch paired with `terrazzo_wear_widgets` capability |
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
      designs: [ { key: DesignKey, sha256, bytes, slots, sizeRange,
                   catalogId, deprecated? } … ],
      variants: { "<surface>": ["default", "material", "modern"] },
      signature }

GET /designs/v1/blobs/<sha256>.rc     (immutable, Cache-Control: max-age=31536000, immutable)
```

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
  that preview.coo.ee serves. This keeps designs and catalog in one
  bundle, but couples app runtime to the preview server's uptime.
- Optional mirror: the add-on serves the same files at
  `/v1/designs/...`, so a home without internet still gets designs from
  inside the LAN.

A dynamic Ktor service only becomes worthwhile for on-demand
combinations we don't want to pre-build. It would need the JVM capture
path from `addon-server/PLAN.md` (option A, `remote-creation-jvm`).

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

- Refresh after a dashboard load if the manifest is older than
  `ttlSeconds` (default 24h). Debounce it, and don't run more than one
  refresh in parallel.
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
  (LTE) can be added later.
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

## Catalog

Each manifest entry gets a `catalogId` and appears in the
`homeassistant-remotecompose` catalog:

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
- A design PR shows up as a catalog diff (`catalog_history_diff`), which
  becomes the review step for "change a design on the fly".

## Milestones

| | Deliverable | Depends on |
| --- | --- | --- |
| **S1** | Spike: instantiate a template by patching named-variable initial values; prove on the launcher (`DrawInstructions`) and a Glance Wear widget | — |
| **S2** | Spike: icon and colour as named bindings (`s<N>.icon` bitmap, `s<N>.color`), and `theme.*` named colours in the Wrap profile | — |
| **S3** | Spike: list op vs row-count buckets for entities/glance | — |
| M1 | Binding schema v1 + `slotsFor` in `ha-model`; converters for tile, sensor, entity, button move names, icons and colours onto bindings; design-snapshot capture | S1, S2 |
| M2 | `card-designs` module: matrix, capture, validator, manifest, blobs; inbuilt assets in `app` | M1 |
| M3 | Client: `DesignRepository` (disk cache, refresh rules, signature check), `DesignCardGenerator`, `CardRender.Template`, wire `CardSource` into the dashboard | M2 |
| M4 | Static hosting on Pages (`/designs/v1/`), CI signing, kill switch | M2 |
| M5 | Launcher surface (instantiate in `TerrazzoWidgetProvider`) | M3, S1 |
| M6 | Wear: phone syncs blobs over the Data Layer, watch cache + inbuilt designs, wear-widget and wear-app targets | M3 |
| M7 | Catalog: generated `Designs` group rendering shipped blobs | M2 |
| M8 | Second variant (`modern`) end to end, plus the variant picker in settings | M3, M7 |
| M9 | More cards: entities/glance buckets, alarm-panel, area, heading (with config shapes); gauge once severity is bound or picked in-document; the arc cards once `min`/`max` bindings exist | M1 |

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
5. **Telemetry.** Do we want a count of which `(variant, surface,
   source)` rendered (remote, stale, inbuilt, local)? Without it we can't
   tell how out of date clients are. With it, the server is no longer
   strictly anonymous. Proposal: no telemetry in v1.
