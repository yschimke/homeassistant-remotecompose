# Baseline profiles and macrobenchmarks

`:app` and `:wear` each ship a baseline profile (plus a startup profile for DEX layout) and
apply `androidx.profileinstaller`, so sideloaded and non-Play installs get the AOT benefit too.
The profiles are produced, and the apps measured, by two `com.android.test` modules:

| Module | Target | Journeys |
| --- | --- | --- |
| `:app-benchmark` | `:app` | Cold start into demo mode, then switch to the 3D Printing, Meshcore and GitHub boards and fling each (the default Security board fits on one screen); then the 3D Printing board again with every section open, dragged |
| `:wear-benchmark` | `:wear` | Cold start onto the top-level list, seeded with a demo phone's pins (`terrazzo.test.seed_pins=true`), fling the pinned cards, open the pinned section, open Settings and fling it, open the dashboard library |

Each module has a `BaselineProfileGenerator`, a `StartupBenchmark` (`StartupTimingMetric`, cold)
and a `ScrollBenchmark` (`FrameTimingMetric`, i.e. jank), the latter two run with no compilation
and with the baseline profile so the profile's effect shows as the gap.

`:app-benchmark` also has `DashboardScrollJankBenchmark`, for the jank of cards coming on screen.
`ScrollBenchmark` flings with sections collapsed (the default), so few cards enter view; this one
opens every section (`terrazzo.test.expand_sections=true`) and drags the 3D Printing board down
and back at a steady pace, from a cold process each iteration: the way down meets every card for
the first time, the way back cards already played. Besides `FrameTimingMetric` it reports
`TraceSectionMetric` sums and counts for the app's `harc:cardTemplate`, `harc:captureDocument`
and `harc:decodeDocument` sections (`HarcTraceSections` in `rc-converter`), the main-thread work
a card does as it appears. Run it alone with
`-Pandroid.testInstrumentationRunnerArguments.class=ee.schimke.terrazzo.benchmark.DashboardScrollJankBenchmark`.

Both modules have a `MemoryBenchmark` (`MemoryUsageMetric`: Java heap, anonymous and file-backed
RSS), with the baseline profile and a fresh process each iteration. Its browse test (the phone's
expanded 3D Printing board dragged down and back; the watch's pins and pinned section flung)
reports the peak (`Max`) and where memory settles (`Last`). Its `trimInBackground` test browses
the same way in its setup, then goes home, sends `am send-trim-memory <package> BACKGROUND` and
waits for ART's background collection: `CardMemory` (in `rc-converter`, installed by both
`Application`s) drops every rebuildable card cache at that level, so `Last` there should sit
well below the browse's. While the UI is only hidden the document cache keeps its 32 most recent
documents; Coil trims its own memory cache. Whether 32 is enough is measured in the field: on
phone builds with the Firebase backend each document-cache trim logs a `card_cache_trim`
Analytics event (`level`, `size_before`, `kept`, `keep_limit`, and for the trim before it
`prev_level`, `prev_dropped` and `prev_rerequested`: how many of the documents it dropped were
asked for again before this trim, each a re-encode). A high `prev_rerequested` after a
`UI_HIDDEN` (20) trim means the app should keep more. The watch has no Analytics.

## Demo mode for the phone

The benchmarks launch `MainActivity` with `terrazzo.test.demo_mode=true`, which skips sign-in and
lands on the demo **Security** board, sections collapsed unless the launch also carries
`terrazzo.test.expand_sections=true`. The extras are honoured only where `BuildConfig.TEST_HOOKS`
is set: debug, and the `nonMinifiedRelease` / `benchmarkRelease` variants the baseline profile
plugin derives from `release`. Release and experimental builds ignore it.

The watch app has no demo of its own (the phone drives it over the data layer), so on an
unpaired emulator its journeys cover the top-level, Settings and dashboard-library screens only.
Seeding the watch's data layer would extend the profile to the card screens.

## Generating

For now the profiles come from emulators. `.github/workflows/baseline-profiles.yml` boots a
phone (API 36) and a Wear (API 33, `android-wear`) emulator, generates both profiles, runs the
benchmarks, prints a Perfetto breakdown of the scroll traces
(`scripts/analyze-benchmark-traces.py`: frame-timeline jank, where over-budget frames spent their
time, JIT activity) into the job log and summary, and uploads the profiles as `baseline-profiles-app` and
`baseline-profiles-wear`. It runs on PRs that touch the benchmark modules and on dispatch; commit
the artifacts' files over:

- `app/src/main/generated/baselineProfiles/` (merged into `main`, so `experimental` gets it too)
- `wear/src/release/generated/baselineProfiles/`

Locally, with an emulator running (API 33+):

```sh
./gradlew :app:generateBaselineProfile
./gradlew :wear:generateReleaseBaselineProfile
```

Or let Gradle manage the device (`pixel6Api35`, and `wearSmallRoundApi33` for Wear):

```sh
./gradlew :app:generateBaselineProfile -Pbenchmark.gmd=true
```

Normal builds never generate (`automaticGenerationDuringBuild = false`); they merge the
checked-in files.

## Benchmarking

```sh
./gradlew :app-benchmark:connectedBenchmarkReleaseAndroidTest
./gradlew :wear-benchmark:connectedBenchmarkReleaseAndroidTest
```

Macrobenchmark refuses emulators; pass
`-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR` to run
on one anyway. Emulator numbers only catch large regressions; compare timings on a physical
device. Results (JSON and Perfetto traces) land in
`*-benchmark/build/outputs/connected_android_test_additional_output/`. Past device numbers are in
[scroll-benchmark-results.md](scroll-benchmark-results.md),
[startup-results.md](startup-results.md) and [wear-results.md](wear-results.md).
