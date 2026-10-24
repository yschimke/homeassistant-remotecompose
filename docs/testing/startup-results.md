# Startup results

What a cold start shows, and how long it takes to show the dashboard, measured on hardware. How
the benchmarks run: [baseline-profiles.md](baseline-profiles.md).

## The aim

The first frame the app draws is the dashboard with its cards and current values: no sign-in
screen on the way, no spinner, no swap of one design for another. Until that frame is ready the
system splash stays up, in the app's own dark or light background. A placeholder is fine only for
something that arrives within a frame or two (a camera picture decoding).

## 2026-10-23: Pixel 9, API 37, demo mode

`StartupBenchmark` (cold, ten iterations), `benchmarkRelease`. `timeToFullDisplayMs` is reported
where the dashboard's cards first compose (`ReportDrawn` in `DashboardList`). The baseline is
`main` at `554ee2237` with only that `ReportDrawn` added.

| | Before | After |
| --- | --- | --- |
| Cards on screen (`timeToFullDisplayMs`), baseline profile, median | 413 ms | 395 ms |
| Cards on screen, no compilation, median | 2,223 ms | 707 ms |
| First frame (`timeToInitialDisplayMs`), baseline profile, median | 216 ms | 395 ms |

Run to run, these medians move by about 20 ms on this phone.

The first frame is later because it is now the dashboard. Before, it was a frame of something else
(see below), and the cards came 200 ms (and, uncompiled, two seconds) after it. Now the two
numbers are the same: the first frame drawn is the content.

### What a cold start showed before

From a screen recording of a cold demo launch (the frames where the screen changed):

| From launch | On screen |
| --- | --- |
| 0 ms | White splash, in a dark-mode app (the launch theme was `Theme.Material.Light`) |
| ~240 ms | The sign-in screen: demo mode's session was only created after the first composition |
| ~470 ms | "Dashboard" with a spinner: no dashboard list or dashboard yet |
| ~590 ms | The board, camera pictures grey for a frame |

When the design cache was populated, the dashboard also held its cards behind a spinner for up to
1.5 s (`DESIGN_PRELOAD_WAIT_MS`) and then swapped from the local library's designs to the
server's: the cached designs took 2 s to read.

### What changed

- **Night launch theme** (`values-night/themes.xml`): the splash and window background are the
  dark dashboard's `#0F1317`, so the splash hands over to the first frame unseen.
- **Demo session before the first composition** (`MainActivity`): seeded like the cache-only
  session a signed-in user gets, instead of null until the async resume.
- **`HaSession.dashboardsNow` / `dashboardNow`**: a session that needs no round trip (demo mode)
  gives its dashboards synchronously, so the list and the board are ready on the first
  composition, as the offline cache already made them for a signed-in user.
- **The cached design read** (`CardDesignPreload`, `harc:loadCardDesigns` in a trace):
  - It ran on the main dispatcher, and every blob read hopped to IO and back through the main
    looper: about 2,000 ms on a cold start. On IO throughout: about 200 ms.
  - The manifest it decoded, twice, is 5.3 MB of JSON (10,320 entries). One read
    (`DesignRepository.resolveAllWithDevices`): about 180 ms.
  - A startup snapshot (`CardDesignSnapshot`): what the choice resolved to, in one protobuf file
    beside the cache, valid while the manifest file is unchanged. A cold start reads that: about
    70 ms, done before the activity's first frame is ready.
- **The splash waits for the designs** (`MainActivity.holdFirstDrawForCardDesigns`), capped at
  `DESIGN_PRELOAD_WAIT_MS`: the first frame drawn has the cards in their final design.

### Measuring it yourself

`adb install` applies no baseline profile, so a sideloaded build runs interpreted until
background dexopt. Compile as Play would before timing a launch:

```sh
adb shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE \
  ee.schimke.harc/androidx.profileinstaller.ProfileInstallReceiver
adb shell cmd package compile -f -m speed-profile ee.schimke.harc
```

`screenrecord` only writes a frame when the screen changes, so the timestamps of its frames
(`ffprobe -show_entries frame=pts_time`) are a timeline of what a launch showed.

### Signed in

Checked against a local Home Assistant (2026.10, the `integration/` config plus a camera card),
the Pixel 9 signed in through the app's own OAuth flow, recorded from launch for 12 s:

- The first frame is the cached dashboard and the swap to the live
  session changes nothing on screen.
- **It opened on "Can't reach Home Assistant".** The cache-only session reported `Failed` before
  anything had tried to connect, so a red banner showed until the live session replaced it about
  80 ms later, and the board jumped up as it went. The cache-only session is now connecting until
  the resume gives up (no stored token, or the refresh fails).
- **The connecting line moved the board.** It took its own height under the app bar, so every
  launch's connect shifted the dashboard down and back. It now overlays the bar's bottom edge, and
  only once connecting has lasted 500 ms.
- **Every Home Assistant notification popped again on every launch.** "Already there" was the
  session's first, empty emission. The announced ids are now kept per instance across launches
  (`SeenNotifications`); a new notification still pops, once.

`am start -W` `TotalTime` for a signed-in cold start: about 255 ms, compiled with the shipped
profile. (Wildcard profile rules for the signed-in packages made no measurable difference, so they
aren't added.)

### Not covered
- **The first frame's own cost**, about 170–190 ms in the benchmark: composing the board, and
  capturing and decoding the visible cards' documents on the main thread (about 33 ms of it). The
  template warm-up starts after the first composition, too late for those cards.
