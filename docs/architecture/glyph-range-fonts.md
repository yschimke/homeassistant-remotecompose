# Glyph ranges: fonts as rasters, fetched and cached

A player that can't draw a font — it doesn't have the face, or (a microcontroller wall panel)
it rasterises text slowly — can still draw a card in that face if it has the face's glyphs as images.
The bitmap-font filter (`POST /filters/bitmap-fonts`, [card-design-server.md](card-design-server.md))
gets those glyphs to it by embedding them in each document. This is the other way: the document
keeps its text as text with a typeface name, and the player fetches the glyphs it needs from the
design server, a character range at a time, and keeps them in a persistent cache.

It is for the low-power clients that don't play documents with rc-players: a wall panel on a
microcontroller, and any player that draws text slowly or ships one face. The Compose players load real fonts and
can draw from glyphs themselves if they ever need to.

## Why

Embedding is per document. On the wall's screens it made a 14 KB document 77–393 KB: every design
carries its own copy of every glyph it might show, one font per size, weight and colour, and a
live text gets a whole alphanumeric set in case. Glyphs by range are per device: a face at a size
is fetched once and every card that sets text in it draws from the same cache, across designs,
dashboards and restarts. The document stays the size it was and plays as text anywhere else.

| | Embedded (`/filters/bitmap-fonts`) | By range (`/fonts/glyphs`) |
|---|---|---|
| Document | text replaced by bitmap-font draws | unchanged: text and a typeface name |
| Glyphs travel | in every document | once per face, size and character |
| Live text | a slot of the characters it may show | whatever arrives: fetch what's missing |
| Player needs | bitmap-font ops | a glyph source for its text drawing |
| Offline | always complete | complete for what it has drawn before |

## The request

```
GET <base>/fonts/glyphs?family=<name>&size=<px>&density=<1-4>&weight=<1-1000>&italic=<0|1>
                       &chars=<text> | &ranges=<from-to,...>  [&bits=8|4|2|1] [&kerning=1]
```

- `family`: by name, case ignored. The server's own faces come first, a `google:` prefix dropped:
  the card fonts (`card-fonts/`) and `roboto` (what the wall panel ships). Otherwise a
  `google:<Family Name>` (`google:Lobster Two`) is fetched from Google Fonts (below). Unknown:
  `404`.
- `size`: the text's size as the document writes it (dp), drawn at `density` device pixels per dp:
  `size=14&density=2` is 28 px text. 4–256.
- `chars` (UTF-8, URL-encoded) or `ranges` (`0x20-0x7e,0xb0`, code points, inclusive): the
  characters wanted, at most 512. A character outside the basic plane, or one the face can't draw,
  is left out of the reply, not an error.
- `bits`: coverage depth, 8 (the default) or packed 4, 2 or 1. 2 looks all but identical to 8 at
  panel sizes; 1 is visibly jagged ([measurements](#coverage-depth)).
- `kerning=1`: the pairs among the characters asked for, when there are at most 32 of them.

The reply is `application/vnd.ha.glyphs` (below), with an `ETag` over the request and the face's
SHA-256 and `Cache-Control: public, max-age=86400`: the same request draws the same glyphs until
the face changes, which is a deployment.

## The glyph pack

One face at one size: big-endian, every field fixed or length-prefixed, so a client in any language
(the wall's C++) reads it without a library.

```
"RCGP"                magic
u8   version          1
u8   bits             8, 4, 2 or 1
u16  flags            bit 0: a kerning table follows the glyphs
u16+ family           UTF-8, u16 length
f32  size             as asked (dp)
f32  density
u16  weight
u8   italic           0 or 1
i16  lineHeight       every glyph's cell, top to bottom, in device pixels
i16  baseline         from the cell's top
u16  glyphCount
per glyph:
  u8+  chars          UTF-8, u8 length (one character; a ligature run in a later version)
  i16  marginLeft, marginTop, marginRight, marginBottom
  u16  width, height  the coverage image, device pixels
  ...  coverage       height rows of ceil(width * bits / 8) bytes, high bits first; 0 is clear
if flags & 1:
  u16  pairCount
  per pair: u8+ pair (the two glyphs' chars joined), i16 adjustment
```

The metrics are RemoteCompose's `BitmapFontData` glyph's, as `coreTextAsBitmapFonts` sets them:
a glyph's advance is `marginLeft + width + marginRight`, every glyph's cell is `lineHeight` tall,
and `marginTop` sets each on the shared baseline. A pack converts to a `BitmapFontData` and `RAW8`
bitmaps without arithmetic, so a player that already plays bitmap fonts draws from it as it is.

Coverage is a white mask: the player tints it with the text's colour, so one pack serves every
colour a face is set in.

## The client

`ha-client`'s `GlyphCache` (`ee.schimke.ha.client.design.glyphs`):

- `glyphs(request)` returns the pack for a face at a size covering the characters asked for: from
  the store when it has them, else fetching only the missing ones and merging them into the stored
  pack. Offline, it returns what it has.
- Packs live in a `GlyphStore`, keyed by face and size (`roboto 14 @2 w400`): `InMemoryGlyphStore`,
  or `DirectoryGlyphStore` (JVM) under a cache directory, one file a face and size that grows as
  text needs more characters. Android implements the interface over its own cache directory.
- A character the face can't draw comes back without a glyph and isn't asked for again that run.
- `GlyphPack.toBitmapFont` (`rc-converter`) turns a pack into `RAW8` bitmaps and a
  `BitmapFontData`, for a host that already plays bitmap fonts.
- A pack is checked when read: one that doesn't decode is dropped and fetched again.

A player plugs in as a glyph source for the families it can't draw: before rasterising a run in
such a family, it asks the cache for the run's characters and, with all of them, draws the run from
glyphs as a bitmap font draws.

## Google Fonts

A `google:` family the server doesn't hold comes from Google Fonts (`GoogleFonts`):

- The public CSS API (`fonts.googleapis.com/css2?family=Lobster+Two:ital,wght@0,400`, no key)
  names the face's file. Asked by a client that isn't a browser, it names whole TrueType files on
  `fonts.gstatic.com`, not WOFF2 subsets. A weight or slant the family doesn't have falls back to
  its default face.
- Only `fonts.gstatic.com` is fetched for a face, at most 16 MB, and the bytes must be a font.
- A face is kept in memory and on disk (`GOOGLE_FONTS_DIR`, else `<UI_BUILDER_STATE_DIR>/google-fonts`),
  so it is fetched once a server. A family Google doesn't have is remembered for an hour.
- `GOOGLE_FONTS=off` turns it off: only the server's own faces are served.
- The face's SHA-256 is in the pack's `ETag`, with a rendering version, so a face Google updates or
  a change in how glyphs are drawn is a new pack.

Google's catalogue is under the SIL Open Font License or Apache 2.0, both of which allow drawing the
faces and serving their rasters.

## A small player

What a microcontroller panel gains, measured on an ESP32-S3 7" panel at 800x480, density 1, with a
vector rasteriser (ThorVG) on a 240 MHz core and the document and frame buffers in PSRAM:

| | Text (outlines, cached as one image a run) | Everything else |
|---|---|---|
| a card loads (15 runs) | 75 ms | 52 ms doc + 99 ms raster |
| a value changes (1 run) | 4.8 ms | 13.7 ms doc + 8.3 ms raster |

Every wall screen set its text in six styles: Roboto Regular at 12, 13, 15, 17 and 22 px, Bold at
16. What fits such a player:

- **Rasters, not outlines.** Outlines are the rasteriser's work, the 75 ms. A run composed from
  packed coverage is a copy: ~0.3 ms where outlines take ~5.
- **Packs, not glyphs in documents.** The six styles' printable ASCII and the signs readings use
  (~100 characters each) are some 25 KB at 4 bits, kept to their ink, once: no document grows, and
  every card and dashboard draws from them.
- **4 bits.** Indistinguishable from 8 at a panel's sizes for half the bytes; 2 bits saves another
  ~10 KB for slightly rougher edges.
- **Built in, then fetched.** Packs for the styles a player always uses can be fetched when its
  firmware is built and embedded; a style or character a document needs that no pack has is
  fetched at run time (`GET /fonts/glyphs`) and kept, so new faces (`google:` families) and sizes
  arrive without a new firmware.
- **Placed by the player's own metrics.** Each glyph goes at the pen position the layout's
  advances give, rounded to a pixel, so a run is exactly as wide as the layout made room for. The
  coverage is the server's, rasterised from the same font file the player measures with
  (`wall-fonts/`).

### Emoji, colour, and how a panel asks

A panel's own face may have no emoji, so a section heading's 🚀 draws as nothing. Emoji travel as
**colour packs**: `bits=32`, each pixel premultiplied ARGB, big-endian, drawn as it is rather than
tinted. `family=emoji` is Noto Color Emoji (from Google Fonts, 25 MB, fetched once a server);
`chars` may name code points past the basic plane, one a glyph, joiners and variation selectors
left out.

A panel asks only for what its fonts lack:
`GET /api/terrazzo/wall/v1/glyphs?family=emoji&size=22&bits=32&chars=🚀📶` on Home Assistant, with
its wall token. The integration passes the query to its add-on's `/designs/v1/fonts/glyphs` and
the answer back as it is: a pack, or 404 when the add-on has no glyph route yet (the panel then
draws without), or 503 with no design server. 🚀 and 📶 at 22 px are 25x25 px each, 5 KB together.

## Rasters or outlines

A glyph can travel as its pixels or as its outline. Measured on the wall's Roboto, letters, digits
and common signs (78 glyphs), each raster kept to its ink (a raster is its line's whole cell, half
of it clear; trimming halved every pack):

| | 13 px | 15 px | 22 px |
|---|---|---|---|
| rasters, 8 bits | 4.3 KB | 5.6 KB | 11.8 KB |
| rasters, 2 bits | 1.3 KB | 1.7 KB | 3.4 KB |
| rasters, 2 bits, deflated | 0.8 KB | 1.1 KB | 1.7 KB |
| outlines (`glyf`), any size | 5.6 KB | | |
| a subset font, kerning, no hinting | 9.2 KB TTF, 4.9 KB WOFF2 | | |

- **Rasters** are smaller for a face at one or two sizes, and need nothing from the client but a
  copy into the frame: what a low-power player (an ESP32-S3 panel draws a run in some 6 ms with a vector rasteriser)
  needs.
- **Outlines** are one download for every size, and draw sharp at any scale or rotation, but the
  client rasterises every glyph. They pay off for a face at three or more sizes, on a client that
  rasterises well (a phone, a browser), or rasterised once into a client's own glyph cache.

Both come from the same request: `format=raster` (the pack, the default) or `format=font`, the face
cut to the characters asked as TrueType (`font/ttf`; FontBox's `TTFSubsetter`), so a client picks by
what it can draw. A font needs no `size`, `bits` or `kerning`. It keeps the outlines, metrics,
character map and TrueType hinting, and drops OpenType layout (ligatures, `GPOS` kerning): the wall's
78 Roboto characters come to 7.5 KB.

## Coverage depth

Measured on the wall's Roboto at 15 and 22 px (`WallFontStats`):

| bits | look | size a glyph at 15 px (raw, kept to its ink) |
|---|---|---|
| 8 | reference | ~74 B |
| 4 | identical to 8 at 3× zoom | ~39 B |
| 2 | very slightly rougher edges | ~22 B |
| 1 | visibly jagged, uneven stems | ~13 B |

## Not yet

- **Ligatures and joined scripts.** One character a glyph, as the bitmap-font filter's defaults.
- **A player drawing from packs.** The C++ wall client library reads packs; a player that draws
  its text from them, and keeps fetched ones in flash, comes with the player.
- **Variable axes.** A variable family is drawn at its default axes; weight and slant pick a face.
