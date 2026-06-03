---
name: design-board
description: Export rendered Compose @Preview screens into a single self-contained HTML "design board" structured for Claude Design (or any design tool that accepts file upload / web capture). Use when someone wants to hand their current app UI to Claude Design / a designer as context, stage screens for a redesign, or produce a grouped, annotated screen gallery instead of a pile of loose PNGs. Pairs with the compose-preview skill.
---

# Design Board

Turn the screens you can already render with **compose-preview** into one
portable, structured HTML board you can drop into **Claude Design** — or any
tool whose intake is "drop files / web-capture a page" (Figma via `.fig`,
moodboards, design reviews).

Maintained at [github.com/yschimke/skills](https://github.com/yschimke/skills)
under `skills/design-board/`. Depends on the sibling
[**compose-preview**](../compose-preview/SKILL.md) skill for the renders.

## Why a board, not a folder of PNGs

A loose pile of screenshots loses the relationships a design tool can't infer:
which states belong to the same screen, navigation flow order, and your intent.
A single board preserves them as one coherent brief:

- **Grouping + captions + intent notes** travel with the images.
- **Self-contained**: images are inlined as base64, so the `.html` is one file
  you can upload, open, or serve — no broken relative paths.
- **Import-method agnostic**: works with Claude Design *web capture* (open/serve
  the page, snapshot it), file upload, or a local-folder import.

## The pattern (what works well)

Organise the board into a small number of **categories**, each with **groups**
of **annotated frames**. The two-category split below is the one that has
worked best for an app with both screens and resizable widgets — adapt the
categories to the app:

1. **App guide** — the main screens a user moves through, the **design system**
   (theme/palette/typography showcases), and an **intro to the primary
   surfaces** (dashboards, feeds, whatever the app is built around).
2. **Components by variant** — one frame *per component class*, showing that
   component across every size/surface it targets side by side (e.g. app card ·
   watch small · watch large · widget smallest/mid/largest). If the project
   already has a "matrix" preview that renders all sizes in one composable,
   render that — it gives you the whole row as a single image.

Altitude guidance: a category intro is one or two sentences; a group note is the
*why* (size range, flow position, what to change); a frame caption is the screen
name + state. Don't annotate every pixel — give the tool tone and intent, not a
spec.

## Workflow

### 1. Render the previews (compose-preview)

Check the renderer is present, then render the modules whose previews you need.
One Gradle build per module renders everything in it; Gradle caching makes
re-renders cheap.

```sh
compose-preview --version            # ensure the CLI exists (see compose-preview skill)
compose-preview show --module <m> --json > /tmp/<m>-show.json
```

Each JSON entry has an `id` and a `pngPath`. Pick the previews for each category
by `id`. A purpose-built "size matrix" `@Preview` (one composable that lays out
every size) is ideal for category 2 — render once, use as one frame.

> Cloud sandboxes: set the Android SDK first (`echo 'sdk.dir=/opt/android-sdk' >
> local.properties`) and use a Custom network policy with the Google
> Maven/Gradle/fonts hosts allowlisted — see compose-preview's
> `references/agent-cloud.md`.

### 2. Write a board spec

The spec is JSON: `title`, `tagline`, an optional `palette` (swatches), and
`categories[] -> groups[] -> items[]`. Each item points at a rendered `pngPath`
with a `caption` and optional `sub`. See [references/spec-schema.md](./references/spec-schema.md)
for the full schema and a worked example. Build the spec programmatically from
the `show --json` output so paths can't drift.

### 3. Generate the board

```sh
python3 scripts/build-design-board.py --spec board-spec.json --out design-board.html
```

`build-design-board.py` (bundled) inlines every PNG as base64 and emits one
self-contained, dark-theme HTML file. Missing renders are shown as a visible
placeholder rather than failing, so a partial board still builds.

### 4. Import into Claude Design

Three routes, most robust first:

- **Web capture** — open `design-board.html` in a browser (or serve it via
  GitHub Pages / a branch) and use Claude Design's *web capture* on the page.
  The grouping, captions, and flow order render into the snapshot, so Claude
  reads it as a designed brief rather than disconnected frames. **Best fidelity.**
- **File upload / drop zone** — drag the `.html` (or the rendered PNGs) into the
  "Images, docs, references, Figma links, or folders" drop zone.
- **Connect repo** — for design-system fidelity, also point Claude Design at the
  GitHub repo so it reads the real components/tokens; the board then conveys
  layout/intent while the code conveys exact styling.

See [references/claude-design-import.md](./references/claude-design-import.md)
for supported formats (images: PNG/JPG/GIF/WebP — **PNG preferred for UI**;
docs: DOCX/PPTX/XLSX; Figma via `.fig` upload, not URL), export formats, and the
round-trip back to code (handoff bundle / standalone HTML → Claude Code).

## Getting the file from a cloud agent to you

Renders and the board live in the agent's container, not your machine. To bridge:

- have the agent **send you the file** (it downloads, then you drop it into
  Claude Design), or
- **commit the board** (or the PNGs) to a branch and use Claude Design's repo /
  web-capture import. The generated HTML can be large (base64); prefer
  gitignoring it and committing the spec + generator so it's reproducible.

## Files

- `scripts/build-design-board.py` — the generator (stdin or `--spec`, `--out`).
- `references/spec-schema.md` — board spec JSON schema + example.
- `references/claude-design-import.md` — Claude Design formats & import methods.
