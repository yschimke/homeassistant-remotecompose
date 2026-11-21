# Player patches

Fixes to rccore (the RemoteCompose engine in `remotecompose-experiments`, which kobento-mcu
builds) that the panel needs, until they land upstream. Apply in the engine checkout:

```sh
cd /config/esphome/kobento/remotecompose-experiments
patch -p1 < rccore-panel.patch
```

`rccore-panel.patch`:

- **Measure once per constraints.** A layout measures each child to size itself, then again
  with its final size, so a leaf nested in N layouts was measured 2^N times a frame (a screen
  of cards: one text 14 016 times, 95 290 text measures a frame). A component measured
  earlier in the same pass with the same constraints keeps its measures. 10-25x faster on a
  card or a screen of them, and it lays a grid out as the reference does.
- **A state change in one frame without layout animations.** A `StateLayout` reads its index
  when it paints and lays the new state out on the next frame, for the transition. With layout
  animations off (`setLayoutAnimationEnabled(false)`, the panel's default) there is no
  transition: the new state is measured in the frame that sees the change.
- **A loading layout settles without animating.** With layout animations on, the settling
  below was animated: every card load slid its rows into place (on the wall screen, 60 px over
  320 ms). A document's first layouts, until one publishes the bounds the last did, snap.
- **An animation frame doesn't measure again.** A layout animation in flight made every frame
  re-measure, though only the interpolation moves. The measured bounds are kept, and a frame
  that would measure only for the animation (same size, epoch and state, no held exit ending)
  re-reads the expressions and blends from them: on the host, layout per animation frame goes
  from ~250 us to ~24 us, and every frame of six availability transitions hashes the same as
  with a full measure (`RC_NO_FAST_ANIM=1` measures, for comparison).
- **A finger moving doesn't lay the document out again.** Every change to the touch position or
  velocity moved the layout epoch, so on the panel each frame of a press measured (26 ms). Those
  variables are read only through expressions, which listen to them; with no listener, or only
  paint-time ones, they leave the epoch alone. None of the wall screens listens to them; drags
  on all four draw the same with `RC_TOUCH_MOVES_LAYOUT=1` (the old behaviour) as without.
- **A layout settles in frames asked for.** A layout reads what the last one published (a
  grid's columns from its width, an icon's scale from its box), so the first frame of a screen
  isn't settled: a player drawing every frame settles in two or three, but the panel draws
  only when asked and kept the first (two columns where there are three, no icons). A layout
  whose published bounds moved asks for the next frame, four in a row at most.
- **No layout on a frame where nothing it reads moved.** A document holding a `StateLayout`
  laid out every frame, in case its index flipped. It lays out when a state layout took a new
  index or is mid-transition; any other change already moves the layout epoch. A value change
  without layout animations drew in 0.39 ms instead of 1.07, a transition frame in 0.8 ms
  instead of 1.15 (desktop core); the animated end is pixel for pixel the instant one.
- **Bitmap fonts.** `DATA_BITMAP_FONT`, `DRAW_BITMAP_FONT_TEXT_RUN`, `DRAW_BITMAP_TEXT_ANCHORED` and
  `BITMAP_TEXT_MEASURE` are played, not skipped: a font is glyph metadata (characters, the
  `BitmapData` drawn for each, margins), and a run is laid out a glyph at a time from left to
  right, the longest glyph that matches at each place, as rc-players' Compose player does
  (`RcBitmapFontLayout.kt`), the anchored draw measuring without kerning as AndroidX does. Text
  indices count UTF-16 units, as on the wire. The glyphs go to the paint context as one run
  (`PaintContext::drawBitmapGlyphs`), which by default draws each as `drawBitmap` would.
- **A canvas's `CanvasOperations` paints.** A `CanvasOperations` directly under a canvas, with no
  content wrapper (as `coreTextAsBitmapFonts` writes a text's canvas), was filed as data and never
  ran in the paint pass: the canvas drew nothing.
- **Where a frame's time went.** `rccore::frameProfile()` holds the last `CoreDocument::paint`'s
  DATA pass, layout (measured, or reused with its expressions re-read) and paint times; rcstage
  logs them with its frame stats, and rc2png's `--live` lines print them.
- **`spacedBy` references.** A row, column or flow whose `spacedBy` is a reference (a float
  constant the document declares, as a stack of cards writes its gap) laid out with the
  reference's NaN: a column of two or more children was NaN tall and drew nothing.
- **Dynamic text colours.** A `TextLayout` with `FLAG_DYNAMIC_COLOR` (bit 16 of its align word)
  drew its `color`, a colour id, as an ARGB literal: transparent. Read as an id, as CoreText's.
- **`MatrixScale` pivots.** Its values are resolved when it paints, and a pivot written as the
  canonical NaN (no pivot, as AndroidX writes it) scales about the origin rather than reading
  variable 0: a tile's icon drew nothing.

`rc2png-live-timing.patch` (kobento-mcu, for `esphome/tools/wall-sim.py`): `rc2png --live` reports
the drawing a change costs, frame by frame, and `RC_NO_LAYOUT_ANIMATION=1` draws as a panel with
`layout_animations: false`. `--live-clock MS` steps the animation clock by MS a frame instead
of sleeping, so a transition draws the same frames every run; `RC_FRAME_HASH=1` prints each
frame's hash, `RC_PROFILE=1` each frame's data, layout and paint time, and `RC_OPCOUNTS=1` the
document's operations by name. `--glyph-pack FILE` (repeatable, when built with `-DTZWALL_DIR`)
composes text from glyph packs as the wall panel does.

`kobentovg-text-cache.patch` (kobento-mcu, `cd /config/esphome/kobento/kobento-mcu && patch -p1 < …`):
a plain text run (a solid fill, drawn at scale 1, as every panel text is) is rasterised once to
an image and blitted on later frames. ThorVG otherwise tessellates every glyph each frame: half
a frame's cost, and with it a screen's frame drew in 0.64 ms on a desktop core instead of 1.49.
Bounded at 4 MB; runs not drawn lately go first. `RC_NO_TEXT_CACHE=1` (host) draws without.

`kobentovg-damage.patch` (kobento-mcu, on top of the text cache, same directory): draw only what
changed. With `setDamageTracking(true)` the paint context hashes every paint of a frame (path,
fill, stroke, transform, opacity, clip; a text image by its cache key, a bitmap by its
version), diffs the hashes with the previous frame, and redraws only the changed rectangles
through ThorVG's viewport. Each target buffer keeps its own backlog, so double buffering stays
exact; content it can't read back (a mesh, an uncached text) is redrawn every frame. rcstage
turns it on for a board that defines `BOARD_PRESENT_DAMAGE` and hands the board each frame's
rectangles, so the RGB565 conversion copies only those too; a frame where nothing changed
isn't presented. ThorVG's `Canvas::viewport` is allowed while paints are pending (patched in
`third_party/thorvg`), so the region can be chosen once the frame's paints are known.
On the host (`RC_DAMAGE=1 rc2png … --bg FF000000 --live NAME=VALUE`, `RC_DAMAGE_LOG=1` for each
frame's rectangles) a value change redraws its text, 108x32 px instead of 800x480, and both
buffers match a full redraw at the end of the run on every wall screen, with layout
animations on and off.

`kobentovg-pictures.patch` (kobento-mcu, on top of the damage patch, same directory): pictures a
document names by URL. A `BitmapData` of `ENCODING_URL` (a card design's `rc-slot://camera.s0/
entity_picture`) is kept as its URL, and when it's drawn the paint context asks the host's
`ThorvgPaintContext::BitmapSource` for it at the size it shows: the part of its destination
inside the clip it's drawn in, in pixels. Its size in the document is a placeholder's (512 x
512), so a crop draw's square destination is cut to the box that shows, and the host gives the
picture cropped to that box (or smaller: it's scaled up). The host decodes it: when it has a
version the paint context hasn't, it hands over the picture's pixels, which become the image's
only copy (ThorVG's own decode keeps several full-size buffers on the way). `rcstage::redraw()` (any task) draws
the showing document again, for a host that just got a picture.

`kobentovg-direct-pictures.patch` (kobento-mcu, on top of every other kobento-mcu patch, same
directory; prototype): pictures drawn without a decoded copy. A `BitmapSource` whose `direct()`
is true only says which version of a picture it has (`pictureVersion`). ThorVG draws a placeholder
in its box (id'd by the version, so a new picture is damage). After ThorVG draws the frame, for
each picture box the frame redrew:
1. ThorVG redraws the box with only what was painted up to the picture, which leaves just the
   card behind it and the placeholder.
2. The host decodes the picture into the target (`drawPicture`).
3. Corners the placeholder didn't fill (a rounded clip) are put back.
4. ThorVG redraws the box with only what's painted over the picture (a picture-entity's
   name/state strip, the scroll bar).

It also adds a host check to rc2png: `RC_DIRECT_PICTURES=1` draws every URL picture as a gradient
this way.

`rccore-pictures.patch` (remotecompose-experiments, on top of `rccore-panel.patch`, applied as it
is): `LAYOUT_IMAGE` paints. An image component was read and laid out but drew nothing; it now
draws its bitmap into its content box as AndroidX's `ImageLayout` does, scaled by its content
scale from the bitmap's declared size (`DRAW_BITMAP_SCALED`'s `ImageScaling`) and clipped to the
box. Laid out with no size of its own, it wraps to the bitmap's declared size, as
`ImageLayout.computeWrapSize` does.

`rcstage-scroll-bar.patch` (kobento-mcu, on top of `kobentovg-pictures.patch`, same directory): a
scroll bar down the right of the screen. `setSideWidth(dp)` (before `start()`) keeps that much of
the screen's right edge, above the navigation bar, for it: documents lay out in what's left.
`setSideThumb(top, length)` (fractions of the bar, any task) places its thumb, and a tap on the
bar is `Hooks::sideTap(at)` (0 at its top, 1 at its bottom), never the document's.

The same patch makes each frame cheaper to prepare:

- **Paints outside the redrawn rectangles are dropped** before ThorVG prepares them (it
  strokes and transforms a shape before it culls it), which takes a value-change frame from
  0.53M to 0.34M host instructions to draw.
- `drawText` checks the cache before it builds a `tvg::Text`.
- **Bitmap-font runs are composed once.** A run's glyphs sit whole pixels apart, so they are
  composed into one image at their offsets and drawn as one picture (on a whole device pixel when
  unscaled, so a redrawn rectangle draws it as a full frame does), kept like text runs: one paint
  and one colour-filtered copy a run, not one a glyph. A `RAW8` bitmap (a white-mask glyph) is kept
  at a byte a pixel and composed as white, which the paint's colour filter tints.
- **Colour-filtered copies stay put until drawn.** The cache of colour-filtered images was a
  16-entry vector that, past 16, erased its oldest and shifted the rest along, so a frame tinting
  more than 16 images (a live text's glyphs, one each) drew other images' pixels. It is a list now,
  and drops only copies no frame drew lately.
- **The text-run cache sweeps stale runs past 128 KB**, not 4 MB. Every new value is a new run,
  and on the panel stale runs filled PSRAM (52 KB free after a few hours, from 220 KB).
- **rcstage logs where the last frame's doc time went:** text runs rasterised (and of those, how
  many were composed from glyphs) and their time, and the time spent dropping the last scene.
- **Text runs from glyph coverage.** `ThorvgPaintContext::setGlyphSource` hands the paint context
  a source of glyph coverage by face, whole pixel size and code point (the wall's glyph packs,
  `esphome/components/terrazzo_wall/glyph_packs.h`). A text run the cache misses, when the source
  has every one of its glyphs, is composed from them instead of rasterised by ThorVG: each glyph
  at the pen position ThorVG's advances give (as the layout measured the run), rounded to a pixel,
  in the run's colour. A run with a glyph the source lacks draws with ThorVG as before, and the
  source is told each glyph it lacked. On the host, `rc2png --glyph-pack FILE` (built with
  `-DTZWALL_DIR=<this repository>/esphome/components/terrazzo_wall`) draws as the panel does;
  `RC_NO_GLYPH_SOURCE=1` draws without, `RC_GLYPH_DEBUG=1` lists each run composed.

Tried and dropped: a glyph cache that composed runs from glyphs the panel rasterised itself, once
per size and quarter-pixel phase (glyph packs draw nothing on the panel: their coverage comes
built, or fetched). ThorVG's draw costs about 0.6 ms a glyph on the panel, so a first frame
took 156 ms of text instead of 94, and a changed value saved only a few ms. Its memory also
pushed the panel's PSRAM (60-230 KB free) past what the design fetch needs: the fetch's
response string failed to grow and the panel crashed and rolled back. Text stays one image per
run.

`rcstage-timing.patch` (kobento-mcu, on top of `rcstage-scroll-bar.patch`, same directory): where
a document's way onto the screen goes. Each load logs how long the document waited for the player
task and how long it took to load, and its first presented frame logs how long after the load it
came, with the breakdown of that frame (data, layout, paint, raster).

`rcstage-preload.patch` (kobento-mcu, on top of `rcstage-timing.patch`, same directory): a
document laid out ahead. A document's first frame is three passes (two unseen, while its layout
settles) of some 180 ms on the panel. `rcstage::preload(bytes, name, fit, values)` loads one
while the player is idle and runs those passes a step at a time between frames, so a touch waits
for one step at most; a `show()` of the same bytes and name then swaps it in settled, its first
frame one pass. One is kept at a time, and none while PSRAM is short. Its paint context asks for
no pictures until it shows (`ThorvgPaintContext::setPictures`), so they're fetched for the screen
showing them.

`rccore-settle.patch` (remotecompose-experiments, on top of `rccore-pictures.patch`): a layout
settles within the frame it paints. A layout reads what the last one published (a grid's columns
from its width, then a cell's icon from the cell), through the document's data pass, which ran
once a frame: a screen took up to three frames to settle. After a layout the root now runs the
document's data pass again, and lays out again while that moved anything the layout reads (the
layout epoch), six times at most, before it paints. On 22 wall screens, with layout animations
off as on the panel, the first frame is the settled one (it took 1 to 3 before), each lays out
once, and later frames don't move.

`rccore-state-first-frame.patch` (remotecompose-experiments, on top of `rccore-settle.patch`): a
document's first frame shows the state its state layouts settle on. The panel runs with layout
animations **on** (the note above assumed off). With them on, a state layout's index moves only
at paint and plays a transition. So the settle passes, which pick a camera screen's column count
through one, painted the state chosen before the layout settled: two columns, then a switch to
three on the next frame, animated, about a second later. A picture fetched for the first box was
fetched again for the second. Until a document has painted a frame there is nothing on screen to
transition from, so its state layouts take their index at once, as with animations off. A later
change still animates.

`rcstage-settled.patch` (kobento-mcu, on top of `rcstage-preload.patch`, same directory): with
`rccore-settle.patch` a document's first frame is settled, so a load paints no unseen frames before
it (it painted two).
