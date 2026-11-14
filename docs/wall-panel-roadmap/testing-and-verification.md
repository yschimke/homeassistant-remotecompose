# Testing and verification

A device build, upload and boot costs minutes, and a crash costs more. Do the work on a desktop
host running the same renderer, and use the device only to confirm.

## A host renderer from day one

Build the same engine and paint context for the desktop as a command-line renderer:
`render in.rc out.png [--fit W H] [--named NAME=VALUE]… [--live NAME=VALUE]… [--frames N]`.
It should:

- **Render a frame** to PNG with values pushed by name, as the panel does.
- **Time a change** (`--live`): push a value after the first frame and report each following
  frame's data, layout, paint and raster time. Divide nothing; multiply by ~100 for the panel.
- **Step a deterministic clock** (`--live-clock MS`) instead of sleeping, so transitions draw the
  same frames every run.
- **Print a hash per frame** so a change in drawing shows up as a changed hash, not a judgement
  call on an image.
- **Toggle each optimisation** with an environment variable (layout animations off, text cache
  off, glyph source off, fast animation path off). Every optimisation must be comparable to
  running without it.
- **Load glyph packs** (`--glyph-pack FILE`) so text is composed as on the panel.

Run it under AddressSanitizer and UBSan in CI. Note what it can't show: on the host, allocations
don't fail, so memory crashes need device telemetry.

## Checks that catch real bugs

| Check | How | Catches |
|---|---|---|
| Damage matches full redraw | Render a run of value changes with damage tracking; at the end compare both buffers to a full redraw of the same frame, pixel for pixel, for every screen, with layout animations on and off | Missed damage, per-buffer backlog bugs, fractional image placement |
| Optimisation is invisible | Frame hashes with and without each optimisation (fast animation path, touch epoch, text cache) | Optimisations that change pixels |
| Composed text matches rasterised text | Diff a screen with glyph packs against the rasteriser; only edge anti-aliasing may differ, widths identical | Advance or baseline errors |
| Pack parser is safe | Feed every truncation of a real pack; it must refuse each, never read past | Untrusted fetched data |
| Values reach the document | For each card, change each named value and check the frame changed | Slots the server sends that the design doesn't declare |
| Layout cost scales | Count measures per frame per component | Exponential re-measuring |

## A simulator against the real Home Assistant

A script that polls the wall API exactly as the panel does (screens of one view, at the panel's
size and density), fetches each design, pushes its values and renders it with the host renderer.
For each screen it prints document bytes, host ms a frame and an estimate for the panel, and
writes a PNG. Use it before every flash: if the host renders today's real designs and values
correctly and quickly, the device problems that remain are memory or hardware.

## On the device

- **A screenshot endpoint** reading the presented buffer (`GET /snapshot.bmp?step=2`, LAN only, off
  by default). Look at it after every flash.
- **Per-frame stats lines**, every few seconds: data, layout (measured / cached), paint, raster,
  text runs rasterised (and composed), paints, PSRAM and internal free.
- **Memory telemetry** whenever PSRAM free moves by 8 KB or its largest block by 16 KB, and what
  each cache holds. Keep a log client running in the background, appending to a file.
- **Survive 60 s with cards on screen** before trusting any measurement: an earlier crash rolls
  the update back, and you'll be measuring the previous image.
- **Bisect on the device when a crash appears.** Rebuild the last known-good source and run it
  under the same conditions. If it also crashes, the cause is the data or the server, not your
  change.
- **Read crashes against the matching ELF.** A crash record printed after a rollback belongs to a
  different build.

## Before calling a step done

- Host: the change is timed, hashes compared, damage check passes on every real screen.
- Device: boots, draws cards, survives several minutes of value changes and a page through every
  screen, with PSRAM minimum logged and above ~100 KB.
- The first frame shows current values, with nothing animating in.
