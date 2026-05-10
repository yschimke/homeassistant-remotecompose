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

## The degradation ladder (recipe)

Per card, declare an ordered list of layout variants from
most-detail to least-detail. At playback the runtime picks the
highest tier whose minimum dimensions fit the canvas:

1. **Full** — HA reference. Same as wrap mode.
2. **Reflowed** — same elements, repacked. Often a column-to-row
   swap. The cell where the user's screenshot lost its gauge belongs
   here.
3. **Identity + value** — drop the name; keep icon/dial + state.
4. **Identity** — icon/dial only. Colour, shape, badge convey the
   state.
5. **Value** — text chip. Last resort.

A card doesn't have to populate every rung — `entities` skips the
identity-only tier (it's a list card; the rows _are_ the identity).
The ladder is per-card, not a fixed cascade.

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
| 1. Full | 240 × 96 | title chrome + N rows |
| 2. Title-and-rows | 200 × 64 | rows trimmed to fit height |
| 3. First-row-only | 160 × 40 | title hidden, single row visible |
| 4. Title chip | smaller | title + "+N more" badge |

(No identity-only tier — entities is a list, the rows _are_ the
identity. Once the chrome can't fit, the next-most-useful thing is
which-and-how-many.)

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

1. `RemoteSizeBreakpoint` reads `componentWidth()` only. Need a 2D
   variant before any reflow ladder can land.
2. Tile / entity / button / gauge ship two-tier ladders
   (`Full → CompactStateChip`). That's the value-fallback path,
   skipping every reflow / identity tier above it. Each card needs
   the worked-example ladder above.
3. The Fixed-mode previews currently render uniformly stripped (the
   screenshot). That's a sign the runtime tier selection isn't
   firing — the named-expression `componentWidth()` may be capturing
   a literal at recording time rather than evaluating live at
   playback. Confirm before investing in per-card ladders.
4. Compact-tier composables don't exist yet. The first card to land
   should set the file convention — the mini-arc gauge is a good
   candidate because it has an obvious reflow target and is the card
   the screenshot called out.
