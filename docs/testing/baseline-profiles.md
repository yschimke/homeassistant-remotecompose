# Baseline profiles and macrobenchmarks

`:app` and `:wear` each ship a baseline profile (plus a startup profile for DEX layout) and
apply `androidx.profileinstaller`, so sideloaded and non-Play installs get the AOT benefit too.
The profiles are produced, and the apps measured, by two `com.android.test` modules:

| Module | Target | Journeys |
| --- | --- | --- |
| `:app-benchmark` | `:app` | Cold start into demo mode, then switch to the 3D Printing, Meshcore and GitHub boards and fling each (the default Security board fits on one screen) |
| `:wear-benchmark` | `:wear` | Cold start onto the top-level list, open Settings and fling it, open the dashboard library |

Each module has a `BaselineProfileGenerator`, a `StartupBenchmark` (`StartupTimingMetric`, cold)
and a `ScrollBenchmark` (`FrameTimingMetric`, i.e. jank), the latter two run with no compilation
and with the baseline profile so the profile's effect shows as the gap.

## Demo mode for the phone

The benchmarks launch `MainActivity` with `terrazzo.test.demo_mode=true`, which skips sign-in and
lands on the demo **Security** board. The extra is honoured only where `BuildConfig.TEST_HOOKS`
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
`*-benchmark/build/outputs/connected_android_test_additional_output/`.
