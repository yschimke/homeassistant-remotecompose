# Adaptive Card Layouts

> _Design philosophy for rendering Lovelace cards across the dashboard,
> launcher widgets, and Wear slot widgets — preserving as much of the
> Home Assistant reference design as the canvas allows._

## The problem

The rc-converter encodes one composition per card type. That composition
was authored against Home Assistant's reference dimensions: tile at
180×43, gauge at ~160×160, entities at 320×N. When the same composition
has to render at a host-determined canvas — a 1×1 launcher cell, a
200×60 Wear chip — the runtime either clips, scales, or (as the current
`Fixed`-mode placeholder does) drops down to centred text.

The text fallback is the path of least resistance. It's also the wrong
default. A gauge stripped to text is indistinguishable from a sensor; a
tile loses the icon-state pairing that makes it tappable; a lock loses
the locked/unlocked pictogram that reads at arm's length. Users can
read the number, but not what kind of thing they're looking at.

The screenshot that motivated this document — a `sensor.living_room`
gauge rendering as `21.4 °C` text in every Fixed-mode cell — is the
failure mode in concentrate.

## Two modes

A card is recorded in exactly one of two modes (`CardSizeMode`):

- **`Wrap`** — the template decides what to show; the document's
  intrinsic size flows back to the host. Used by the in-app dashboard.
  Matches the HA reference rendering. _Authoritative._

- **`Fixed`** — the host fixes the canvas; the document adapts at
  playback by switching between recorded layout tiers. Used by the
  launcher widget and the Wear slot widgets. Adaptation is encoded
  via `RemoteStateLayout` keyed off
  `RemoteFloatContext.componentWidth()` / `componentHeight()`, so the
  user resizing a widget swaps tiers without re-encoding the document.

Wrap is the design we'd ship if every surface had an infinite canvas.
Fixed is a controlled degradation of that design — never a different
design.

## Principles

### 1. Identity before density

The first thing a card communicates is _what kind of thing it is_.
The gauge **is** its dial. The tile **is** its icon + state-colour
pairing. The lock **is** its locked/unlocked pictogram. Strip the
density (range labels, names, severity captions) before stripping the
shape that announces what kind of card you're looking at. Text-only is
the last fallback, never the first compact tier.

### 2. Reflow before remove

When space tightens, the first move is to reorient — move elements
sideways, switch axes, tighten padding. Not to delete them. A gauge
that can't fit `dial-on-top + text-below` in a square cell can fit
`dial-on-left + text-on-right` in a wide-thin one. Same data, repacked.

Removal only kicks in when reflow is exhausted: there is no axis on
which both elements fit, even at minimum size.

### 3. Aspect-aware breakpoints

A 200×60 cell and a 144×144 cell both pass "≥ 140 dp wide" but ask for
totally different layouts. The first wants a horizontal row; the
second wants a column or square. Break on width **and** height (or
their ratio), not just width.

The `RemoteSizeBreakpoint` helper today reads only `componentWidth()`.
A 2D variant is a prerequisite before any worked-example ladder below
can do its job.

### 4. Compact ≠ stripped

A compact tier is a _redesigned_ layout, not a subset. The compact
tile, the mini-arc gauge, the icon-only lock chip — each is a
deliberate compact form, designed to look intentional at its size.
Reviewers should look at the smallest tier and feel "yes, that's how
this card looks tiny", not "that's the broken version".

### 5. Tiers describe what fits, not size labels

Don't think _small / medium / large_. Think:

- **Minimum viable identity** — smallest pill that still says what
  _kind_ of card this is. Usually icon-only or shape-only.
- **Identity + value** — adds the live state to identity.
- **Compact info** — adds the name. Most launcher widgets land here.
- **Full** — HA reference. Wrap mode.

Each card declares its own ladder; three rungs is typical, five for
heavyweight cards (gauge, entities, weather-forecast).

### 6. The default container layout matches the content layout

Every card has a _natural launcher shape_ — the cells the card wants
to occupy when it has no further constraint. That shape is the
**base** of the matrix preview, the size the user defaults to when
they pin the card to the home screen, and the geometry the converter's
`Full` tier targets.

It is **not** the wrap-mode dimensions. Wrap mode is the _expanded_
form — a stack-of-elements layout pinned to a wide dashboard slot.
A gauge's natural launcher shape is a horizontal pill (arc + text
side-by-side, ~`2×1`), not a vertical-stack square (`2×2`). A tile's
launcher shape is a row (`2×1`), not a column. Picking `baseGridSize`
to match the wrap-mode aspect ratio shoves the card into a layout
nobody asked for and makes the matrix's `−1` / `+1` neighbours look
wrong.

Rule of thumb: pick the `baseGridSize` that matches the card's
**Reflowed** tier — the layout it adopts when the launcher gives it a
typical pinned-card-shaped slot. That's also what the matrix's
"middle" cell shows, and what the `±1` neighbours bracket.

### 7. Earn the canvas — enrich upward, don't only degrade downward

The ladder is **bidirectional**. The principles above describe what to
_drop_ as the cell shrinks; this one describes what to _add_ as it
grows. A bigger widget must show **more** — more data, a larger key
element, finer chrome — never the same content floating in a pool of
empty space.

The failure mode is concrete and visible in the matrix today: `tile`,
`entity`, `button`, `entities`, and `weather-forecast` all render their
largest preview cell as the base layout pinned to the top-left with the
bottom 50–70 % of the cell blank. The card degrades gracefully but does
not _enrich_ — the user paid for a `4×2` and got a `2×1`'s worth of
information.

`Full` (= wrap = the HA reference) is **not** the ceiling. When the cell
is bigger than the natural shape, a card should reach for an **Expanded**
tier above `Full`: promote P4/P5 data that the compact tiers dropped
(a sensor's history sparkline, a tile's last-changed line, a button's
state value under the name), scale the key element to the available
room (a gauge dial that grows with the cell), or center the content so
the whitespace is balanced rather than dumped at the bottom. The
cheapest correct version of "earn the canvas" is **centering**; the
better version is **promoting the next data priority**.

### 8. No dead space

Every tier is responsible for the **whole cell** it is handed, not just
the rectangle its content naturally occupies. A composed layout either
fills the cell (key element scales, list grows more rows/cells) or
centers within it (so the margin is symmetric). A single row of content
glued to the top edge with a blank cell beneath it is a bug, not a
tier — it reads as "the card broke", which is exactly the reaction
Principle 4 (_compact ≠ stripped_) warns against, at the opposite end of
the size range.

This is the upward-size twin of "defaulting to text": text-at-the-top of
an empty cell is the large-canvas version of the same laziness.

## The degradation ladder (recipe)

Per card, declare an ordered list of layout variants from
most-detail to least-detail. At playback the runtime picks the
highest tier whose minimum dimensions fit the canvas:

0. **Expanded** — _bigger_ than the HA reference. Promotes a P4/P5
   field the `Full` tier doesn't show (history row, last-changed,
   secondary value) and/or scales the key element to the cell. Only
   reached on cells larger than the natural shape; see Principle 7.
1. **Full** — HA reference. Same as wrap mode.
2. **Reflowed** — same elements, repacked. Often a column-to-row
   swap. The cell where the user's screenshot lost its gauge belongs
   here.
3. **Identity + value** — drop the name; keep icon/dial + state.
4. **Identity** — icon/dial only. Colour, shape, badge convey the
   state.
5. **Value** — text chip. Last resort.

A card doesn't have to populate every rung — `entities` skips the
identity-only tier (it's a list card; the rows _are_ the identity);
a `tile` may have no Expanded tier and instead just **center** its
`Full` content on a large cell. The ladder is per-card, not a fixed
cascade. But every card must have an answer for both ends: a legible
identity at the smallest cell (Principle 1) **and** a filled or
centered layout at the largest (Principles 7–8).

> **alpha010 reality check.** Runtime tier selection currently fires on
> a _single_ breakpoint per card (#224) — nested ladders collapse to
> tier 0 at playback. So "Expanded / Full / Reflowed / …" is the design
> target, not something a converter can encode as 4 live rungs today.
> In practice each card picks the **one** transition that buys the most
> (e.g. gauge: Wide↔Stacked; entities: list↔strip) and uses _centering_
> rather than a distinct tier to satisfy Principle 8 at the large end,
> which costs no extra breakpoint. Revisit when a true multi-rung /
> aspect gate lands.

## Worked examples

### tile

| Tier | Min size | Layout |
|------|----------|--------|
| 1. Full | 180 × 40 | icon · name · state — HA reference |
| 2. Reflowed wide-thin | 80 × 40 | icon · state inline, no name; tinted background still carries on/off colour |
| 3. Identity chip | 56 × 56 | icon-only square, tinted by state |
| 4. Text chip | smaller | centred state |

### entity

| Tier | Min size | Layout |
|------|----------|--------|
| 1. Full | 180 × 40 | icon · name · state row |
| 2. Reflowed two-line | 80 × 40 | icon top, state below; no name |
| 3. Identity chip | 40 × 40 | icon only |
| 4. Text chip | smaller | state |

### gauge

| Tier | Min size | Layout |
|------|----------|--------|
| 1. Full | 160 × 160 | half-arc top, value+unit centred, name + range below — HA reference |
| 2. **Reflowed wide** | 200 × 60 | mini arc on left (square ratio), value · name stacked on right. _This is the layout the screenshot was missing._ |
| 3. Reflowed compact square | 80 × 80 | arc with value floating in the centre; no name, no range |
| 4. Severity ring | 48 × 48 | coloured ring at the active severity tier (green / yellow / red); colour signals trouble even without a value |
| 5. Text chip | smaller | "21.4 °C" |

### button

| Tier | Min size | Layout |
|------|----------|--------|
| 1. Full | 180 × 80 | icon centred, name below, ripple action target |
| 2. Icon-and-name square | 56 × 80 | icon top, name below |
| 3. Icon-only | 40 × 40 | icon (tinted by state when toggleable) |
| 4. Text chip | smaller | name |

### entities

| Tier | Min size | Layout |
|------|----------|--------|
| 1. Full | `w < 260` | title chrome + N rows — matches Wrap |
| 2. **Icon strip (reflow)** | `w ≥ 260` | rows repacked as a horizontal `glance`-style strip of icon · name · state cells |

Implemented as a **single-threshold width-axis** `RemoteSizeBreakpoint`
(`thresholdsDp = [260], axis = Width`): drag the widget wider and the
column of rows repacks into a row of icons. Two deliberate constraints,
both learned from the matrix preview:

- **Single threshold.** A multi-rung ladder lowers to nested
  `RemoteStateLayout`s, which alpha010 collapses to tier 0 at playback
  (#224, same as `GaugeCardConverter`) — a two-rung version pinned every
  widget cell to one tier.
- **Width, not height.** Every Fixed-mode surface (the matrix `@Preview`
  cell and the launcher) pins width and lets height follow, so only a
  width gate reads a stable canvas dimension and fires consistently. A
  height gate reads the wrap-content height in those surfaces and
  mis-selects. (`componentHeight()` _does_ work where height is pinned —
  e.g. the Wear S vs L gauge — which is why the axis is available; it's
  just not the right axis for a launcher list reflow.)

(No identity-only tier — entities is a list, the rows _are_ the
identity; the strip keeps which-and-how-many.)

## Data priorities

The degradation ladder above says _what composition_ to draw at each
canvas size. **Data priorities** answer the orthogonal question: when
the renderer has every layout but not every field, which fields stay
on screen?

Every card's data inventory falls into five priority bands. **P1** is
what makes the card a card — drop it and the user can't tell what
they're looking at. **P5** is the first thing on the chopping block
when the canvas tightens.

| P | Field role | What dropping it costs |
|---|---|---|
| **P1** | **Identity + key value.** The visual element (icon, dial, pictogram) plus the live state that motivates the glance. | Card becomes indistinguishable from a blank chip — defeats the glance entirely. |
| **P2** | **Disambiguating name.** Entity-specific label like "Living Room" or "Front door". | Two same-type cards look identical; the user can't tell which lamp is on. |
| **P3** | **Unit + qualifying state.** The suffix that turns the number into a reading (°C, %, min), or the secondary value that gives the primary one intent — target temp, brightness level, track title. | The number is meaningless without context, or the user sees current state without the intent. |
| **P4** | **Chrome and history hints.** Range labels (0–100), last-changed timestamps, severity captions, trend arrows, dividers, status sub-text. | Card loses polish but still reads. First to drop on tight surfaces. |
| **P5** | **Bulk content.** List rows, forecast days, sparkline samples, calendar events, log entries — anything time-series or N-of-many. | Card collapses to its identity row only. List/graph cards stop _being_ list/graph cards; acceptable only if the alternative is overflow. |

The ladder rungs map onto these directly:

- **Full** carries P1 + P2 + P3 + P4 + P5.
- **Reflowed** keeps P1 + P2 + P3; P4 compresses, P5 trims.
- **Identity + value** keeps P1 + P3; P2 drops.
- **Identity** keeps P1; everything else drops.
- **Value** falls back to the textual P1 + P3 with no visual — last resort.

**Rule of thumb:** drop priorities right-to-left as the canvas
shrinks. Never drop P1 — if the canvas can't carry the identity,
switch to a smaller identity (icon-only chip) before stripping it.

### Per-card data inventory

| Card | P1 (identity + key value) | P2 (name) | P3 (unit / secondary) | P4 (chrome) | P5 (bulk) |
|---|---|---|---|---|---|
| tile | tinted icon + state | name | unit | last-changed | — |
| entity | icon + state | name | unit | — | — |
| button | tinted icon | name | — | — | — |
| gauge | half-arc + value | name | unit | min/max, severity | — |
| light | tinted icon + on/off | name | brightness | colour swatch | — |
| picture-entity | image | name | state badge | overlay scrim | — |
| sensor | icon + state | name | unit | trend arrow | history sparkline |
| statistic | icon + value | name | unit, period | trend | — |
| thermostat | mode icon + current temp | room name | target temp + HVAC action | min/max range | controls strip |
| humidifier | icon + current humidity | room name | target + action | min/max range | controls strip |
| alarm-panel | shield icon + state | panel name | — | last-changed | keypad |
| media-control | art + play/pause | speaker name | track title | artist, progress | seek bar |
| bambu-spool | filament swatch + name | tray label | remaining % | material code | — |
| heading | text | — | — | — | — |
| markdown | text body | title | — | — | — |
| clock | clock face | — | timezone | — | — |
| picture | image | name (overlay) | — | — | — |
| entities | first row | title | — | dividers | additional rows |
| glance | first cell | title | unit chip | — | additional cells |
| area | name + dominant entity | area name | summary count | — | per-entity grid |
| picture-glance | image | title | — | — | entity chip strip |
| picture-elements | image | — | — | — | positioned overlays |
| entity-filter | matched count | filter label | — | — | matched rows |
| vertical / horizontal / grid stacks | first child's P1 | first child's P2 | — | — | additional children |
| weather-forecast | current icon + temp | location | unit | feels-like, humidity | forecast days |
| logbook | latest entry | title | — | timestamps | older entries |
| history-graph | spark + latest value | title | unit | y-axis labels | history series |
| statistics-graph | bar + latest value | title | unit, period | y-axis labels | stat series |
| todo-list | counter | title | — | — | item rows |
| calendar | next event icon | title | event time | venue | additional events |
| bambu-print-status | progress arc + % | printer name | layer x/y, time remaining | nozzle/bed temps | thumbnail, sub-stages |
| bambu-print-control | progress + status | printer name | action buttons | — | extended controls |
| bambu-ams | active tray colour + filament | AMS name | remaining % | tray index | per-tray rows |

### Wear data-layer reality

The wear data-layer proto (`LiveValues` in `WearSyncProto.kt`) carries
**state + friendly_name + unit + device_class** per entity, and nothing
else. Mapped onto the priority bands:

- **P1** (state) ✓
- **P2** (friendly_name) ✓
- **P3** (unit) ✓ — _but only for cards whose P3 is the unit suffix_,
  not those whose P3 is a secondary value (target temp, brightness,
  track title, media position).
- **P4** ✗ — no last-changed, no trend, no severity, no progress fields.
- **P5** ✗ — no history, no forecast, no list payloads, no calendar
  events, no todo items.

That's the only data a Wear slot widget sees today. Two consequences
follow.

**1. Cards whose identity needs P4 / P5 advertise the small container
only on Wear.** Their P1 is bound to a payload the data layer can't
carry, so at the large container they would render as their stripped
tier with a lot of empty space. The set today is **`heading`,
`markdown`, `clock`, `weather-forecast`, `logbook`, `history-graph`,
`statistics-graph`, `todo-list`, `calendar`**. The list lives in
[`WearCardDataTier`](../../wear/src/main/kotlin/ee/schimke/terrazzo/wear/widget/WearCardDataTier.kt);
[`WearSlotsController`](../../wear/src/main/kotlin/ee/schimke/terrazzo/wear/sync/WearSlotsController.kt)
gates the large slot service on this classifier so the system widget
picker hides large for low-data cards regardless of the phone-side
`SlotSizePref`. (If the user explicitly chose `LargeOnly`, the
controller falls back to the small variant rather than hiding the slot
entirely — they wanted _some_ surfacing.)

**2. Cards whose P5 is a list of additional _entities_ can fill the
large container** by feeding more entities through the data-layer
(each carries its own P1 + P2 + P3). `entities`, `glance`, `area`,
`picture-glance`, `picture-elements`, `entity-filter`, and the
`*-stack` cards all benefit from a richer fixture at the large size
and a leaner one at the small size — same composition, different
payload, demonstrating how the renderer's ladder picks up the
breakpoint.

The per-card preview convention in
[`CardSlotWidgetPreviews.kt`](../../wear/src/main/kotlin/ee/schimke/terrazzo/wear/widget/CardSlotWidgetPreviews.kt)
follows directly from those two rules:

- **Small-only** `@Preview` for the low-data card types listed above.
- **Separate `*Small` / `*Large` fixtures** (the small one stripped,
  the large one filled) for list-shaped cards.
- **A single shared fixture, both `@Preview` sizes**, for single-entity
  cards (tile, button, gauge, thermostat, etc.) — the data is the
  same; the renderer's own ladder picks the right tier.

A reviewer scanning the wear preview list should see this shape
mirrored: one small entry for each low-data card, a small+large pair
for each multi-entity card, and a matching pair for single-entity
cards.

## Implementation guidance

### Where the variants live

Compact-tier composables are **card-specific** and ship next to the
full composable in `rc-components`. The mini-arc gauge belongs in
`RemoteHaGauge.kt` as a sibling of the full version; the icon-only
tile chip in `RemoteHaTile.kt`. Generic helpers like
`CompactStateChip` are the _value-fallback_ tier (rung 5), not the
compact tier — they exist so cards without a deliberate identity-only
form still degrade safely.

### Where the ladder is declared

Each converter's `Render` in Fixed mode wraps its content in a single
breakpoint helper that takes the ladder thresholds and a tier-keyed
content lambda. The thresholds are co-located with the converter so a
reviewer can read the full ladder in one place — no breakpoint
constants scattered across helper files.

### Threshold conventions

- Thresholds are **minimum dimensions** for the variant to look right,
  derived from the variant's smallest legible composition. Not "small
  starts at X dp".
- Both width and height. The 2D form is the default; width-only is
  shorthand for "this card's layout doesn't reflow vertically".
- Conservative — pick the dimension at which the variant still looks
  composed, not the one at which it stops crashing.

### Validation

Every card with a ladder has a `CardPreviewMatrix_<Card>` preview
showing all six cells (app + 3×widget + 2×wear). A tier change lands
when the matrix passes review at every size — the matrix is the
source of truth, not individual @Preview entries.

### Design-review checklist

Run every card (or shared layout family) through these five questions
at each of the three launcher sizes (`base−1`, `base`, `base+1`) plus
both Wear containers. They are the rubric a matrix review is graded
against; a card that can't answer all five isn't done.

1. **Hierarchy of data.** Is there a clear P1→P5 order on the cell —
   one dominant key element, a secondary value, then chrome? Or do
   competing elements read at the same weight? (Map to the per-card
   data inventory above.)
2. **Enrichment with size.** Does the card show _more_ as the cell
   grows — more rows, a promoted field, a larger key element — or just
   the same content with more whitespace? (Principle 7.)
3. **Key-element retention.** Is the one element that says _what kind
   of card this is_ (gauge dial, tile icon, weather glyph, lock
   pictogram) present at **every** size, including the smallest tier?
   If the smallest tier is a text chip, the icon was dropped too early
   (Principle 1 + the "hiding the icon first" anti-pattern).
4. **Space usage.** Does each tier fill or center within its cell, with
   no content glued to one edge over a blank half-cell? (Principle 8.)
5. **Appropriate sizes.** Does the advertised size ladder match what
   the data can fill? A card whose content tops out at `2×1` should not
   default a `4×2` base, and the matrix `±1` neighbours should both look
   intentional. If `base+1` is mostly empty, either the base is wrong or
   the card needs an Expanded tier.

A "no" on any row is a design bug, tracked per shared layout family in
the issues filed off the widget design review (see the layout-family
breakdown below).

### Shared layout families

Cards are not redesigned one-by-one — they cluster into a handful of
**shared layouts**, and the ladder/enrichment work lands once per family
and is reused. The families today:

| Family | Key element | Cards | Shared composable(s) | Ladder state |
|---|---|---|---|---|
| **Icon + state row/tile** | tinted icon | `tile`, `entity`, `sensor`, `statistic` | `RemoteHaTile`, `RemoteHaEntityRow` | tile/entity: `Full↔CompactStateChip`; sensor/statistic: none |
| **Icon-centred button** | large tinted icon | `button` | `RemoteHaButton` / `RemoteHaToggleButton` | `Full↔CompactStateChip` |
| **Arc-dial control** | the arc/dial | `gauge`, `thermostat`, `humidifier`, `light` | `RemoteHaGauge*`, `RemoteHaArcDial*`, `RenderArcDial` | thermostat/humidifier/gauge: Wide↔Full; `light`: **none (outlier)** |
| **Multi-entity list/strip** | first row/cell | `entities`, `glance`, `area`, `picture-glance`, `entity-filter`, `*-stack` | `RemoteHaEntities`, `RemoteHaGlance` | entities: `list↔strip`; others: none |
| **Hero + supporting detail** | condition/art/shield + value | `weather-forecast`, `media-control`, `alarm-panel` | `RemoteHaWeatherForecast*`, `RemoteHaMediaControl`, `RemoteHaAlarmPanel` | weather: `Full↔Wide`; others: none |
| **Bulk / time-series** | spark/list head | `history-graph`, `statistics-graph`, `logbook`, `todo-list`, `calendar`, `markdown` | per-card | none — Wear `SmallOnly` |
| **Picture / image** | the image | `picture`, `picture-entity`, `picture-elements` | `RemoteHaImage*` | none |

The "Arc-dial control" family is the reference implementation: a single
`RenderArcDial` width-ladder gives thermostat and humidifier a clean
Wide-row↔Full-card transition with the dial retained at every size. The
open work is bringing the other families up to that bar — see the
per-family issues.

## Anti-patterns

- **Defaulting to text.** Never the first move; rarely the right
  move. The screenshot is the cautionary tale.
- **Hiding the icon first.** The icon is the cheapest visual identity
  — hide name and value before the icon.
- **Treating size as a numeric budget.** Don't allocate dp ranges to
  tiers ahead of designing the variants. Declare layout variants
  first; the breakpoint picks the highest one whose minimum
  dimensions fit.
- **Pinning the base to wrap-mode dimensions.** Wrap mode is the
  expanded form. The base is the card's natural launcher shape
  (typically the Reflowed tier).
- **Width-only breakpoints by default.** Aspect matters. Wide-thin
  and tall-square cells of the same width want different layouts.
- **Ad-hoc thresholds.** Collect ladder thresholds in one place per
  card so reviewers can audit all transitions in a single read.

## Known gaps (today vs the philosophy)

The current state is the placeholder, not the philosophy:

1. `RemoteSizeBreakpoint` now takes a `BreakpointAxis` — `Width`
   (default) or `Height`. Two playback realities, both confirmed against
   the matrix preview, constrain how it's used: (a) only a **single**
   threshold fires — multi-rung ladders lower to nested
   `RemoteStateLayout`s that alpha010 collapses to tier 0 (#224); and
   (b) a gate only reads a **stable** value on the axis the surface
   *pins* — launcher/matrix cells pin width and wrap height, so a height
   gate mis-selects there (it works where height is pinned, e.g. the
   Wear gauge). A *true* 2-D / aspect gate remains blocked (#224). Net:
   `entities` reflows on a single width threshold; per-card ladders
   should pick the one pinned axis that best discriminates their
   variants — see `GaugeCardConverter`.
2. Tile / entity / button / gauge ship two-tier ladders
   (`Full → CompactStateChip`). That's the value-fallback path,
   skipping every reflow / identity tier above it. Each card needs
   the worked-example ladder above.
3. Runtime tier selection **does** fire — the matrix preview shows tile
   switching chip↔full and entities switching list↔strip per size. Two
   gotchas were shaped around: the `componentWidth()` named expression
   only materialises when a visible node references it (the transparent
   forcing-`RemoteText` in `RemoteSizeBreakpoint`), and the default
   `RemoteStateLayout` swap is a 300 ms `FADE_IN/FADE_OUT` whose
   mid-flight frame the in-process preview player captures, leaving the
   outgoing branch ghosting behind the incoming one. The breakpoint
   pins the swap to zero duration (`immediateSwap`) so only the selected
   branch is ever drawn. (Binding branch opacity via `alpha(select(…))`
   does **not** work — the derived float doesn't materialise (#224) and
   blanks the selected branch too.)
4. Compact-tier composables don't exist yet. The first card to land
   should set the file convention — the mini-arc gauge is a good
   candidate because it has an obvious reflow target and is the card
   the screenshot called out.
5. **`CompactStateChip` is wired as the _second_ tier, not the last.**
   `tile`, `entity`, and `button` go `Full → CompactStateChip` at a
   single 120 dp gate, so the `1×1` cell renders as top-left text with
   **no icon** — a direct violation of Principle 1 and the "hiding the
   icon first" anti-pattern, visible in the matrix today. Each needs an
   icon-only identity tier between `Full` and the text chip; the chip
   should only appear when even a `40×40` icon won't fit.
6. **Large cells pad with whitespace instead of enriching (Principle
   7).** Confirmed in the matrix at `base+1` for `tile`, `entity`,
   `button`, `entities`, and `weather-forecast`: content pins to the
   top-left and the bottom half of the cell is blank. The minimum fix
   is centering (`No dead space`, Principle 8); the better fix is an
   Expanded tier. Tracked per layout family in the design-review issues.
7. **`light` is outside the arc-dial family.** `thermostat` and
   `humidifier` route through `RenderArcDial` and get the Wide↔Full
   ladder + dial retention for free; `LightCardConverter` calls
   `RemoteHaArcDial` directly with no `CardSizeMode` branch, so it never
   reflows or degrades. It should join the shared `RenderArcDial` path.
8. **Gauge matrix shows the #309 overlay artifact.** The Wear L /
   widget cells render both `RemoteHaGaugeWide` and
   `RemoteHaGaugeStacked` on top of each other (double value + double
   arc). This is the alpha010 `RemoteStateLayout` overlay bug (#309),
   not a layout-design fault, but it makes the gauge family un-reviewable
   in the matrix until the visibility-modifier workaround there is
   re-confirmed against the current alpha.
