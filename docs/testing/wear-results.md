# Wear results

What a cold start of the watch app shows, how long it takes, and how the pinned cards scroll.
How the benchmarks run: [baseline-profiles.md](baseline-profiles.md). The phone's numbers:
[startup-results.md](startup-results.md), [scroll-benchmark-results.md](scroll-benchmark-results.md).

## Seeded content

An unpaired watch has nothing pinned, so `:wear-benchmark` used to measure the empty state and the
Settings list. It now launches with `terrazzo.test.seed_pins=true`, which writes what a demo-mode
phone would have synced (`WearBenchmarkSeed`: a pinned section and a dozen pinned cards of the
common types, with their values) to the offline store the watch launches from. The extra only acts
in builds with `BuildConfig.TEST_HOOKS` (debug, `benchmarkRelease`, `nonMinifiedRelease`).

## 2026-10-23: emulator, Wear OS XL Round, API 36

`benchmarkRelease`, `suppressErrors=EMULATOR`. Emulator timings run on a desktop CPU: they show
what a launch draws and catch large regressions, not what a watch takes.

### What a cold start showed

From screen recordings of a cold, seeded launch, timed from the splash's first frame:

| From the splash | Before | After |
| --- | --- | --- |
| 0 ms | Splash icon | Splash icon |
| ~200 ms | An empty screen, only the time | |
| ~240 ms | | The pinned list |
| ~285 ms | The pinned list | |

`WearApp` returned before drawing anything until the theme and card style arrived from their
DataStore flows. They are now read before the first composition (a tiny preferences file, as the
phone reads its last dashboard), so the first frame is the list. It fades in over about 130 ms:
the system's window animation, the same with or without the empty frame.

### Numbers

Medians; startup over ten cold launches, scroll over five iterations of four flings each way.

| | Before | After |
| --- | --- | --- |
| Cold start, baseline profile (`timeToInitialDisplayMs`) | 199 ms (an empty frame) | 215 ms (the cards) |
| Cold start, no compilation | 204 ms (an empty frame) | 300 ms (the cards) |
| Scroll, baseline profile, frame CPU P50 / P99 | 1.6 / 5.4 ms (Settings) | 1.7 / 3.8 ms (pinned cards) |
| Scroll, no compilation, frame CPU P50 / P99 | 2.4 / 17.9 ms (Settings) | 3.1 / 18.9 ms (pinned cards) |

`timeToFullDisplayMs` (`ReportDrawn` where the pinned list composes) equals the first frame after
the change. Before and after measure different screens: "before" had no cards to draw.

The baseline profile is regenerated from the seeded journey, so it now covers the card players:
with the profile captured before the cards (the empty journey), the seeded cold start took 238 ms.

The profile plugin (benchmark 1.5.0) found no `-baseline-prof.txt` in the generator's output,
only `-startup-prof.txt`, and deleted the checked-in pair. The checked-in `baseline-prof.txt` and
`startup-prof.txt` were identical, so both are that output.
