# Scroll benchmark results

Measured numbers from `:app-benchmark`, so a later run has something to compare against. How the
benchmarks work and how to run them: [baseline-profiles.md](baseline-profiles.md).

## 2026-10-22: Pixel 9, API 37

`main` at `554ee2237`, plus the long-press fix below. `benchmarkRelease` variant, USB, five
iterations per test (ten for startup). Run with:

```sh
ANDROID_SERIAL=<usb serial> build-brief ./gradlew :app-benchmark:connectedBenchmarkReleaseAndroidTest
```

### Results

| Test | Measurement | Result |
| --- | --- | --- |
| `DashboardScrollJankBenchmark.dragExpandedBoard` | frames per iteration | ~2,470 |
| | `frameDurationCpuMs` p50 / p90 / p99 | 7.4 / 9.4 / 12.2 ms |
| | `frameOverrunMs` p50 / p90 / p99 | −5.3 / −3.1 / +1.4 ms |
| | `harc:cardTemplate` | 89 resolves, 46 ms total (median iteration) |
| | `harc:captureDocument` | 25 captures, 161 ms total |
| | `harc:decodeDocument` | 25 decodes, 25 ms total |
| `ScrollBenchmark.scrollBaselineProfile` | `frameOverrunMs` p50 / p90 / p99 | −6.8 / −1.8 / +15.9 ms |
| `ScrollBenchmark.scrollCompilationNone` | `frameOverrunMs` p50 / p90 / p99 | −6.0 / +2.3 / +30.2 ms |
| `StartupBenchmark` (cold, `timeToInitialDisplayMs`) | baseline profile / none, median | 238 / 273 ms |

### What they show

- **Cards entering the screen don't jank on hardware.** The drag starts from a fresh process with
  every section open, so each section comes on screen for the first time during the measurement.
  The 99th-percentile frame still finishes within about a millisecond of its deadline. All the
  card work over a whole iteration (template resolves, document captures and decodes) adds up to
  about 230 ms, spread across roughly 2,470 frames. In the traces (`analyze-benchmark-traces.py`)
  about 1% of frames are janky and the worst is 45–51 ms: no multi-frame stall.
- **The section-entry stall Robolectric showed isn't real on the phone.** A Robolectric probe
  had found a single 2.1–2.3 s main-thread frame when a whole section (one lazy item) first
  composes and measures all its cards. Robolectric runs with a cold JIT, which inflates this. On
  the Pixel 9 with the baseline profile it doesn't happen, so splitting sections into row-sized
  lazy items isn't justified by these numbers.
- **Decoding tracks capturing.** There are 25 of each per iteration against 89 template resolves,
  so a document is decoded once per capture rather than every time its card comes back into view.
- **The baseline profile roughly halves the fling tail:** p99 overrun +15.9 ms with it, +30.2 ms
  without.

### Long press fired during slow drags

Before these numbers could be trusted, the drags had to stop opening the card menu.
`longPressBeforeChild` (`app-ui`, `CombinedClickLongPress.kt`) cancelled a press only when the
finger moved past touch slop in the card's own coordinates. A drag scrolls the card along with the
finger, so in those coordinates it barely moves. Any drag held longer than the 400 ms long-press
timeout opened the menu, and the benchmark's drags take about 500 ms. Slop is now measured in
window coordinates. Checked on the Pixel 9: 600 ms drags scroll without a menu, and a 900 ms
stationary press still opens it.

### Running on a personal phone

- A phone with a Play or release build of `ee.schimke.harc` refuses the benchmark APK
  (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, a signature mismatch). Gradle still prints
  `BUILD SUCCESSFUL` with no tests run. Uninstall the app first, which deletes its data.
- A fresh install asks for the nearby-devices permission on launch, and the dialog covers the
  board. Grant it before running:
  `adb shell pm grant ee.schimke.harc android.permission.BLUETOOTH_SCAN` (likewise
  `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`, `UWB_RANGING`, `NEARBY_WIFI_DEVICES`,
  `POST_NOTIFICATIONS`).
- A phone connected over both USB and wireless debugging shows up as two devices. Set
  `ANDROID_SERIAL`, or the connected task runs on both.
