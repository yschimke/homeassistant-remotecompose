# Hardware: the Waveshare ESP32-S3-Touch-LCD-7

What the board can do, where its time and memory go, the settings that matter, and the approaches
that look reasonable but don't work here. The numbers are what a finished panel measures, so you
can tell whether you're on track.

## The board

| | |
|---|---|
| SoC | ESP32-S3, two Xtensa LX7 cores at 240 MHz |
| Display | 800x480 RGB parallel panel (RGB565 on the wire), no controller RAM: the SoC streams every frame |
| Memory | 512 KB internal SRAM, 8 MB octal PSRAM at 80 MHz, 8 MB flash |
| Touch | GT911 capacitive over I2C |
| Radio | 2.4 GHz Wi-Fi on the SoC |

The RGB panel is the defining constraint. It has no frame memory of its own, so the LCD peripheral
reads the frame from PSRAM continuously, through internal-RAM bounce buffers, for as long as the
screen is on. Anything else that uses PSRAM competes with that stream.

## Memory map and budgets

Plan every byte. PSRAM is large but almost all of it is spoken for.

| Use | Where | Size |
|---|---|---|
| Two 800x480 RGB565 scan-out buffers | PSRAM | 750 KB each, 1.5 MB |
| The renderer's ARGB8888 frame buffer: **one**, see below | PSRAM | 1.5 MB |
| The app image (code and read-only data) | PSRAM, see below | ~2.9–3.0 MB |
| A playing document: wire bytes plus the parsed operation and layout tree | PSRAM | tens to a few hundred KB |
| Design blobs kept for the view's other screens | PSRAM | 1–25 KB each |
| TLS sessions (Home Assistant over https) | PSRAM, by setting | ~40 KB each, one per concurrent request |
| Text-run image cache | PSRAM | the current frame's runs (~100–190 KB for a screen of cards), plus 64 KB of older ones |
| Everything else the renderer allocates per frame | PSRAM | transient, spiky |
| The renderer's stack (deep layout recursion) | PSRAM, see below | 192 KB |
| **Left over** | PSRAM | **~1.6–1.9 MB with cards on screen** (110–400 KB with two render buffers) |
| Internal RAM free | internal | ~150 KB, never below ~85 KB (55–145 KB before the settings below) |

### One render buffer, not two

Two ARGB8888 render targets let the CPU draw frame N+1 while frame N is converted to RGB565, but
they cost 3 MB, and with them the panel had too little PSRAM left for a heavy screen: the
rasteriser's allocations failed and it crashed. Use one, and wait for the previous frame's
conversion before drawing into it. A damage-limited frame converts only its rectangles, so the
wait is short.

### Never let the rasteriser composite

A translucent group (a fade, a dimmed unavailable tile, a hidden state of a cross-fade) makes
ThorVG composite through a scratch buffer **the size of the whole canvas** (1.5 MB at 800x480, one
per nesting level), allocated without a check. When it fails, ThorVG rasterises into null. If you
guard it and draw the layer uncomposited, both states of a cross-fade show at once. Don't give
the rasteriser translucent groups at all: multiply the layer's alpha into each paint inside it as
you add it. Where its paints overlap they show through each other, which a card's fades don't
mind, and no screen needs a composition buffer.

### The renderer's stack

The renderer recurses as deep as the document's layout tree: a screen of 236 layout components
needs 32–48 KB of stack on x86, more on Xtensa's register windows. Give its task a large stack in
PSRAM (`CONFIG_SPIRAM_ALLOW_STACK_EXTERNAL_MEMORY=y`, `xTaskCreatePinnedToCoreWithCaps(…,
MALLOC_CAP_SPIRAM)`, 192 KB), which also frees internal RAM, and turn on
`CONFIG_FREERTOS_WATCHPOINT_END_OF_STACK=y` so an overflow faults where it happens instead of
corrupting the heap below. Log the stack's high-water mark.

### The app image lives in PSRAM

Set `CONFIG_SPIRAM_XIP_FROM_PSRAM=y`. With the RGB panel streaming from PSRAM, executing from flash
means every instruction-cache miss contends for the same bus and the panel tears or drifts. With
XIP from PSRAM the bootloader copies the whole app image into PSRAM at boot and runs it from there.

The consequence is easy to miss: **every byte you add to the firmware costs a byte of PSRAM.**
Embedded fonts, glyph tables, splash documents, lookup tables: none of it is "free because it's in
flash". A 40 KB table built into the firmware is 40 KB less headroom at run time. Data that must
not cost PSRAM needs its own data partition mapped from flash (`esp_partition_mmap`), which means a
partition table change, which can't be delivered over the air; decide this before the first device
ships.

### Keep internal RAM for what must live there

Set `CONFIG_SPIRAM_USE_MALLOC=y` so plain `malloc`/`new` can use PSRAM,
`CONFIG_SPIRAM_MALLOC_ALWAYSINTERNAL=0` so **no** plain allocation stays internal, and
`CONFIG_SPIRAM_MALLOC_RESERVE_INTERNAL=65536` to keep internal RAM for what can only live there
(Wi-Fi, DMA). A vector renderer makes many small allocations per glyph and path. With any
always-internal threshold (even 256 bytes) a deep screen fills internal RAM, and the Wi-Fi task
then aborts (`ESP_ERROR_CHECK` in `ppTask`: an `esp_timer` it can't create), or the panel hangs
without answering a ping. Corollary for your own code: prefer one large allocation (a sorted
vector) over many small ones (a map's nodes).

Also: `CONFIG_SPIRAM_TRY_ALLOCATE_WIFI_LWIP=y`, `CONFIG_MBEDTLS_EXTERNAL_MEM_ALLOC=y` (TLS buffers
in PSRAM).

## Where the time goes

Measure these on your own build with a per-frame log line: document data pass, layout, paint (the
renderer's operations), raster (the vector rasteriser filling pixels) and present (the RGB565
conversion and buffer swap).

| | Typical |
|---|---|
| Full 800x480 raster, a screen of cards | ~100–110 ms |
| Raster of a value change, damage-limited (a 108x32 px text) | ~8 ms |
| Rasterising one text run with the vector rasteriser (ThorVG) | ~5 ms |
| Rasterising one glyph with the vector rasteriser | ~0.6 ms |
| Composing one text run from pre-rasterised glyph coverage | ~0.3 ms |
| Layout of a screen of cards, measured | 35–40 ms |
| Layout re-used (nothing moved) | ~0.7 ms |
| ESP32-S3 vs a desktop core running the same renderer | ~100x slower |

The 100x factor lets you predict panel cost from a desktop profile. It holds well for layout and
paint; raster depends more on memory bandwidth.

## Board settings that matter

| Setting | Why |
|---|---|
| `CONFIG_SPIRAM_XIP_FROM_PSRAM=y` | See above. Without it the panel glitches under load. |
| `CONFIG_ESP32S3_INSTRUCTION_CACHE_32KB=y`, `..._LINE_32B=y` | The renderer's hot loops are larger than the 16 KB default; 32 KB measurably cut frame time. Costs internal RAM. |
| Bounce buffers for the RGB panel in internal RAM | Lets the LCD keep streaming while PSRAM is busy. |
| Start the display (LCD init) on core 0 | The LCD's interrupts are bound to the core that installed them; the renderer owns core 1, and an LCD ISR on core 1 stalls behind long layouts. |
| Pin the renderer task to core 1, networking and HTTP tasks to core 0 | Keeps a seconds-long layout from starving Wi-Fi, and a long poll from delaying a frame. |
| `CONFIG_ESP_TASK_WDT_CHECK_IDLE_TASK_CPU1=n` | A big card's first layout can keep core 1 busy for seconds; the idle-task watchdog took that for a hang and reset the panel. |
| Wi-Fi `power_save_mode: none` | Wi-Fi light sleep's wake path (`phy_track_pll_init` from the power-save handler) aborted on this board while the RGB panel streamed from PSRAM. A mains-powered panel doesn't need it. |
| C++ exceptions and RTTI on | The RC engine uses both. |

## Failure modes to design around

### A failed allocation is a crash, not an error

On this platform a failed allocation inside the renderer or a growing `std::string` doesn't throw
in a way anything catches: the next write goes through a null (or near-null) pointer and the core
faults with `StoreProhibited` at address 0 or a small offset. Seen in practice:

- the vector rasteriser growing a point array while tessellating text (`Array::push`),
- the rasteriser clipping against a span list it failed to allocate (`rleClip`),
- an HTTP response body appended to a `std::string` that couldn't double.

Rules that follow:

- Reserve an HTTP body from `Content-Length` before reading, and check the largest free block
  (`heap_caps_get_largest_free_block`) with a margin first. If it doesn't fit, fail the request
  and retry later.
- Before allocating a cached image, check the largest free block; if it won't fit with room to
  spare, skip that item this frame rather than fall back to a costlier path.
- Treat PSRAM fragmentation as real: the largest block can be a fraction of the free total.
- Never fall back from a cheap cached path to an expensive uncached one under memory pressure. The
  uncached path needs *more* memory.

### Over-the-air updates roll back on an early crash

With safe mode and bootloader rollback, an image that crashes within its first 60 s is rolled back
to the previous one. That's a good safety net, but:

- A crash that only happens on one screen, or ~30 s after a screen loads (the first value change,
  starting a fade), points at that screen's content: log which documents have shown, and look for
  one that never has.
- A crash that only happens once cards are on screen can look like "my change crashed it" when the
  old image would crash too. Before blaming a change, rebuild the previous known-good source and
  check it under today's conditions.
- A panel that crash-loops within ~40 s can't take an upload: a 1.8 MB compressed image takes
  longer than that. Keep a way to quiet it: stop serving it cards (it then sits on its splash) or
  let ESPHome's safe mode kick in after repeated boot failures.

## Debugging

- **Decode backtraces against the ELF of the build that crashed.** The crash record survives a
  reboot and is printed when a log client connects; if the running image is a different build (a
  rollback happened), decoding with the current ELF gives nonsense frames. Check the build hash
  the record names before reading it.
- **A screenshot endpoint.** `GET /snapshot.bmp?step=N` on the device, reading the presented RGB565
  buffer. It tells you what's actually on the glass, which is often not what you assumed.
- **Memory telemetry.** Log PSRAM free, largest free block and minimum-ever, plus internal free,
  whenever they move by more than ~8 KB (check every 250 ms). Log what the renderer keeps (text
  cache bytes, decoded images, filtered copies) with each frame's stats. Log each large HTTP
  response with the memory after it. Without this, an OOM crash 30 s after a frame is a mystery.
- **Per-frame stats.** Data, layout (measured or cached), paint, raster, text runs rasterised and
  how many were composed from glyphs, number of paints. Print every few seconds, not every frame:
  the log buffer is small (768 bytes per task) and long lines get dropped under load.
- **Keep the log stream short-lived but always on** during work: a background client that
  reconnects and appends to a file. Don't truncate it when you restart it; the lines before a
  crash are the ones you need.

## Options explored, and what to do instead

| Tried | Outcome | Do instead |
|---|---|---|
| Measuring each layout child twice per pass (intrinsic, then final) | A leaf nested in N layouts was measured 2^N times; one text measured 14,016 times, 95,290 text measures a frame | Measure once per constraints per pass; 10–25x faster layout |
| Re-encoding the document on every value change | Re-parse and full layout every time; seconds on this chip | Named slots: push values into the playing document |
| Laying out every frame "in case" a state layout flipped | Layout cost on frames where nothing moved | Lay out only when an input to layout moved (an epoch), or a state layout changed index |
| Touch position as a layout input | Every finger move re-measured (26 ms a frame while pressed) | Touch variables move the layout epoch only when an expression that layout reads listens to them |
| Re-measuring on every frame of a layout animation | ~250 µs host per frame, all of it wasted | Keep measured bounds; re-read only the animated expressions (~24 µs host) |
| Animating the settling passes of a fresh load | Every card slid into place on load | Snap the first layouts until bounds stop moving; animate only later changes |
| Redrawing the whole frame per change | ~100 ms raster per value change | Damage tracking: 108x32 px for a value change |
| Rasterising text with the vector rasteriser every frame | Half of a frame's cost | One cached image per text run |
| An unbounded (4 MB) text-run cache | Stale runs filled PSRAM: 52 KB free after a few hours, down from 220 KB | A small budget for runs not drawn this frame |
| Keeping runs drawn in the last two frames, past 128 KB | A layout settling over its first frames redraws its text at a new sub-pixel phase each frame, so the cache held two or three screens of text (191 KB for one screen of cards): PSRAM fell to 5 KB and the panel crashed ~30 s after every card load | Past 64 KB, drop runs not drawn in the current frame *before* allocating a new one; size composed runs to their ink, not a padded box |
| A persistent glyph cache the panel rasterised itself, per size and quarter-pixel phase | 0.6 ms per glyph made first frames slower (156 ms of text instead of 94), and its 60–170 KB pushed PSRAM below what the design fetch needed: crash and rollback | Glyphs rasterised by the server, shipped as compact packs |
| Bitmap fonts embedded in each document | Every document grows by its glyphs; per-glyph image overhead | Glyph packs per face and size, outside documents, shared |
| 1-bit glyph coverage | Visibly jagged, uneven stems | 4 bits: indistinguishable from 8 at panel sizes, half the bytes |
| "Read-only data in flash costs no RAM" | Not on this board: the app image runs from PSRAM | Budget built-in data as PSRAM, or put it in a mapped data partition |
| A map with a node per glyph for lookups | Hundreds of sub-256-byte nodes in internal RAM | Sorted vectors |
| Two ARGB8888 render buffers | 3 MB, leaving 110–400 KB; the rasteriser ran out on heavy screens | One buffer; wait for the conversion |
| Translucent groups (scene opacity) | A 1.5 MB full-canvas composition buffer each, unchecked: crash, or both cross-fade states drawn when guarded | Multiply the layer's alpha into each paint |
| A 64 KB renderer stack in internal RAM | Overflowed on the deepest screen and corrupted the heap below it | 192 KB in PSRAM, end-of-stack watchpoint |
| Small allocations (< 256 B) kept in internal RAM | A deep screen filled it; Wi-Fi aborted in `ppTask` | Nothing always-internal; reserve 64 KB for Wi-Fi and DMA |
| A 16-entry vector cache of colour-filtered images that erased and shifted | Entries moved under live pointers; glyphs drew other glyphs' pixels | A list with last-used-frame eviction; never hand out pointers into a container that shifts |
| Drawing images at fractional device positions | Damage-limited redraws drew them differently from full redraws | Snap cached images to whole device pixels when unscaled |

## Expected results

A finished panel on a typical screen of five cards (15 text runs):

| | Before the work in these docs | After |
|---|---|---|
| A value changes (one text) | ~45 ms | 18.5 ms document (layout re-used 0.7, paint 12.3, text 4.8) + 8.3 ms raster |
| A screen of cards loads | — | ~127 ms document (layout 35, paint 86, of which text 75) + ~99 ms raster |
| Same, text from glyph packs | — | ~75 ms document, text 24 ms (13 of 15 runs composed) |
| An animation frame | full measure | expressions re-read only |
| Memory with cards on screen | — | 110–390 KB PSRAM free, depending on what's built in |

On the desktop host running the same engine, a value change without layout animations draws in
~0.4 ms, a transition frame in ~0.8 ms.
