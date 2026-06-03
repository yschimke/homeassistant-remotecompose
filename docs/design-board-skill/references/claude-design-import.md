# Importing into Claude Design (and exporting back)

Claude Design (Anthropic Labs) is an AI design tool in research preview (Pro /
Max / Team / Enterprise). Its intake is a drop zone — *"Images, docs,
references, Figma links, or folders"* — plus a few connect/capture paths. This
reference summarises what it accepts so a board can be built to match.

> Verify against the live product; formats below are assembled from Anthropic's
> help centre and third-party guides and may shift as the preview evolves.

## Inputs it accepts

| Input | Notes |
|---|---|
| **Images** | PNG, JPG, GIF, WebP. **PNG is preferred for UI screenshots** — sharp text, crisp edges, no JPEG artifacts. compose-preview already emits PNG. |
| **Docs** | DOCX, PPTX, XLSX |
| **Figma** | `.fig` file **upload**. A Figma *URL* pasted into the drop zone is unreliable — prefer the `.fig`, or bridge through Claude Code. |
| **GitHub repo** | Connect a repo so it reads real components / styling / tokens |
| **Local folder** | Point it at a code or asset folder |
| **Web capture** | Snapshot a live element from a URL (renders the page) |
| **Another project** | Reference an existing Claude Design project as context |

There is **no documented "paste an arbitrary image URL"** input — a raw
`https://…/screen.png` link is not a recognised drop-zone type. Plan on file
upload, repo connect, or web-capturing a hosted page.

## Best way to bring in compose-preview renders

1. Keep them **PNG, lossless, high-DPI** (render at 2×/xxhdpi where possible).
2. Assemble into **one structured HTML board** (this skill) so groupings, flow
   order, and intent notes are preserved.
3. Prefer **web capture** on the opened/served board — the labels and layout
   render into the snapshot as a single coherent brief.
4. For design-system fidelity, **also connect the repo** so styling/tokens come
   from code while the board conveys layout/intent.
5. Add a sentence of intent per group ("redesign the transport controls, keep
   the colour tokens") — reference shots + stated palette + intent gives the
   best output.

## Exports (the round-trip back to code)

Claude Design can export: internal share URL, save-as-folder, **Canva**, **PDF**,
**PPTX**, **standalone HTML**, **.zip**, and a **handoff bundle** meant for
Claude Code. There is no native Compose/Android export, so the bridge back is:

- export the **handoff bundle** → feed it to Claude Code to implement, or
- export **standalone HTML** → have Claude Code translate it to Compose, then
  re-render with compose-preview and diff (see the **compose-preview-review**
  skill) to confirm the redesign landed.
