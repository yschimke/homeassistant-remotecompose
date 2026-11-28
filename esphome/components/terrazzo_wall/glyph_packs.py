"""Glyph packs for a panel's text, drawn at build from the faces the player itself loads.

A pack is one face at one size as glyph coverage, in the wire form the design server's
`GET /designs/v1/fonts/glyphs` answers with (`GlyphPackCodec` in ha-client; see
docs/architecture/glyph-range-fonts.md): big-endian, coverage at 8, 4, 2 or 1 bits a pixel,
rows padded to a byte, each glyph kept to its ink. A panel composes a run of text from a pack
instead of rasterising outlines, and fetches what no pack it was built with has.

Rasterised with FreeType (ESPHome's `font:` component's own dependency), unhinted as a vector
rasteriser draws, at the glyph's pen origin: the panel places each glyph at the pen position its
own advances give, rounded to a pixel.
"""

from __future__ import annotations

import math
from pathlib import Path
import struct

MAGIC = b"RCGP"
VERSION = 1
BITS = (8, 4, 2, 1)

# Every character a wall card writes in a reading or a name that isn't printable ASCII.
READING_SIGNS = "°±µ²³·…–—•×÷‰€£"
DEFAULT_CHARS = "".join(chr(c) for c in range(0x20, 0x7F)) + READING_SIGNS


def _string8(s: str) -> bytes:
    b = s.encode("utf-8")
    return struct.pack(">B", len(b)) + b


def _string16(s: str) -> bytes:
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def _pack_rows(coverage: list[int], width: int, height: int, bits: int) -> bytes:
    if bits == 8:
        return bytes(coverage)
    levels = (1 << bits) - 1
    row = (width * bits + 7) // 8
    out = bytearray(row * height)
    for y in range(height):
        for x in range(width):
            q = (coverage[y * width + x] * levels + 127) // 255
            bit = x * bits
            out[y * row + bit // 8] |= q << (8 - bits - bit % 8)
    return bytes(out)


def _ink(coverage: list[int], width: int, height: int):
    """The rows and columns holding ink: (x0, y0, x1, y1), or None for none."""
    xs = [x for x in range(width) if any(coverage[y * width + x] for y in range(height))]
    ys = [y for y in range(height) if any(coverage[y * width + x] for x in range(width))]
    if not xs or not ys:
        return None
    return xs[0], ys[0], xs[-1] + 1, ys[-1] + 1


def render_pack(ttf: Path, family: str, weight: int, size_px: int, chars: str, bits: int = 4) -> bytes:
    """[ttf]'s glyphs for [chars] at [size_px] pixels an em, as a pack's bytes."""
    import freetype  # ESPHome's own dependency (its `font:` component)

    if bits not in BITS:
        raise ValueError(f"bits must be one of {BITS}")
    face = freetype.Face(str(ttf))
    face.set_pixel_sizes(0, size_px)
    scale = size_px / face.units_per_EM
    baseline = math.ceil(face.ascender * scale)
    line_height = baseline + math.ceil(-face.descender * scale)
    flags = freetype.FT_LOAD_RENDER | freetype.FT_LOAD_NO_HINTING | freetype.FT_LOAD_TARGET_NORMAL
    glyphs = []
    for c in sorted(set(chars)):
        if face.get_char_index(ord(c)) == 0:
            continue
        face.load_char(c, flags)
        slot = face.glyph
        bitmap = slot.bitmap
        width, height, pitch = bitmap.width, bitmap.rows, bitmap.pitch
        buffer = bitmap.buffer
        coverage = [buffer[y * pitch + x] for y in range(height) for x in range(width)]
        advance = round(slot.linearHoriAdvance / 65536)
        left, top = slot.bitmap_left, baseline - slot.bitmap_top
        ink = _ink(coverage, width, height)
        if ink is None:
            glyphs.append((c, 0, baseline, advance, line_height - baseline, 0, 0, b""))
            continue
        x0, y0, x1, y1 = ink
        w, h = x1 - x0, y1 - y0
        trimmed = [coverage[(y0 + y) * width + x0 + x] for y in range(h) for x in range(w)]
        left += x0
        top += y0
        glyphs.append(
            (c, left, top, advance - left - w, line_height - top - h, w, h, _pack_rows(trimmed, w, h, bits))
        )
    out = bytearray(MAGIC)
    out += struct.pack(">BBH", VERSION, bits, 0)
    out += _string16(family)
    out += struct.pack(">ffHBhhH", float(size_px), 1.0, weight, 0, line_height, baseline, len(glyphs))
    for c, left, top, right, bottom, w, h, rows in glyphs:
        out += _string8(c)
        out += struct.pack(">hhhhHH", left, top, right, bottom, w, h)
        out += rows
    return bytes(out)
