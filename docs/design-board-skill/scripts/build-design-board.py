#!/usr/bin/env python3
"""Assemble rendered @Preview PNGs into a single self-contained HTML design board.

The board is structured (categories -> groups -> annotated frames) and inlines
every image as a base64 data URI, so the resulting `.html` is a single portable
file you can:

  * open in a browser and grab via Claude Design's "web capture", or
  * upload / drop into the Claude Design drop zone, or
  * serve (GitHub Pages) and web-capture the URL.

Driven by a JSON spec on stdin or via --spec. See scripts/design-board.spec.json.
"""
from __future__ import annotations

import argparse
import base64
import html
import json
import mimetypes
import sys
from pathlib import Path


def data_uri(path: str) -> str:
    p = Path(path)
    mime = mimetypes.guess_type(p.name)[0] or "image/png"
    b64 = base64.b64encode(p.read_bytes()).decode("ascii")
    return f"data:{mime};base64,{b64}"


def esc(s: str) -> str:
    return html.escape(s, quote=True)


def render_frame(item: dict) -> str:
    missing = not Path(item["src"]).exists()
    img = (
        f'<div class="missing">missing render:<br>{esc(item["src"])}</div>'
        if missing
        else f'<img loading="eager" src="{data_uri(item["src"])}" alt="{esc(item.get("caption",""))}">'
    )
    caption = esc(item.get("caption", ""))
    sub = esc(item.get("sub", ""))
    sub_html = f'<div class="sub">{sub}</div>' if sub else ""
    return (
        '<figure class="frame">'
        f'<div class="shot">{img}</div>'
        f'<figcaption><div class="cap">{caption}</div>{sub_html}</figcaption>'
        "</figure>"
    )


def render_group(group: dict) -> str:
    note = group.get("note", "")
    note_html = f'<p class="note">{esc(note)}</p>' if note else ""
    frames = "\n".join(render_frame(it) for it in group.get("items", []))
    layout = group.get("layout", "row")
    return (
        '<section class="group">'
        f'<h3>{esc(group.get("title",""))}</h3>'
        f"{note_html}"
        f'<div class="frames {esc(layout)}">{frames}</div>'
        "</section>"
    )


def render_category(cat: dict) -> str:
    intro = cat.get("intro", "")
    intro_html = f'<p class="intro">{esc(intro)}</p>' if intro else ""
    groups = "\n".join(render_group(g) for g in cat.get("groups", []))
    return (
        '<section class="category">'
        f'<div class="cat-head"><span class="badge">{esc(cat.get("badge",""))}</span>'
        f'<h2>{esc(cat.get("title",""))}</h2></div>'
        f"{intro_html}{groups}"
        "</section>"
    )


def render_palette(palette: list[dict]) -> str:
    if not palette:
        return ""
    chips = "".join(
        f'<div class="chip"><span style="background:{esc(c["hex"])}"></span>'
        f'<small>{esc(c.get("name",""))}<br>{esc(c["hex"])}</small></div>'
        for c in palette
    )
    return f'<div class="palette">{chips}</div>'


CSS = """
:root{--bg:#0f1115;--panel:#171a21;--frame:#1d2129;--line:#2a2f3a;--text:#e8eaed;--muted:#9aa0ab;--accent:#7aa2ff}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--text);font:15px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Inter,sans-serif}
header.board{padding:40px 48px 24px;border-bottom:1px solid var(--line)}
header.board h1{margin:0 0 6px;font-size:30px;letter-spacing:-.02em}
header.board .tagline{color:var(--muted);margin:0 0 18px;max-width:70ch}
.palette{display:flex;gap:14px;flex-wrap:wrap}
.chip{display:flex;align-items:center;gap:8px}
.chip span{width:26px;height:26px;border-radius:7px;border:1px solid rgba(255,255,255,.15);display:block}
.chip small{color:var(--muted);font-size:11px;line-height:1.25}
main{padding:8px 48px 80px;max-width:1700px;margin:0 auto}
.category{margin-top:48px}
.cat-head{display:flex;align-items:center;gap:14px;position:sticky;top:0;background:var(--bg);padding:18px 0 10px;z-index:5}
.badge{background:var(--accent);color:#0b0d12;font-weight:700;font-size:12px;padding:4px 10px;border-radius:999px;letter-spacing:.03em}
.cat-head h2{margin:0;font-size:22px;letter-spacing:-.01em}
.intro{color:var(--muted);max-width:80ch;margin:0 0 8px}
.group{background:var(--panel);border:1px solid var(--line);border-radius:16px;padding:20px 22px;margin:18px 0}
.group h3{margin:0 0 4px;font-size:16px}
.note{color:var(--muted);margin:0 0 16px;font-size:13px;max-width:90ch}
.frames{display:flex;gap:22px;flex-wrap:wrap;align-items:flex-start}
.frames.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(240px,1fr))}
.frames.wide .frame{flex:1 1 100%}
.frame{margin:0;background:var(--frame);border:1px solid var(--line);border-radius:12px;padding:12px;display:flex;flex-direction:column}
.shot{display:flex;justify-content:center;align-items:center;background:#0b0d12;border-radius:8px;overflow:hidden}
.shot img{max-width:100%;height:auto;display:block}
figcaption{padding-top:10px}
.cap{font-weight:600;font-size:13px}
.sub{color:var(--muted);font-size:12px;margin-top:2px}
.missing{color:#ff8080;font-size:12px;padding:24px;text-align:center}
footer{color:var(--muted);font-size:12px;padding:24px 48px;border-top:1px solid var(--line)}
"""


def build(spec: dict) -> str:
    cats = "\n".join(render_category(c) for c in spec.get("categories", []))
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{esc(spec.get("title","Design board"))}</title>
<style>{CSS}</style></head>
<body>
<header class="board">
  <h1>{esc(spec.get("title","Design board"))}</h1>
  <p class="tagline">{esc(spec.get("tagline",""))}</p>
  {render_palette(spec.get("palette", []))}
</header>
<main>
{cats}
</main>
<footer>{esc(spec.get("footer",""))}</footer>
</body></html>"""


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--spec", help="JSON spec path (default: stdin)")
    ap.add_argument("--out", required=True, help="output .html path")
    args = ap.parse_args()
    spec = json.load(open(args.spec) if args.spec else sys.stdin)
    out = Path(args.out)
    out.write_text(build(spec), encoding="utf-8")
    kb = out.stat().st_size / 1024
    print(f"wrote {out} ({kb:.0f} KB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
