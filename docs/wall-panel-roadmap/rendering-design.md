# Rendering design: RemoteCompose on a microcontroller

How to play RemoteCompose (RC) documents of Home Assistant cards on an ESP32-S3 panel fast enough
that a value change is a few tens of milliseconds and a new screen is well under half a second.
Read [hardware.md](hardware.md) first for the budgets this design is shaped by.

## What the documents look like

An RC document is a byte stream of operations: data (named values, expressions, text, paths,
bitmaps), a layout tree (rows, columns, boxes, flows, canvases, state layouts) and paint
operations inside layout components. A document for a Home Assistant card typically has:

- **A shallow-to-medium layout tree.** A screen is a vertical stack of shelves, each a row of
  cards, each card a box of rows and columns. Five to eight levels deep is normal; each level's
  measure cost multiplies if you measure naively.
- **Lots of text.** 10–20 runs per screen: card titles, entity names, formatted values ("21.5 °C",
  "583.56 Mbit/s"), headings. Text is the single largest drawing cost.
- **Icons as vector paths.** Material Design Icons are captured into the document as paths. An
  on/off icon carries both glyphs and an expression picks one.
- **Named slots for live data.** Values are declared by name in the user domain
  (`USER:sensor.s0.state`, `USER:light.s0.is_on`, `numeric_state`, `is_available`, `name`, …).
  Text, colours, visibility and sweep angles are expressions over them.
- **State layouts.** A component that shows one of N children by an index (available /
  unavailable, on / off), possibly with a transition between them.
- **Few images.** Pictures (cameras, entity pictures) come by slot and are fetched separately.
  Charts (history graphs) are paths computed from data.
- **Wire size.** 1–30 KB per screen of cards.

Everything below follows from three facts: values change often and structure rarely, text
dominates paint cost, and memory is the hard limit.

## The pipeline

Each frame:

1. **Data pass.** Apply pushed values, evaluate expressions that depend on them.
2. **Layout.** Measure and position, only if something layout reads has moved.
3. **Paint.** Walk the tree emitting drawing operations to the paint context.
4. **Raster.** The vector rasteriser (ThorVG) fills only the damaged rectangles.
5. **Present.** Convert those rectangles to RGB565 and hand the buffer to the LCD.

Make every stage report its time per frame from day one ([testing](testing-and-verification.md)).

## Layout

### Measure once per constraints

A layout measures each child to size itself, then measures again with the final size. Done
naively, a leaf nested in N layouts is measured 2^N times. Cache each component's measure result
keyed by the constraints it was measured with, valid for one layout pass. This alone makes layout
10–25x faster on a screen of cards.

### Lay out only when an input moved

Keep a **layout epoch** that moves only when something layout reads changes: a value a size or
visibility expression depends on, the canvas size, a state layout's index. A frame whose epoch
didn't move re-uses the last layout (0.7 ms instead of 35 ms on the panel). Two traps:

- **Touch.** Touch position and velocity are variables; if every change to them moves the epoch,
  every frame of a press re-measures. Move the epoch for them only when an expression layout
  reads listens to them. Typical card designs never do.
- **State layouts.** Don't lay out every frame "in case" the index flipped. Lay out when it took a
  new index or is mid-transition.

### Settle on load without animating

A layout reads what the previous one published (a grid's column count from its width, an icon's
scale from its box), so the first frame of a new document isn't settled. A player that draws every
frame settles in two or three; a panel that draws only when asked must **ask for the next frame
while published bounds are still moving** (cap it at about four). And with layout animations on,
those settling passes must **snap**, not animate: a document's first layouts, until bounds stop
changing, are not "changes".

### Animation frames don't measure

While a layout animation runs, only the interpolation moves. Keep the measured bounds and, on a
frame where size, epoch and state are unchanged and no exit is ending, only re-read the animated
expressions and blend. ~250 µs → ~24 µs per frame on a desktop; same pixels.

### Without layout animations, a change is one frame

When animations are off (the panel's default), a state layout should lay out its new state in the
same frame it sees the change, not the next.

## Paint and damage

### Draw only what changed

Turn on damage tracking in the paint context:

1. Each paint of a frame gets a hash: geometry, fill, stroke, transform, opacity, clip; a cached
   image by its cache key; a bitmap by its version.
2. Diff this frame's hashes against the previous frame's. The union of the changed paints' bounds
   is the damage.
3. Rasterise only those rectangles (the rasteriser's viewport), and present only them.
4. Double buffering: keep a damage backlog per target buffer, so each buffer is brought up to date
   with what it missed.
5. Content you can't hash reliably (a mesh, an uncached text) is redrawn every frame.
6. A frame with no damage isn't presented at all.

A value change then redraws its text, about 108x32 px, instead of 800x480.

### Cull before the rasteriser sees it

Vector rasterisers typically stroke and transform a shape before they cull it. Drop paints whose
bounds miss every damage rectangle before handing them over: 0.53M → 0.34M instructions for a
value-change frame on the host.

### Whole pixels for images

A cached image drawn at a fractional position is resampled differently when clipped to a damage
rectangle than when drawn whole. Snap unscaled image draws to whole device pixels, or damage
redraws won't match full redraws.

## Text

Text is most of the cost. Three layers, cheapest first.

### 1. One cached image per text run

Rasterise each plain text run (solid fill, unscaled, unrotated: nearly all panel text) once into an
ARGB image at its sub-pixel phase (quarter pixels are enough), and blit it on later frames. Key it
by text, face, size, colour and phase. Check the cache before building any text object: a hit
needs nothing else.

- **Bound it by frame, not by age.** Keep the runs the current frame draws, plus a small budget
  (64 KB) of older ones; past the budget, drop runs not drawn this frame *before* allocating a new
  one. Every new value is a new run, and an unbounded cache (we measured 4 MB) fills PSRAM within
  hours. Keeping "the last two frames" isn't enough either: a layout that settles over its first
  frames draws its text at a new sub-pixel phase each frame, so two frames of runs is two or three
  screens of text, and a screen of cards' runs is already ~190 KB at 32 bits a pixel.
- **Size a run to its ink.** A run composed from glyph coverage knows its exact ink box; a padded
  box (a quarter of the text size on each side, which a rasterised run needs for overhangs)
  nearly doubles a 15 px run's image.
- **Under memory pressure, skip, don't fall back.** If the image won't fit (check the largest free
  block with a margin), skip that run this frame. Drawing it uncached needs more memory and
  crashes the rasteriser.
- Text with a gradient, stroke, italic transform or non-identity scale goes to the rasteriser
  directly; it's rare on cards.

### 2. Glyph packs: compose runs from pre-rasterised glyphs

A cache miss still costs ~5 ms a run with the vector rasteriser; a screen load has 15 of them
(~75 ms). Compose runs from glyph coverage instead: ~0.3 ms a run.

- **Packs, not per-glyph caches on the device.** A pack is one face at one pixel size: per glyph,
  its metrics and its coverage trimmed to the ink, at 4 bits a pixel. The server rasterises it
  (see [protocol-and-server.md](protocol-and-server.md)). Don't rasterise glyphs on the panel to
  cache them: at ~0.6 ms a glyph it's slower than rasterising runs, and the cache costs memory the
  panel doesn't have.
- **Placed by the renderer's own metrics.** Layout measures text with the renderer's font. Put
  each glyph at the pen position the renderer's own advances give, rounded to a whole pixel, so
  the run is exactly as wide as layout made room for. The pack only supplies coverage.
- **All or nothing per run.** A run whose face, whole-pixel size and every character are in a pack
  is composed; otherwise it's rasterised as before, and the missing glyphs are reported so they
  can be fetched.
- **Into the same run cache.** Compose into the same cached image the rasteriser would have filled.
  Damage tracking, caching and blitting don't change.
- **Built in, then fetched.** Build the panel's few styles into the firmware (a typical wall
  screen uses six: Roboto Regular at 12, 13, 15, 17 and 22 px, Bold at 16; ASCII plus reading
  signs is ~100 glyphs, ~6 KB a style at 4 bits). Fetch the rest at run time, a few dozen
  characters a request, and keep them in NVS under a fixed cap (32 KB). Drop fetched packs when
  the built-in set changes.
- **Remember the cost of building in.** On this board the firmware runs from PSRAM, so six
  built-in styles cost ~40 KB of PSRAM.
- **Index with sorted vectors** in one allocation per style, never a map with a node per glyph.

Expected: text on a screen load from 75 ms to 24 ms, the run rendered within a few edge pixels of
the rasteriser's output.

| Coverage depth | Look at panel sizes | Bytes a glyph at 15 px |
|---|---|---|
| 8 bits | reference | ~74 |
| 4 bits | identical at 3x zoom | ~39 |
| 2 bits | very slightly rougher | ~22 |
| 1 bit | jagged, uneven stems | ~13 |

### 3. Bitmap fonts in documents (support them, don't rely on them)

RC has bitmap fonts: a font is glyph metadata (characters, margins, the bitmap id for each) and
each glyph is its own bitmap operation; there is no atlas. Play them correctly (longest match
first, kerning, anchored draws measured without kerning, UTF-16 indices) and **compose a run's
glyphs into one image** drawn once, tinted by the paint's colour filter for white-mask glyphs.
But for the panel's own text, glyph packs are better: bitmap fonts grow every document, and each
glyph is a separate image with its own overhead.

### 4. Emoji: colour glyphs, only for what the fonts lack

The panel's fonts have no emoji, and a colour emoji font is 25 MB. Split the emoji out: the
server rasterises them (Noto Color Emoji) into **colour glyph packs**, 32 bits of premultiplied
ARGB a pixel; everything else stays coverage. On the panel:

- **Detect**: read the character map (`cmap`, formats 4 and 12) of each font the panel loads, so
  it knows exactly which code points it lacks. Those come from the emoji face; skip joiners and
  variation selectors (U+200D, U+FE00–FE0F), which draw nothing on their own.
- **Measure**: the layout's text width uses the colour glyph's own advance for those code points,
  so the run is as wide as it's drawn. When glyphs arrive, drop cached widths and run images and
  show the screen again so it's laid out with them.
- **Draw**: compose colour glyphs untinted into the same run image as the coverage glyphs.
- **Negotiate**: the panel asks for what it draws (`bits=4` for text, `bits=32` for emoji) through
  the Home Assistant integration's glyph route, which passes the query to the design server's;
  a 404 (a server without the route) or 503 means draw without, and the panel carries on.

A 22 px emoji is ~25x25 px, about 2.5 KB; two came to 5 KB.

## Icons

Icons arrive as vector paths. A heading card draws its configured `icon:` before its title, as
Home Assistant does; it's a design property (configuration, not a value), so a heading with one
is its own design. Treat them like text runs: rasterise each icon once at its size and
colour into a cached image, keyed by path id, size, colour and phase, bounded, and blit. An icon
picked by state (on / off) is two cache entries, both stable. Don't re-tessellate a path every
frame. Decoded bitmaps and colour-filtered copies follow the same rules: bounded caches evicted by
last-used frame, never pointers into containers that move.

## Images

Pictures (camera stills, entity pictures) are not in the document; a slot names them and the
panel fetches them scaled by the server to the box they're drawn in (JPEG, never scaled up).
Decode into a buffer sized for the box, keep only the visible one, and refetch when the server
says the picture changed. Budget them explicitly: one 400x300 ARGB image is 470 KB, more than the
panel has free. Decode to RGB565 or stream into the frame if you need large pictures.

## Data changes

**Never re-encode a document for a value.** The server sends each card's design once (by hash)
and afterwards only `name → value` changes. The panel pushes them into the playing document's
named slots. Rules:

- A document declares every value it reads by name; the server's values use the same names.
- A value change moves the layout epoch only if a layout expression reads it.
- Structure changes (an entity gaining an attribute that adds a row) come as a new design hash;
  that's the only time the panel loads a new document.
- **The first frame shows current values.** Push the current values before the document's first
  frame, not after. Nothing sweeps from zero or fades from a stale state on load; only later
  changes animate.

## Transitions

Two kinds:

- **Fades** (a state's cross-fade, a dimmed unavailable tile): apply the layer's alpha to each
  paint, never as a composited group (see [hardware.md](hardware.md#never-let-the-rasteriser-composite)).
- **Layout animations**: a value's text resizing its row, a slot appearing, an entity going
  unavailable. About 320 ms on the wall clock per change. Make them optional (off by default on
  the panel): each frame redraws only what moved and doesn't re-measure, but it is still ~20
  frames of raster instead of one.
- **Paging between screens**: a new document. Prefer a cut to an animated slide; a slide is a full
  raster every frame.

## What the server does for the panel

The panel can't afford to interpret what a phone player can, so the design server makes designs
**for the display**:

- Sizes as numbers, not expressions over density and font scale (the panel is density 1).
- Text as simple text layouts, not rich text.
- Values the panel won't push frozen into constants.
- Each card simplified to what its template draws (a heading without badges, markdown without
  inline images).
- A canvas the size of the screen, and cards packed into screens of the screen's height.
- Glyph packs on request, by face, size, depth and characters.

Details in [protocol-and-server.md](protocol-and-server.md).

## Memory discipline

- Every cache is bounded and sweeps by last-used frame.
- Check the largest free block before any allocation that can exceed a few KB; fail, skip or
  retry instead of crashing.
- One allocation per table, not one per entry.
- Count built-in data as PSRAM.
- Keep design blobs only for the current view; drop them when the view changes.
- Log what each cache holds with the frame stats, and PSRAM free / largest block whenever it moves.

## How to achieve high performance: checklist

- [ ] The renderer runs on a desktop host with the same code; every change is timed there first.
- [ ] Per-frame stats: data, layout (measured / re-used), paint, raster, text runs (composed),
      paints, memory.
- [ ] Measure once per constraints per pass.
- [ ] Layout epoch; touch and state layouts don't force layout.
- [ ] Settle-on-load snaps; animation frames don't measure.
- [ ] Values through named slots; first frame seeded with current values.
- [ ] Damage tracking with per-buffer backlog; culling before the rasteriser; no-damage frames not
      presented.
- [ ] Text-run image cache: this frame's runs plus a 64 KB budget, composed runs sized to their ink, skip-on-no-memory.
- [ ] Icons and images cached as images, bounded.
- [ ] Glyph packs for the panel's text styles; fetch the rest.
- [ ] Every large allocation guarded by a largest-free-block check.

## Order to build in

1. **Play a document on the host and on the device**, full redraw each frame. Get frame stats and
   memory logging in now.
2. **Named slots and the long poll**: values change without new documents. First frame seeded.
3. **Layout: measure once per constraints**, then the layout epoch. Re-measure the value-change
   cost (should drop below 20 ms of document time).
4. **Damage tracking and culling.** Raster per value change drops from ~100 ms to ~8 ms.
5. **Text-run cache**, bounded. Then icon and image caches.
6. **Allocation guards** on HTTP bodies and caches, and the screenshot endpoint.
7. **Screens and paging**: the server packs cards into screen-sized documents.
8. **Glyph packs**: server route, built-in styles, run-time fetch.
9. **Layout animations** last, optional, with settle-on-load and the animation fast path.
10. **Bitmap fonts** only if documents from elsewhere use them.
