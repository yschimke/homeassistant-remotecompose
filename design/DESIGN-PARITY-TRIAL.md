# design-parity trial — Tile card (static preview-bundle path)

End-to-end run of [`yschimke/design-parity`](https://github.com/yschimke/design-parity)
against this repo, via the **static preview-bundle** candidate source: a committed
**Claude Design** reference (HTML export) vs the **Compose** candidate, rendered by
**compose-ai-tools** and packed into a portable PNG+ZIP bundle.

This is a tool-evaluation artifact, not app behaviour. It records what ran, the
verdict, the self-contained comparison pages, and the friction worth feeding back
to design-parity / compose-ai-tools.

## What was compared

| | |
|---|---|
| **Reference (design)** | Four Claude Design HTML exports under [`design/reference/`](reference/) — one per `@Preview` — each embedding an `application/design-parity+json` handoff whose `src` reuses the committed ground-truth HA-frontend captures in [`references/tile/`](../references/tile/). Consumed by `@design-parity/adapter-claude-design`. |
| **Candidate (code)** | `compose-preview bundle pack --module previews --id <4 tile ids>` → the portable polyglot [`design/candidates/tile-bundle.png`](candidates/tile-bundle.png). design-parity's `bundleCandidateSource` reads it statically (pure JS, no JVM) into `CandidateRender`s with `data:` image URIs. |
| **Correspondence** | [`design-map.json`](../design-map.json) links each code handle → `claude-design` ref **and** an explicit `previewId` (the compose-ai-tools preview id) so the bundle candidate pairs with its reference — design-parity issue #44. |
| **Direction** | [`.design-parity.json`](../.design-parity.json) → `code-led` (advisory). |
| **Components** | `Tile_TemperatureSensor`, `Tile_TemperatureSensor_Dark`, `Tile_LightOn`, `Tile_LightOn_Dark` in `TileCardPreviews.kt`. |

Run from design-parity `agent/resolver-previewid-mapping` (PR #45 / issue #44),
which adds the preview-id ↔ code-handle reconciliation this trial depends on.

## How to reproduce

```sh
cd <ha-repo>
# 1. render + pack the candidate previews into a portable bundle
ANDROID_HOME=/opt/android-sdk COMPOSE_AI_TOOLS=true \
  compose-preview bundle pack --module previews \
    --id "<exact preview id>" ... -o design/candidates/tile-bundle.png

# 2. parity verdict (markdown) — bundles read statically, no JVM
node <design-parity>/packages/action/dist/cli/run.js run --repo . \
  --components "<4 code handles>" \
  --candidate-bundles design/candidates/tile-bundle.png --out out/

# 3. self-contained HTML comparison page per component
#    (renderHtmlReport over the resolved reference + bundle candidate + verdict;
#     the run CLI does not emit HTML yet — see friction #7)
```

## Verdict

`⚠️ Parity warn` (`code-led` → advisory, exit 0) for all four components. Each
paired with its reference **through the #44 `previewId` link**:

```
## …#Tile_TemperatureSensor — ⚠️ warn
**Semantics**
- ⚠️ candidate root node has no accessibility role
- ⚠️ candidate root node has no accessible label
**Visual**
- ⚠️ default/compact: 100.0% of pixels differ from reference
```

Self-contained comparison pages (reference | candidate | diff, every image
inlined): [`out/parity-Tile_TemperatureSensor.html`](../out/parity-Tile_TemperatureSensor.html)
and the three siblings. The pages show the genuine reference-vs-candidate
divergence (the HA frontend's muted thermometer/text vs the Compose candidate's
saturated icon and lighter secondary text) side by side; the **automated pixel
score is 100% only because of a 2px dimension drift** — see friction #4.

## Friction (feedback for design-parity / compose-ai-tools)

1. **Theme isn't derivable from these previews (`uiMode: 0`).** The tile previews
   theme via the app's `ProvideHaTheme(HaTheme.Light/Dark)` wrapper, not Android
   night-mode `uiMode`, so the bundle reports `uiMode: 0` and the candidate images
   carry **no `theme`**. Light/dark are therefore *separate preview ids*
   (`Tile_LightOn` vs `Tile_LightOn_Dark`), and the references must omit `theme`
   too or pairing (keyed on `state/theme/size`) drops them. This is the *theme*
   half of #24 (the *size* half is fixed): a project that themes via a
   `CompositionLocal` gets no theme on the candidate. Suggestion: let the
   candidate path derive theme from the preview id / name convention, or document
   that `theme` must be supplied when `uiMode` is unset.

2. **Bundle `sourceFile` is module-relative, so the #44 *convention* can't match.**
   `previews.json` reports `sourceFile: src/main/kotlin/ee/schimke/ha/previews/TileCardPreviews.kt`
   (module-relative), while the reference/design-map code handle is repo-relative
   (`previews/src/main/kotlin/…`). The convention (`sourceFile#functionName`)
   therefore derives the wrong handle, and we had to use the **explicit
   `previewId` field** on every design-map entry (high confidence). Suggestion:
   compose-ai-tools could emit a repo-relative `sourceFile`, or #44's convention
   could tolerate a module-root prefix.

3. **The bundle carries no semantics, so a11y/i18n/contrast/token are no-ops.**
   The v2 bundle (`PreviewBundleFormat`) has `previews.json` + `previews/<id>.png`
   + `classes/app.jar` + `report.json`, but **no `previews/<id>.semantics.json`**
   (the blob design-parity's reader looks for). So the only findings are the
   structural "root has no role/label" and the visual diff. This is the
   "compose-ai-tools must bake the a11y tree + resolved fg/bg colors + typography
   into the bundle" gap: until it does, the rich verdict needs the **daemon path
   (#43)**, which fetches `a11y/hierarchy` + native findings live.

4. **A 2px dimension delta is scored as a 100% visual mismatch with no heatmap.**
   The Robolectric render is **490×112**; the ground-truth capture is **492×112**
   (a density-rounding gap: Robolectric 2.625 vs the Puppeteer `deviceScaleFactor=2`
   the references were shot at). `diffImagePair` short-circuits *any* dimension
   difference to `diffPixels = max(area)` ⇒ 100%, and the triptych's diff panel is
   empty. Aligning dimensions yields the real ~6–10% (measured in an earlier
   manual run). Suggestion (likely a **new issue**): tolerate a small dimension
   delta — letterbox/pad or scale-to-fit — before declaring a total mismatch, so a
   sub-pixel rounding difference between two render tools doesn't mask the content
   diff.

5. **Triptych filenames collide across components.** `diff` writes
   `triptych-<state>-<theme>-<size>.png`, keyed only by the image variant, so
   running multiple components into one `--out` dir makes the last component
   overwrite the others (all four tiles are `default/compact`). Suggestion:
   namespace triptychs by `componentId`.

6. **`compose-preview list --json` mangled the non-ASCII id when piped.** Preview
   ids embed the `@Preview` name with an em-dash separator
   (`…Tile_TemperatureSensor_tile — sensor temperature`). Piped through a non-UTF-8
   stdout the em-dash came out as `?`, so `bundle pack --id "<that>"` failed with
   "preview id not found". We sourced the exact bytes from the on-disk
   `build/compose-previews/previews.json` instead. Suggestion (compose-ai-tools):
   force UTF-8 on JSON stdout regardless of locale/pipe.

7. **`design-parity run` doesn't emit the HTML page.** `report-html` exists as a
   separate `design-parity-report` CLI that needs reference + candidate + verdict
   JSON, which the run doesn't dump — so producing the comparison page took a
   small harness over the package APIs. Suggestion: wire `renderHtmlReport` into
   `design-parity run --out` (or have the run write the per-component
   reference/verdict JSON alongside the triptychs).

## Files added by this trial

```
design-map.json                              # correspondence + explicit previewId per entry (#44)
.design-parity.json                          # parity direction (code-led)
design/reference/tile-*.html                 # 4 Claude Design exports (one per @Preview, no theme)
design/candidates/tile-bundle.png            # compose-ai-tools portable bundle (PNG+ZIP polyglot)
out/parity-*.html                            # 4 self-contained comparison pages
design/DESIGN-PARITY-TRIAL.md                # this report
```
