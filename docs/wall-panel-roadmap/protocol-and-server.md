# Protocol and server

What the panel asks for, and what Home Assistant and the design server do so the panel doesn't
have to. The split is the most important performance decision in the whole system: everything
that needs Lovelace, entity attributes, formatting or a JVM stays off the device.

## The parts

| Part | Runs | Does |
|---|---|---|
| A Home Assistant integration | In Home Assistant (Python) | Dashboards and per-user visibility, layout into grid boxes, a state listener, value diffs and cursors, taps into service calls. Serves the wall API. |
| A design server | Beside Home Assistant (an add-on or a container) | Turns a card plus its states into an RC design and its named values, made for the display asking. Serves glyph packs. |
| The panel | ESP32-S3 | Fetches designs by hash, pushes values into named slots, draws, posts taps. |

The panel never sees a Lovelace config, an entity's attributes, a formatter or a service name.

## The wall API

Served by Home Assistant under one versioned prefix, authenticated with a long-lived token of a
dedicated non-admin user per panel (`Authorization: Bearer …`). The panel sees what that user may
see; revoking the token cuts off that one device.

```
GET  /dashboards                       once: pick one, or the default
GET  /dashboards/{d}                   the grid; again when its etag changes
GET  /values/{d}?screens=…&wait=0      every screen's design hash and every value
GET  /blobs/{sha}.rc                   each design not already held: immutable
loop:
  GET /values/{d}?screens=…&since={cursor}&wait=25   long poll: only what changed
on tap:
  POST /actions/{d}/{card}             {"action": "<the document's host action string>"}
```

### Values: one endpoint, long-polled

- The first answer is full: each screen's `design` hash, the blob URL and every value.
- After that, `since={cursor}` answers only what changed, and `wait` holds the request (25 s; up
  to 55) until something does. A burst of changes (a scene) is settled for ~150 ms and answered
  once.
- A screen whose design changed (structure, not value) comes whole again.
- `reset: true` means the cursor predates a restart or an edit: full answer; refetch the layout if
  its etag moved.
- Values are typed JSON: booleans, integers without a point, floats with one, strings; `null` for
  a number with no JSON form (NaN, an unavailable reading).
- Names are the document's own slots: the panel pushes `sensor.s0.state` as
  `USER:sensor.s0.state` without interpreting it.

Why long poll and not a websocket: on a microcontroller the websocket is the expensive part (TLS
framing, reconnects, Home Assistant's full state diffs the device would still have to format). A
long poll is one `GET`, stateless on the device (a cursor string), survives Wi-Fi drops and
reboots, and has the same latency (one round trip after a change).

### Display parameters

The panel tells the server what it is, and the server makes designs for it:

| Query | Effect |
|---|---|
| `density=1&font_scale=1` | Sizes written as numbers, not expressions over density and font size; rich text as plain text layouts; values the panel won't push frozen. Served under its own hash. |
| `width`, `height` (dp) | Each design's canvas is the screen. |
| `screens=<view>` | The view packed into screens of the panel's height (grid rows of 56 dp, 8 dp gaps, at most 12 entities a screen), each drawn as one vertical stack of its shelves. A heading never ends a screen with more to come; a card that doesn't stack gets a screen of its own and one with no design is left out, the rest packed around them. |
| `dark=1`, `style=…` | The look. |

For a small player each card is also simplified to what its template draws: headings without
badges, markdown without inline images or links. An entity the user can't read is sent as
unavailable, so its card keeps its exact design.

### Designs by content hash

Blobs are immutable and addressed by SHA-256. The panel caches the current view's blobs in RAM
(1–25 KB each) and drops those the view no longer uses. Download each blob into a buffer reserved
from `Content-Length`, after checking the largest free block; on no room, retry later rather than
grow a string (see [hardware.md](hardware.md#a-failed-allocation-is-a-crash-not-an-error)).

### Pictures

A design never carries an image URL. A picture slot is resolved by the server: the panel asks for
`/pictures/{d}/{card}/{slot}?w=&h=&fit=contain|cover&format=jpeg|png`, scaled to the box (never up),
with an ETag. Each value map carries a `<slot>.picture_url` whose version changes when the picture
does; that's the panel's cue to refetch.

### Fonts and icons

Icons are vector paths inside designs: nothing to fetch. Named faces (a design asking for a face
the panel doesn't have) are listed in a fonts manifest and fetched by hash; a panel that can't
load a face keeps its own.

### Glyph packs

```
GET /designs/v1/fonts/glyphs?family=<name>&size=<dp>&density=<1-4>&weight=<1-1000>&italic=<0|1>
      &chars=<text>|ranges=<0x20-0x7e,…>&bits=8|4|2|1[&kerning=1][&format=raster|font]
```

- `format=raster` (default): a glyph pack, `application/vnd.ha.glyphs`, with an ETag.
- `format=font`: the face cut to those characters as TrueType, for clients that rasterise well
  (phones, browsers). A face at three or more sizes is smaller as outlines; at one or two sizes,
  rasters are smaller and cost the client nothing to draw.
- `family` is one of the server's faces, or `google:<Family Name>`, fetched once from Google Fonts'
  public CSS API (non-browser user agent: whole TrueType files), cached on disk, only from Google's
  font host. Unknown families are remembered as missing for an hour.
- Pixel size is `size × density`.
- Bump a rendering version in the ETag whenever rasterisation changes.

**Wire format** (big-endian, length-prefixed, so a C client reads it without a library):

```
"RCGP" u8 version=1  u8 bits  u16 flags(1=kerning)
string16 family  f32 size  f32 density  u16 weight  u8 italic
i16 lineHeight  i16 baseline  u16 glyphCount
glyph × count: string8 chars  i16 marginLeft  i16 marginTop  i16 marginRight  i16 marginBottom
               u16 width  u16 height  coverage[ceil(width*bits/8) * height]   (rows padded to a byte, high bits first)
[if kerning] u16 pairs, each: string8 pair  i16 adjustment
```

A glyph's advance is `marginLeft + width + marginRight`; `marginTop` sets it on the shared
baseline; coverage is a white mask the client tints; a glyph with no ink (space) has width 0.

Measured pack sizes (letters, digits and common signs, 78 glyphs, each kept to its ink):

| | 13 px | 15 px | 22 px |
|---|---|---|---|
| 8 bits | 4.3 KB | 5.6 KB | 11.8 KB |
| 2 bits | 1.3 KB | 1.7 KB | 3.4 KB |
| outlines (`glyf`), any size | 5.6 KB | | |
| subset TrueType | 7.5 KB | | |

Trimming each glyph to its ink halves every pack: a glyph's full cell is mostly empty.

**Panel side:** build the panel's styles into the firmware at compile time (rasterise with
FreeType from the same font files the renderer loads, unhinted); fetch missing glyphs a few dozen
characters per request with `bits=4`; keep fetched packs in NVS with a cap; parse with full bounds
checking (fetched data is untrusted).

## Design server responsibilities

- Make each design for the asking display (above) and cache it by its parameters.
- Re-resolve a card only when one of its entities changes and only while a display polls it; drop
  idle hubs after ~10 minutes. One resolve per card at a time, reading states when it starts, so a
  slow resolve never overwrites a newer one.
- Check the device's token with Home Assistant before answering; keep no credentials of its own.
- Serve glyph packs and fonts with ETags.
- **Be supervised.** If the design server dies, the panel gets no cards (HTTP 503) and should show
  that, not crash. Watch it: a silently dead design server looks like a panel problem.
