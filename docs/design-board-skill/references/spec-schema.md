# Board spec schema

The board is described by a single JSON object passed to
`scripts/build-design-board.py` via `--spec <file>` or stdin.

## Schema

```jsonc
{
  "title": "string",                 // board H1
  "tagline": "string",               // one-paragraph intro under the title
  "footer": "string",                // small print at the bottom
  "palette": [                       // optional swatch row (design-system colours)
    { "name": "string", "hex": "#RRGGBB" }
  ],
  "categories": [
    {
      "badge": "string",             // short chip, e.g. "01 · App"
      "title": "string",
      "intro": "string",             // 1–2 sentences
      "groups": [
        {
          "title": "string",
          "note": "string",          // the WHY: size range, flow position, intent
          "layout": "row|grid|wide", // frame layout (default "row")
          "items": [
            {
              "src": "/abs/path/to/render.png",  // a compose-preview pngPath
              "caption": "string",               // screen/component name
              "sub": "string"                    // optional: state / size / theme
            }
          ]
        }
      ]
    }
  ]
}
```

`layout`:
- `row` — frames flow left-to-right, wrapping (good for phone screens).
- `grid` — auto-fill grid (good for many same-size tiles, e.g. theme swatches).
- `wide` — each frame spans the full width (good for wide "size matrix" images).

Missing `src` files render as a visible placeholder, so a partial board still
builds — useful while iterating.

## Build the spec programmatically

Drive it off `compose-preview show --module <m> --json` so paths never drift:

```python
import json
d = json.load(open("/tmp/previews-show.json"))
items = d if isinstance(d, list) else d.get("previews", [])
by_id = {it["id"]: it["pngPath"] for it in items}

def pick(substr):
    return next(p for i, p in by_id.items() if substr in i)

spec = {
  "title": "MyApp — Design Board",
  "tagline": "Rendered @Preview exports staged for Claude Design.",
  "palette": [{"name": "Primary", "hex": "#03A9F4"}],
  "categories": [
    {
      "badge": "01 · App", "title": "App guide",
      "intro": "Main screens, design system, primary surfaces.",
      "groups": [
        {"title": "Main screens", "note": "Phone @412×892dp.", "layout": "row",
         "items": [
           {"src": pick("Screen_Home"), "caption": "Home"},
           {"src": pick("Screen_Settings"), "caption": "Settings"},
         ]},
      ],
    },
    {
      "badge": "02 · Components", "title": "Components by variant",
      "intro": "Each component across every size it targets.",
      "groups": [
        {"title": "Cards", "note": "App · watch · widget sizes.", "layout": "wide",
         "items": [{"src": pick("Matrix_Tile"), "caption": "Tile"}]},
      ],
    },
  ],
}
json.dump(spec, open("board-spec.json", "w"), indent=2)
```

Then:

```sh
python3 scripts/build-design-board.py --spec board-spec.json --out design-board.html
```
