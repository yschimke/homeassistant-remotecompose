# Android IME scrolling regression

The Android edge-to-edge host must constrain scroll containers to the area above the keyboard.
The connection screen must scroll even when discovered instances leave little room for its URL
field and actions. IME and navigation-bar padding must be consumed once, and the original viewport
must return when the keyboard closes.

`AndroidImeScrollingTest` uses real text-input focus and the system keyboard, not a synthetic
keyboard-height spacer. The discovery fixture contains two deterministic instances; it does not
need LAN discovery, authentication, or network permission. Card studio exercises an existing shared
form through the Android host without contacting a design server or provider.

Run on a disposable emulator (the wrapper changes its size, density and navigation overlay):

```sh
bash scripts/run-ime-tests.sh 640x1136 gestural
bash scripts/run-ime-tests.sh 720x1280 gestural
bash scripts/run-ime-tests.sh 720x1280 threebutton
```

320dpi corresponds to 320×568 dp and 360×640 dp. The checked-in
[workflow](../../../../.github/workflows/android-ime.yml) uses API 35 for the smaller gesture
phone, and API 36 for both navigation modes on the 360dp phone. Screenshots include the real IME
and system navigation controls; every saved image is at most 1800px on either side.

## Reproduction

[Baseline run](https://github.com/yschimke/homeassistant-remotecompose/actions/runs/39118547843),
commit `82b15f35`, preserves the original layout while extracting a stateless discovery form and
adding test/evidence plumbing. All three tests failed in every configuration:

| Device | Discovery | Card studio | Activity resize policy |
| --- | --- | --- | --- |
| API 35, 320×568 dp, gesture | Focused URL field hidden | Field bottom 1088px, IME starts 595px | Unspecified |
| API 36, 360×640 dp, gesture | Focused URL field hidden | Field bottom 1232px, IME starts 706px | Unspecified |
| API 36, 360×640 dp, three-button | Focused URL field hidden | Field bottom 1184px, IME starts 658px | Unspecified |

The non-scrollable discovery column ran out of height as its local IME padding grew, compressing
or clipping the remaining controls. Its scaffold padding also overlapped the local IME padding.
Card studio already scrolled, but its viewport still extended under the keyboard. That separates
missing scrollability from the Android host inset problem.

## Fixed behavior

`MainActivity` explicitly requests `adjustResize`. `AndroidImeHost` applies and consumes IME padding
before the app's scaffolds, so they receive only the remaining system-bar insets. Discovery uses a
scroll container and consumes its scaffold padding. Existing Compose focus/bring-into-view behavior
then works with the resized viewport; no custom focus jump or nested-scroll interception is needed.

The tests check that the full focused control lies above the actual IME, scroll to and click the
connection actions, reach the card-studio bottom action without dismissing the keyboard, preserve
URL edits, close/reopen the IME, and check the final form's bottom gap for doubled/stale insets.

[Fixed run](https://github.com/yschimke/homeassistant-remotecompose/actions/runs/39119049784),
commit `be1d25a6`: **9 tests passed, 0 failed, 0 skipped** across the same three configurations.
The screenshots below were downloaded from that run and inspected. The bottom-action frames also
show the entered URL/message while the real keyboard remains open.

| Device | Before: discovery | After: URL and actions | Before: card studio | After: entered text and action | After: keyboard closed |
| --- | --- | --- | --- | --- | --- |
| API 35, 320×568 dp, gesture | [PNG](before-35-gestural-discovery-open-ime.png) | [PNG](after-35-gestural-discovery-bottom-ime.png) | [PNG](before-35-gestural-card-studio-focused-ime.png) | [PNG](after-35-gestural-card-studio-bottom-ime.png) | [PNG](after-35-gestural-discovery-bottom-closed.png) |
| API 36, 360×640 dp, gesture | [PNG](before-36-gestural-discovery-open-ime.png) | [PNG](after-36-gestural-discovery-bottom-ime.png) | [PNG](before-36-gestural-card-studio-focused-ime.png) | [PNG](after-36-gestural-card-studio-bottom-ime.png) | [PNG](after-36-gestural-discovery-bottom-closed.png) |
| API 36, 360×640 dp, three-button | [PNG](before-36-threebutton-discovery-open-ime.png) | [PNG](after-36-threebutton-discovery-bottom-ime.png) | [PNG](before-36-threebutton-card-studio-focused-ime.png) | [PNG](after-36-threebutton-card-studio-bottom-ime.png) | [PNG](after-36-threebutton-discovery-bottom-closed.png) |

Local validation also passed: repository-wide `ktfmtCheck`, `:app:assembleDebug`,
`:app:compileDebugAndroidTestKotlin`, `:app:lintDebug`, and `:app:testDebugUnitTest`
(143 tests, 0 failures, 0 skips). This session VM has no emulator/KVM; actual IME checks and
screenshots ran on the workflow's Android emulators.

## Manual check

1. On a small Android phone, open the connection form with at least two discovered instances.
2. Focus the Base URL field and edit the URL. Keep the keyboard open.
3. Scroll to Connect and Try demo mode; verify both can be tapped above the keyboard.
4. Close the keyboard, scroll to the bottom, then reopen it. Check that the URL is preserved and
   there is no extra navigation-bar-sized gap below the form.
5. Repeat in gesture and three-button navigation, and with the last card-studio field/action.

Editable Remote Compose component/protocol work is separate. These tests use existing native
Compose forms and verify the Android host viewport shared by dashboard/player content.
