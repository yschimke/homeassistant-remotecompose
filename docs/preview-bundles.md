# Preview bundles

A **preview bundle** is the portable artifact produced by
`compose-preview bundle pack`. It is a **PNG+ZIP polyglot**: the same bytes are
simultaneously a valid PNG and a valid ZIP archive.

```
bundle.png
├── (read as PNG) ── the cover: the default preview, shown by any image viewer
└── (read as ZIP) ── manifest + the remaining previews + the minimal classpath
```

## Why a polyglot

The goal is a preview you can hand to someone **detached from the project**.
Because the cover is a normal PNG, dropping `bundle.png` into Downloads and
opening it shows the default preview immediately — no checkout, no Gradle, no
tooling. Anything that wants more than the cover reads the ZIP side of the same
file.

## One default, many previews

- The **cover** is the single default preview — the first preview passed to
  `--id`, or the first discovered preview when `--id` is omitted. It is the PNG
  that the file renders as.
- **Every other preview** is stored inside the ZIP, in its well-known interior
  alongside the classpath needed to replay it. They never show up as the cover;
  you reach them with `bundle extract` / `bundle inspect`.

This keeps the "open it and see something useful" property (one cover) while
still carrying the full set (the rest, inside the ZIP).

## Commands

```sh
# Pack the previews module into previews/build/compose-previews/bundle.png.
# First preview = cover; the rest go inside the ZIP.
compose-preview bundle pack --module previews

# Pick the cover (and optionally narrow the set) explicitly. First --id wins.
compose-preview bundle pack --module previews --id Tile_TemperatureSensor -o out.png

# Read the manifest without unpacking.
compose-preview bundle inspect bundle.png

# Extract the cover + every interior preview to a directory.
compose-preview bundle extract bundle.png -o out/
```

Pass `--module` — bare auto-detection fans out across every module and trips on
`demo-app`, which has no `@Preview` tooling on its classpath.

## CI: pack + show in the terminal

[`.github/workflows/preview-bundle.yml`](../.github/workflows/preview-bundle.yml)
packs one sample bundle and dumps each preview to the terminal:

- **The TUI.** `compose-preview show --images=kitty` paints previews inline
  using the terminal's image protocol (kitty graphics). It is TTY-gated and
  auto-off when stdout is piped, so it is silent in CI logs but works for a
  human running it locally in a capable terminal (kitty, WezTerm, Ghostty).
- **ASCII fallback.** [`chafa`](https://hpjansson.org/chafa/) renders each
  preview as ASCII/Unicode art when no graphics protocol is available — which
  is the case in CI logs. It won't look great, but every preview is visible on
  any terminal.

The packed `bundle.png` and the extracted previews are uploaded as the
`preview-bundle` artifact.
