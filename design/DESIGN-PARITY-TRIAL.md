# design-parity trial — Tile card (Claude Design ref vs Compose candidate)

A first end-to-end run of [`yschimke/design-parity`](https://github.com/yschimke/design-parity)
against this repo: a real parity verdict for the **tile** card, comparing a
committed **Claude Design** reference against the **Compose** candidate rendered
by `compose-preview`.

This is a tool-evaluation artifact, not app behaviour. It documents what ran,
the verdict, and the friction worth feeding back to design-parity.

## What was compared

| | |
|---|---|
| **Reference (design)** | Claude Design HTML exports under [`design/reference/`](reference/), each embedding a `application/design-parity+json` handoff block. The handoff `src` reuses the committed ground-truth HA-frontend captures in [`references/tile/`](../references/tile/). Consumed by `@design-parity/adapter-claude-design`. |
| **Candidate (code)** | `compose-preview show --json --module previews --filter Tile` → the `Tile_TemperatureSensor` / `Tile_LightOn` `@Preview`s (light + dark), staged under [`design/candidates/`](candidates/). |
| **Correspondence** | [`design-map.json`](../design-map.json) (root) links each code handle → `claude-design` ref. |
| **Direction** | [`.design-parity.json`](../.design-parity.json) → `code-led` (advisory; the shipped Compose code is reality). |
| **Components** | `TileCardPreviews.kt#Tile_TemperatureSensor`, `TileCardPreviews.kt#Tile_LightOn`. |

design-parity was run from `main` (PRs #26 + #27 + #28 merged); #26 being merged
means the task's "use `main`" branch applies, and #27 (the #24 fix) is included.

## How to reproduce

```sh
# 1. render the candidate previews (Android SDK at /opt/android-sdk)
cd <ha-repo>
ANDROID_HOME=/opt/android-sdk COMPOSE_AI_TOOLS=true \
  compose-preview show --json --module previews --filter Tile
# PNGs land in previews/build/compose-previews/renders/ ; staged copies live in
# design/candidates/ (raw) and design/candidates/normalized/ (see "dimension drift").

# 2. run the parity verdict
node <design-parity>/packages/action/dist/cli/run.js run \
  --repo . \
  --components "previews/src/main/kotlin/ee/schimke/ha/previews/TileCardPreviews.kt#Tile_TemperatureSensor" \
  --candidates candidates.json \
  --out out/temperature-sensor/
```

## Verdict

`⚠️ Parity warn` (`code-led` → advisory, exit 0) for both components:

```
## Parity verdict: …#Tile_TemperatureSensor — ⚠️ warn
**Semantics**
- ⚠️ candidate root node has no accessibility role
- ⚠️ candidate root node has no accessible label
**Visual**
- ⚠️ default/light/compact: 6.0% of pixels differ from reference
- ⚠️ default/dark/compact:  6.0% of pixels differ from reference

## Parity verdict: …#Tile_LightOn — ⚠️ warn
**Visual**
- ⚠️ default/light/compact: 10.1% of pixels differ from reference
- ⚠️ default/dark/compact:  10.1% of pixels differ from reference
```

Triptychs (reference | candidate | pixel-diff heatmap) in
[`out/temperature-sensor/`](../out/temperature-sensor/) and
[`out/light-on/`](../out/light-on/). The heatmap pinpoints the **real**
divergences between the HA frontend and our Compose rendering:

- **Thermometer icon**: muted blue-grey in the HA reference vs a saturated blue
  in the Compose candidate.
- **Secondary text** (`21.4 °C`, `On`): lighter grey in the candidate; small
  horizontal position / weight shift on the title.
- **Light-on tile** diverges more (10.1%) than the sensor tile (6.0%) — the amber
  bulb glow and container tint differ from the reference.

These are genuine code-vs-design differences a parity bot should surface — the
trial works.

## Friction (feedback for design-parity)

1. **Theme is not auto-detected from these previews → must be set on the
   candidate by hand (relates to #24).** Every `compose-preview` entry reports
   `uiMode: 0`, because the previews theme via the app's own
   `ProvideHaTheme(HaTheme.Light/Dark)` wrapper, not Android night-mode
   `uiMode`. So `@design-parity/candidate`'s `themeFromUiMode` yields *no theme*,
   and a reference image tagged `theme: dark` would never pair with the
   candidate. We worked around it by building `candidates.json` by hand with
   explicit `theme` (derived from the `_Dark` id suffix). #27 fixed the *size*
   half of #24; the *theme* half has the same failure mode for any project that
   themes via a `CompositionLocal` rather than `uiMode`. Suggestion: let the
   candidate path derive theme from the preview id / a name convention, or
   document that `theme` must be supplied when `uiMode` is unset.

2. **A small dimension delta is treated as a 100% visual mismatch with no
   aligned diff.** The raw Robolectric render is **490×112** while the reference
   capture is **492×112** — a 2px density-rounding gap (Robolectric density
   2.625 vs the Puppeteer `deviceScaleFactor=2` the references were shot at).
   `diffImagePair` short-circuits any dimension difference to
   `diffPixels = max(area)` ⇒ **100% differ**, and the triptych's third panel is
   empty (no heatmap). To get a meaningful score we padded the candidate to
   492×112 (`design/candidates/normalized/`), after which the diff dropped to the
   real **6% / 10%**. Suggestion: tolerate a small dimension delta
   (letterbox/pad, or scale-to-fit) before declaring a total mismatch, so a
   sub-pixel rounding difference between two render tools doesn't mask the actual
   content diff.

3. **Triptych filenames collide across components.** Triptychs are written as
   `triptych-<state>-<theme>-<size>.png`, keyed only by the image variant — not
   the `componentId`. Running both tile components into one `--out` dir made
   `Tile_LightOn` overwrite `Tile_TemperatureSensor`'s triptychs (both are
   `default/*/compact`). We worked around it with one `--out` dir per component.
   Suggestion: namespace triptychs by `componentId` (or write them under a
   per-component subdir).

4. **Candidate semantics (a11y/hierarchy) aren't wired into the run, so the
   a11y/i18n/token/semantic dimensions are effectively no-ops (relates to #8
   part 2 / #25).** We passed an empty `semantics.root`, which is why the only
   semantic findings are "root has no role/label". Getting real semantics out of
   `compose-preview` today means `compose-preview a11y`, which (a) is a separate
   expensive pass — it walks ATF over the **entire module** (185 previews here),
   `--filter` only narrows the *printed* output, not the work — and (b) emits an
   `accessibility.json` shape that the design-parity candidate path
   (`SpawnComposePreviewCli`, which reads `data/<id>/a11y/hierarchy.json`)
   doesn't yet consume. Live `compose-preview` candidate rendering (incl.
   semantics) is exactly what #8 part 2 defers; this trial confirms the gap on a
   real module.

5. **Reference tokens omitted on purpose.** Claude Design has no token export
   here, and emitting hand-authored token values would have produced bogus
   "missing from candidate" errors against the empty candidate semantics, so the
   handoff blocks carry images only. With #25 (checks-config loader) and a real
   token source, the token dimension could be exercised meaningfully.

## Files added by this trial

```
design-map.json                         # root correspondence manifest
.design-parity.json                     # parity direction (code-led)
design/reference/tile-temperature-sensor.html   # Claude Design export + handoff
design/reference/tile-light-on.html              # Claude Design export + handoff
design/candidates/*.png                 # raw compose-preview renders (490×112)
design/candidates/normalized/*.png      # padded to 492×112 (dimension-drift workaround)
candidates.json                         # CandidateRender[] passed to --candidates
out/<component>/triptych-*.png          # reference | candidate | diff heatmaps
design/DESIGN-PARITY-TRIAL.md           # this report
```
