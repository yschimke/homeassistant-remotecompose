# Wear follows mobile appearance

Robolectric native-graphics captures, API 35, 240 × 240 px. These are composable renders rather
than photographs or captures from a paired physical watch.

| Settings before | Settings after |
| --- | --- |
| ![Watch appearance pickers](settings-before.png) | ![Appearance follows mobile](settings-after.png) |

The before image runs the original `WearSettingsScreen` from the parent commit. Its source was
copied temporarily into the test source set under a different function name, rendered with the
same Wear Material theme/background, then removed after capture. The after image comes from
`WearDashboardRenderingTest.settingsOnlyExplainsMobileAppearanceWithoutWatchSelection`.

| Synced LCD design | Synced Braun design | Braun after live update and offline replay |
| --- | --- | --- |
| ![Synced LCD](synced-lcd.png) | ![Synced Braun](synced-braun.png) | ![Offline Braun](synced-braun-offline.png) |

`WearDashboardRenderingTest.syncedInstalledDesignChangesPixelsAndSurvivesOfflineRestart` writes
the phone's settings and template/index wire models into the offline store. It renders LCD,
delivers a Braun index before its template, delivers the template separately, changes the live
entity from on to off, and recreates the card using the persisted design. It asserts pixel
changes for design and value updates and identical pixels on offline replay. Its palette/global
style stay fixed, so the selected template must drive the artwork.

Other regression checks cover newer settings winning over delayed DataItems, startup reads,
reconnect-style updates, old/future field defaults, installed phone-only designs winning over
generic Wear templates, large payloads using assets, out-of-order index/blob persistence,
phone-driven widget styling and warming the synced template rather than a local fallback.

Wear remains dark-only, including when the device test configuration is `notnight`.
`WearDynamicThemeRenderingTest` exercises the real Wear dynamic color API on API 35, renders
system colors, toggles them without a phone packet, recreates from persisted mobile settings,
and applies a subsequent fixed mobile palette. Pixel assertions prove the dynamic/fallback
change appears and fixed palettes ignore the system toggle. Palette keys are stable across recreation and differ with resolved system colors, avoiding
stale local card captures. The API 35 disabled-system case exercises the API's null fallback;
older-device fallback follows the same path but was not run on a separate pre-35 SDK.

| Watch system dynamic | System colors unavailable/disabled | Fixed palette selected on mobile |
| --- | --- | --- |
| ![Dynamic](watch-system-dynamic.png) | ![Fallback](watch-system-fallback.png) | ![Fixed](watch-mobile-fixed.png) |

Run the affected suites and checks:

```sh
./gradlew :wear:testDebugUnitTest :app:testDebugUnitTest
./gradlew :wear:lintDebug :app:lintDebug :wear:ktfmtCheck :app:ktfmtCheck
```

The rendering test can save PNGs when its test JVM receives the `wearRenderEvidence` system
property. These captures used a temporary Gradle init script that set that property on `Test`
tasks to `/tmp/wear-render-evidence`.

Physical transport remains a manual follow-up: pair updated phone/watch builds, change theme,
global style and a card's installed skin on the phone; with Material 3 selected, change the
watch-face colors and toggle system dynamic colors. Confirm Wear stays dark. Disconnect, restart
both apps, change
mobile appearance while disconnected, then reconnect. Confirm the app and placed widgets retain
the last synced look offline and converge to the current phone choice after reconnect. This VM
has no paired watch and no `/dev/kvm`; the automated checks exercise payloads, persistence and
actual rendering, without claiming a physical Bluetooth/Wi-Fi reconnect test.
