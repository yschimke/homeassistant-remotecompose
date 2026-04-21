#!/usr/bin/env python3
"""
Scan every rendered preview PNG and flag ones where the canvas is too
small for the card content.

Two signals:
1. The last row of pixels has no transparent pixels. A card with
   rounded corners always produces transparent corner pixels at the
   bottom row when the canvas height is adequate. A fully-opaque
   bottom row means the card's rounded corner is getting clipped off
   (or the whole card is bigger than the canvas).

2. The top row has the same characteristic. If both top and bottom
   lack transparent pixels, the card is almost certainly being
   clipped.

Usage:  scripts/check-preview-clipping.py
Exits non-zero if any clipped previews are found.
"""
from PIL import Image
from pathlib import Path
import re
import sys

RENDERS = Path(__file__).parent.parent / "previews" / "build" / "compose-previews" / "renders"

def row_has_transparent(im, y):
    for x in range(im.width):
        if im.getpixel((x, y))[3] < 50:
            return True
    return False

bad = []
for f in sorted(RENDERS.glob("*.png")):
    im = Image.open(f).convert("RGBA")
    if im.width < 4 or im.height < 4:
        continue
    top_ok = row_has_transparent(im, 0) or row_has_transparent(im, 1)
    bot_ok = row_has_transparent(im, im.height - 1) or row_has_transparent(im, im.height - 2)
    # Heading card has no card bg — only text — so both rows are fully
    # transparent or not. Skip when the card area is mostly transparent.
    transparent_rows = sum(1 for y in range(im.height) if row_has_transparent(im, y))
    no_card = transparent_rows > im.height * 0.6
    if no_card:
        continue
    if top_ok and bot_ok:
        continue
    m = re.search(r"Kt\.(\w+)_", f.name)
    name = m.group(1) if m else f.name[:40]
    reason = []
    if not top_ok:
        reason.append("top")
    if not bot_ok:
        reason.append("bottom")
    bad.append((name, " + ".join(reason), f.name))

if bad:
    print("Clipped previews detected:")
    for name, reason, full in bad:
        print(f"  {name:30s}  clipped on {reason}  ({full})")
    sys.exit(1)
else:
    print(f"All {len(list(RENDERS.glob('*.png')))} rendered previews have adequate canvas size.")
