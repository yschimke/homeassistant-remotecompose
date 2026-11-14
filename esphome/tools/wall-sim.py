#!/usr/bin/env python3
"""A wall panel on the desk: what the ESP32 panel would draw, and how long it would take.

Polls the wall display API the way the panel does (screens of one view, at its size), fetches
each screen's design, pushes its values into the document as the panel does (`USER:` names),
and renders it with kobento-mcu's host `rc2png` — the same rccore + ThorVG engine the panel
runs. Writes a PNG per screen and prints each one's cost a frame, with an estimate for the
panel (an ESP32-S3 runs the engine some 100x slower than a desktop core).

  wall-sim.py --url https://ha.example --token-file ~/.wall-token \\
      --rc2png ~/kobento-mcu/esp32/host/build/rc2png --dashboard dashboard-networks --out /tmp/sim

Build rc2png from a kobento-mcu checkout (esphome/patches/rccore-panel.patch applied to the engine,
rc2png-live-timing.patch to kobento-mcu for the change timings):
  cmake -S esp32/host -B esp32/host/build -G Ninja && ninja -C esp32/host/build rc2png
"""
import argparse
import json
import math
import os
import re
import subprocess
import sys
import urllib.parse
import urllib.request

API = "/api/terrazzo/wall/v1"


def get(base, token, path):
    req = urllib.request.Request(base + path, headers={"Authorization": f"Bearer {token}"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def scalar_text(v):
    """A JSON value as the panel pushes it (wall_client.cpp scalar_text)."""
    if v is True:
        return "1"
    if v is False:
        return "0"
    if v is None:
        return "nan"
    if isinstance(v, int):
        return str(v)
    if isinstance(v, float):
        return "nan" if math.isnan(v) else repr(v)
    return str(v)


def document_name(name):
    return name if name.startswith(("HA:", "USER:")) else "USER:" + name


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--url", required=True)
    ap.add_argument("--token-file", required=True)
    ap.add_argument("--rc2png", required=True)
    ap.add_argument("--dashboard", default="lovelace")
    ap.add_argument("--view", type=int, default=0)
    ap.add_argument("--width", type=int, default=800)
    ap.add_argument("--height", type=int, default=480)
    ap.add_argument("--style", default="classic")
    ap.add_argument("--light", action="store_true")
    ap.add_argument("--bench", type=int, default=5, help="frames to time")
    ap.add_argument("--device-factor", type=float, default=100.0)
    ap.add_argument("--no-layout-animations", action="store_true",
                    help="as the panel draws by default (layout_animations: false)")
    ap.add_argument("--change", action="append", default=[], metavar="NAME=VALUE",
                    help="a value change to time after the first frame (the wall API's name); "
                         "default: each screen's first `.state` text, changed")
    ap.add_argument("--out", default="wall-sim")
    a = ap.parse_args()

    base = a.url.rstrip("/")
    token = open(os.path.expanduser(a.token_file)).read().strip()
    os.makedirs(a.out, exist_ok=True)
    query = {
        "screens": a.view, "wait": 0, "density": 1, "font_scale": 1,
        "width": a.width, "height": a.height, "style": a.style,
    }
    if not a.light:
        query["dark"] = 1
    path = f"{API}/values/{urllib.parse.quote(a.dashboard)}?" + urllib.parse.urlencode(query)
    answer = json.loads(get(base, token, path))
    screens = answer.get("screens") or list(answer.get("cards", {}))
    print(f"{a.dashboard} view {a.view}: {len(screens)} screens")
    print(f"{'screen':<14} {'bytes':>7} {'host ms':>8} {'panel ~ms':>10} {'paints':>7}  png / error")
    print(f"{'':<14} {'':>7} then per change: frames it animates, and the drawing they cost")
    worst = 0.0
    for sid in screens:
        card = answer["cards"].get(sid, {})
        if "error" in card or card.get("hidden"):
            print(f"{sid:<14} {'':>7} {'':>8} {'':>10} {'':>7}  {card.get('error') or 'hidden'}")
            continue
        blob = get(base, token, card.get("rc") or f"{API}/blobs/{card['design']}.rc")
        rc = os.path.join(a.out, f"{sid}.rc")
        png = os.path.join(a.out, f"{sid}.png")
        open(rc, "wb").write(blob)
        cmd = [a.rc2png, rc, png, "--fit", str(a.width), str(a.height),
               "--theme", "light" if a.light else "dark", "--bench", str(a.bench)]
        for name, value in card.get("values", {}).items():
            if name.endswith(".picture_url"):
                continue
            cmd += ["--named", f"{document_name(name)}={scalar_text(value)}"]
        changes = list(a.change)
        if not changes:
            state = next((k for k, v in card.get("values", {}).items()
                          if k.endswith(".state") and isinstance(v, str)), None)
            if state:
                changes = [f"{state}={card['values'][state]}~"]
        for change in changes:
            name, _, value = change.partition("=")
            cmd += ["--live", f"{document_name(name)}={value}"]
        run = subprocess.run(cmd, capture_output=True, text=True, timeout=600,
                             env={**os.environ, "ASAN_OPTIONS": "alloc_dealloc_mismatch=0",
                                  **({"RC_NO_LAYOUT_ANIMATION": "1"} if a.no_layout_animations else {})})
        m = re.search(r"bench: ([0-9.]+) ms/frame \((\d+) paints\)", run.stdout + run.stderr)
        if not m:
            print(f"{sid:<14} {len(blob):>7} {'':>8} {'':>10} {'':>7}  rc2png failed: {(run.stderr or run.stdout)[-200:]}")
            continue
        ms, paints = float(m.group(1)), int(m.group(2))
        worst = max(worst, ms)
        print(f"{sid:<14} {len(blob):>7} {ms:>8.2f} {ms * a.device_factor:>10.0f} {paints:>7}  {png}")
        for live in re.finditer(r"live (\S+?)=.*?: (\d+) frames, ([0-9.]+) ms drawing", run.stdout):
            name, frames, cost = live.group(1), int(live.group(2)), float(live.group(3))
            # rccore's animations run on the wall clock: the host draws a frame every 16 ms, so
            # it lasts about frames x 16 ms; the panel draws fewer, slower frames over that same
            # time (a slow frame skips ahead), each costing a host frame's drawing x the factor.
            per_frame = cost / frames * a.device_factor
            duration = frames * 16
            panel_frames = max(1, math.ceil(duration / max(per_frame, 16)))
            print(f"{'':<14}   change {name}: {duration} ms animation, {frames} host frames; "
                  f"panel ~{panel_frames} frames of ~{per_frame:.0f} ms "
                  f"(~{panel_frames * per_frame:.0f} ms busy)")
    print(f"worst screen ~{worst * a.device_factor:.0f} ms on the panel")


if __name__ == "__main__":
    sys.exit(main())
