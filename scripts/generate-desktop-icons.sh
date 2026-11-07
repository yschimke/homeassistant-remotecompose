#!/usr/bin/env bash
# Rasterizes the desktop icon for jpackage (Compose Desktop nativeDistributions):
#   app-ui/icons/terrazzo.svg  ->  src/jvmMain/resources/terrazzo.png (Linux, and the window icon), terrazzo.ico (Windows), terrazzo.icns (macOS)
#
# The SVG comes from scripts/generate-launcher-icons.py; run that first after changing the
# default launcher icon. Needs rsvg-convert (librsvg) and python3 with Pillow.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
dir="$root/app-ui/icons"
res="$root/app-ui/src/jvmMain/resources"  # also the window icon, so it ships in the jar
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

rsvg-convert -w 1024 -h 1024 "$dir/terrazzo.svg" -o "$tmp/terrazzo-1024.png"
rsvg-convert -w 512 -h 512 "$dir/terrazzo.svg" -o "$res/terrazzo.png"

python3 -I - "$tmp/terrazzo-1024.png" "$dir" <<'PY'
import sys
from PIL import Image

src, out = sys.argv[1], sys.argv[2]
img = Image.open(src).convert("RGBA")
img.save(f"{out}/terrazzo.ico", sizes=[(s, s) for s in (16, 24, 32, 48, 64, 128, 256)])
img.save(f"{out}/terrazzo.icns")
PY

echo "Wrote $res/terrazzo.png and $dir/terrazzo.{ico,icns}"
