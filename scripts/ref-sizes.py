#!/usr/bin/env python3
"""
Print the dimensions of every HA reference capture in references/,
converted from pixels to dp (density 2.625 matching the Puppeteer
capture config).
"""
from PIL import Image
from pathlib import Path
import math
import sys

REFS = Path(__file__).parent.parent / "references"

# HA captures come back from Puppeteer at a device-pixel ratio of 2
# (2x the CSS pixel dimensions). Our previews render at density 2.625.
# For preview widthDp we want the CSS-pixel width matched to the
# preview's dp. So: widthDp = captured_px / 2 == cssPx.
#
# If the user wants "exact same size" meaning same visible size, we
# match dp-to-cssPx. Ie `widthDp = captured_px // 2`.
REF_DENSITY = 2.0

print(f"{'file':60s}  px          dp (density={REF_DENSITY})")
for f in sorted(REFS.glob("*/*.png")):
    im = Image.open(f)
    w, h = im.size
    dpw = math.ceil(w / REF_DENSITY)
    dph = math.ceil(h / REF_DENSITY)
    rel = f.relative_to(REFS.parent)
    print(f"{str(rel):60s}  {w:4d}x{h:4d}  {dpw:4d}x{dph:4d}")
