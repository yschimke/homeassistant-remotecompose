# Watch faces on the panel

How a published Wear OS watch face (a `watchface` card, [HOME_ASSISTANT.md → Watch
faces](../HOME_ASSISTANT.md#watch-faces)) plays on the ESP32-S3 panel, and what it takes to keep
its frame rate workable. Read [hardware.md](hardware.md) and [rendering-design.md](rendering-design.md)
first: this doc applies their budgets to a document that, unlike a card, changes on its own.

**Status, 2026-11:** the server side of the first step is built. Nothing here has been measured on
the panel. The per-tick costs below are estimates from the card numbers in
[hardware.md](hardware.md#where-the-time-goes), and they are marked as estimates.

## How a face reaches the panel

1. The design server lists the site's public faces (`GET /api/watchface-push/v1/designs` on
   ui.coo.ee). It downloads the face's APK, checks the APK's SHA-256 against the listing, and
   compiles `res/raw/watchface.xml` and its `res/` tree to `.rc` with compose-ai-tools'
   `dwf-remote-compose` (`design-server/.../WatchFaces.kt`).
2. The panel resolves the card like any other (`/api/cards/resolve` with its `player`). It gets
   the face folded for its profile (`PlayerFolding`, `kobento-mcu`) under that design's own sha.
   The face's named values come back as the compiled defaults. Nothing pushes values for it.
3. **The clock is the player's.** Hands and time text are expressions over RC's system time
   variables (`TIME_IN_SEC`, `TIME_IN_MIN`, `TIME_IN_HR`, `CONTINUOUS_SEC`), never named values.
   The wall API has no timer ([wall-display-api.md](../architecture/wall-display-api.md)), and it
   doesn't need one.

## Why a face is different from a card

A card is idle until a value arrives. A face asks to be drawn on its own, and how often depends
only on which clock variables it reads:

| The face reads | The player needs a frame | Why |
|---|---|---|
| `CONTINUOUS_SEC` (a `<Sweep>` second hand, `[MILLISECOND]`) | every vsync | the angle moves every frame |
| `TIME_IN_SEC` only (a ticking second hand; the translator's hour and minute hands also read `[SECOND]`) | once a second | nothing moves between seconds |
| `TIME_IN_MIN` and coarser | once a minute | not produced by the translator today |

rc-players already classifies these: `referencesContinuousSystemVariable()` and
`referencesMovingSystemVariable()` (`RcSystemVariables.kt`). Its Compose player sleeps a
per-second document to the next second.

**What's built:** for a small player (any resolve that names a `player`, which a wall panel always
does), the server strips the face's `<Sweep>` elements before compiling. The second hand then
ticks and the document reads whole seconds only (`WatchFacesTest` checks this). A card can set
`sweep: true` to keep the author's sweep, or `sweep: false` to tick on a phone too.

## The frame budget

**Measured** in [hardware.md](hardware.md), for cards:
- Full-screen raster: 100–110 ms.
- A damage-limited 108x32 px redraw: ~8 ms.
- A value change: 18.5 ms of document time (paint 12.3 ms) plus 8.3 ms of raster.

**Estimated** for one tick of a 450 px face, alone on screen:
- **Damage.** The second hand's old and new bounds, about 6° apart, add up to roughly the bounds of
  one hand: up to ~150x150 px on a diagonal. That is ~6% of the screen. The minute hand adds about
  as much once a minute, and the hour hand less.
- **Raster:** ~6–10 ms. That is the damage share of a full raster, plus the dial's ticks and
  background repainted inside the damage.
- **Document:** ~5–12 ms. A frame still evaluates the time expressions and walks the face's paints,
  even when it rasterises only a little. That is the 12.3 ms "paint" of a card value change.

| Second hand | Frames a second | Estimated core time | Verdict |
|---|---|---|---|
| Ticking (panel default) | 1 | ~15–25 ms a second, about 2% of the render core | Workable now |
| Sweep at 10 fps | 10 | ~150–250 ms a second | Possible with the face alone on screen. It steals from touch and value changes on a dashboard |
| Sweep at vsync | 30–60 | more than the core has | No |

So: **tick at 1 Hz on the panel and sweep only on players that afford it.** A face on a shared
screen of cards also pays the cards' paint walk on every tick unless the player limits its
per-frame work to the components that read the clock (below).

## What the player has to do

The renderer itself is not in this repository. These are requirements for it, in the order to
build them.

1. **Frame demand from the clock.** After each frame, work out when the next frame is due from
   the time variables the document reads:
   - `CONTINUOUS_SEC`: the next frame, capped by a frame-rate limit (`DOC_DESIRED_FPS`, header
     field 8, or the panel's own cap, 10 fps).
   - `TIME_IN_SEC`: the next whole second.
   - `TIME_IN_MIN`: the next whole minute.
   - Nothing: idle, as now.

   Honour `WAKE_IN` (opcode 191) the same way. This is the panel half of
   [ambient-mode.md](../architecture/ambient-mode.md)'s `RcFrameDemand` (NextFrame / At / Idle).
   Without it a panel either never moves the hands or redraws continuously.
2. **Re-evaluate only what reads the clock.** Mark the expressions that depend on a time variable
   once, at load. On a tick, re-evaluate only those, and re-paint only the components whose
   inputs moved. A clock tick must not move the layout epoch: a face's sizes don't read time.
3. **Damage per paint, not one union.** Keep each changed paint's old and new bounds as separate
   rectangles while they are far apart, and merge them only when they overlap. A tick moves two
   hands at most. One union rectangle around a minute hand at 12 and a second hand at 6 is the
   whole dial.
4. **Cull the dial's paints against the damage** before ThorVG sees them
   ([rendering-design.md](rendering-design.md#cull-before-the-rasteriser-sees-it)). A face has
   60–120 tick marks. Most fall outside a hand's damage.
5. **Never composite a group.** The translator draws a WFF `Group` or `PartDraw` with `alpha` below
   255 through a full-document offscreen bitmap, cleared every frame. On this board that is the
   1.5 MB-per-level crash in [hardware.md](hardware.md#never-let-the-rasteriser-composite).
   Multiply the alpha into each paint instead (rendering-design.md, *Transitions*). Better, the
   server does that before sending, below.
6. **Rotated hand bitmaps.** WFF hands are often PNGs (SimpleAnalog's are). Draw each as one
   transformed image with bilinear sampling, decoded once and bounded like the other image
   caches. Measure its cost per tick before optimising it.
7. **Time text through glyph packs.** A digital face's `TimeText` changes once a minute, or once a
   second with seconds shown. Each value is a new text run, so it must be composed from glyph
   packs (~0.3 ms), not rasterised (~5 ms), and evicted from the run cache by frame.

## What the server should do next (`kobento-mcu` folding for faces)

The server can afford what the panel can't. Each item is a step in `PlayerFolding`, or in
`WatchFaces` before the compile, for small players. Re-measure on the host after each one.

| Step | What it saves the panel |
|---|---|
| Strip `<Sweep>` (**built**) | vsync frames become one a second |
| Freeze the face's named values to constants (battery, steps, heart rate, complication types) | The `drawConditionally` per complication type folds to the one that draws; fewer paints, no expression work |
| Hide the second hand on request (`seconds: false`), and compile hands at minute resolution (needs the translator to accept a minute-quantised hand angle) | One frame a minute: the face is then as cheap as a card |
| Fold alpha groups into per-paint alpha (no `drawToOffscreenBitmap`) | The offscreen bitmap per frame, and the rasteriser crash |
| Merge the dial's static paths of one colour (60 tick marks) into one path | 60 hashes, cull tests and ThorVG shapes become one |
| Gradients a panel draws slowly (sweep gradients) baked into a small bitmap | Per-frame gradient set-up |
| Size the document to the cell it fills (`size` from the panel's grid cell) | No scaling at draw time |

A tempting step that doesn't pay: rasterising the whole static dial into one bitmap. At 450 px it
is 810 KB as ARGB8888 or 405 KB as RGB565. That exceeds the 110–390 KB of PSRAM a panel has free
with cards on screen. Revisit it only for a panel that shows a face alone, with a firmware that has
the memory.

## Complications from Home Assistant

A face's complication slots read named values: `dwf.complication.<n>.type`, `.text`, `.title`,
`.value`, `.min`, `.max` (compose-ai-tools `docs/DWF_REMOTE_COMPOSE.md`). These are the same
named-slot path the panel already long-polls. The next feature is a card option that maps a slot
to an entity:

```yaml
card:
  type: watchface
  face: aurora
  complications:
    0: sensor.outdoor_temperature   # SHORT_TEXT: state and unit
    1: sensor.phone_battery         # RANGED_VALUE: state over 0–100
```

The server would answer those slots' values from the entities' states through the existing
`/values` long poll. Faces would then show live Home Assistant data, with no new player work.

## Order to build in

1. **Server: compile and tick** (built). Then try a face on the host renderer and log frames per
   second and per-stage times with `--live-clock`.
2. **Player: clock frame demand and expression re-evaluation** (player 1–2). Without these nothing
   else matters.
3. **Server: freeze named values, fold alpha groups** (two folding steps). These are cheap and
   remove the worst case.
4. **Player: per-paint damage and culling** (player 3–4). Measure a 1 Hz tick on the device. The
   target is under 25 ms a tick with the face alone on screen.
5. **Server: minute-resolution faces** for dashboards that show a face beside cards.
6. **Complications from entities.**
7. **Sweep at a capped frame rate**, only if step 4's numbers leave room.
