# Android theme, scaffold insets, and compact editor evidence

The [baseline run](https://github.com/yschimke/homeassistant-remotecompose/actions/runs/39148066782)
restored production sources from `71d69df516e9ed74f2bccdd2188b3dc107bf34b2`.
Across 20 tests it reproduced nine expected failures: theme contrast and unconsumed scaffold
insets in all four configurations, and a clipped landscape Card Studio field.

The [fixed run](https://github.com/yschimke/homeassistant-remotecompose/actions/runs/39149155506)
tested `a9e7d65e92a1fde1d767986f12d4cd501b55558b`: **20 passed, 0 failures, 0 errors,
0 skipped**. Downloaded JUnit reports contain five executed tests per device:

| Configuration | Passed |
| --- | --- |
| API 35, 320×568dp, gestures | 5 |
| API 36, 360×640dp, gestures | 5 |
| API 36, 360×640dp, three-button navigation | 5 |
| API 36, 640×360dp, gestures, side cutout, dark device theme | 5 |

The runner checks actual landscape rotation and side-cutout coverage. Tests assert the full
field bounds above the real keyboard, a three-line draft retained after dismissal, reachable
bottom actions and compact Back navigation, consumed descendant insets, and live app theme changes.

The screenshots below were downloaded from those runs and visually inspected. They are all
at most 1800px in either dimension. Theme frames exercise the production Activity; editor
frames use a native Compose form fixture, so their system-bar appearance is not theme evidence.
The compact editor shows two rows and scrolls internally through the three-line draft.

| Scenario | Before | After |
| --- | --- | --- |
| Light device, forced dark app, three-button navigation | [PNG](before-device-light-app-dark.png) | [PNG](after-device-light-app-dark.png) |
| Dark device, forced light app, landscape | [PNG](before-device-dark-app-light-landscape.png) | [PNG](after-device-dark-app-light-landscape.png) |
| Landscape editor with real keyboard | [PNG](before-landscape-studio-ime.png) | [PNG](after-landscape-studio-ime.png) |
| Landscape bottom action with real keyboard | — | [PNG](after-landscape-studio-bottom-ime.png) |

No new Message + Send integration, freeform windows, editable modal sheets, or WebView text
entry was verified. Earlier portrait IME evidence remains in [android-ime](../android-ime/README.md).
