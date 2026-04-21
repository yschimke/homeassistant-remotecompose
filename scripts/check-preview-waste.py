#!/usr/bin/env python3
"""
Scan rendered preview PNGs and report wasted canvas space.

For each render:
- measure the bounding box of the non-transparent content,
- report the wasted margin (top/bottom/left/right) in pixels and dp,
- suggest a tight widthDp / heightDp.

Density is 2.625 (Pixel preview density — what compose-preview uses).
Add 2 dp of slack on each side so future soft-shadow / antialiasing
tweaks don't immediately clip.

Usage:  scripts/check-preview-waste.py [min_waste_dp=4]
"""
from PIL import Image
from pathlib import Path
import math
import re
import sys

RENDERS = Path(__file__).parent.parent / "previews" / "build" / "compose-previews" / "renders"
DENSITY = 2.625
SLACK_DP = 0  # we want tight — no slack; comment in code where clipping would happen

min_waste_dp = int(sys.argv[1]) if len(sys.argv) > 1 else 4


def content_bbox(im):
    """Bounding box of pixels whose alpha >= 50. None if entirely transparent."""
    alpha = im.split()[-1]
    bbox = alpha.getbbox()  # (left, upper, right, lower) over > 0
    if bbox is None:
        return None
    # Tighten using alpha >= 50 threshold
    l, u, r, d = bbox
    px = im.load()
    # Walk inward on each edge
    def row_has(y, a_thresh=50):
        return any(px[x, y][3] >= a_thresh for x in range(l, r))
    def col_has(x, a_thresh=50):
        return any(px[x, y][3] >= a_thresh for y in range(u, d))
    while u < d and not row_has(u):
        u += 1
    while d > u and not row_has(d - 1):
        d -= 1
    while l < r and not col_has(l):
        l += 1
    while r > l and not col_has(r - 1):
        r -= 1
    if u >= d or l >= r:
        return None
    return (l, u, r, d)


def px_to_dp(px):
    return math.ceil(px / DENSITY)


results = []
for f in sorted(RENDERS.glob("*.png")):
    im = Image.open(f).convert("RGBA")
    if im.width < 4 or im.height < 4:
        continue
    bb = content_bbox(im)
    if bb is None:
        continue
    l, u, r, d = bb
    W, H = im.width, im.height
    waste_l, waste_r = l, W - r
    waste_u, waste_d = u, H - d
    waste_w = waste_l + waste_r
    waste_h = waste_u + waste_d
    waste_w_dp = math.floor(waste_w / DENSITY)
    waste_h_dp = math.floor(waste_h / DENSITY)
    tight_w = px_to_dp(r - l) + SLACK_DP * 2
    tight_h = px_to_dp(d - u) + SLACK_DP * 2
    cur_w_dp = round(W / DENSITY)
    cur_h_dp = round(H / DENSITY)
    results.append({
        "file": f.name,
        "canvas_dp": (cur_w_dp, cur_h_dp),
        "waste_dp": (waste_w_dp, waste_h_dp),
        "waste_edges_dp": (
            math.floor(waste_l / DENSITY),
            math.floor(waste_u / DENSITY),
            math.floor(waste_r / DENSITY),
            math.floor(waste_d / DENSITY),
        ),
        "tight_dp": (tight_w, tight_h),
    })


def bad(r):
    return r["waste_dp"][0] >= min_waste_dp or r["waste_dp"][1] >= min_waste_dp


wasteful = [r for r in results if bad(r)]
if not wasteful:
    print(f"All {len(results)} previews have <{min_waste_dp} dp waste on each axis.")
    sys.exit(0)

print(f"Previews with ≥{min_waste_dp} dp wasted space:")
print(f"  {'file':80s}  canvas  waste(l,t,r,b)  tight")
for r in wasteful:
    cw, ch = r["canvas_dp"]
    ww, wh = r["waste_dp"]
    wl, wu, wr, wd = r["waste_edges_dp"]
    tw, th = r["tight_dp"]
    m = re.search(r"Kt\.(\w+)_", r["file"])
    short = m.group(1) if m else r["file"][:60]
    print(f"  {short:80s}  {cw}x{ch}  ({wl},{wu},{wr},{wd})={ww}x{wh}  {tw}x{th}")
