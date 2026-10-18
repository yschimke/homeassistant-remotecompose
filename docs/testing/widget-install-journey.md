# Widget authoring and installation journey

A phone user chooses a dashboard card, opens its details, and taps **Prepare a
home screen widget** above the history charts. **Make it yours** provides a local
name edit and a live home screen preview. Compact, Default and Roomy presets plus
a corner drag let the user test the provider's advertised resize range before
pressing **Install widget**. The install action stays visible while the preview
scrolls. Android then asks for confirmation and a home screen position.

The name edit applies only to the widget. Installation persists the edited card
only after the launcher confirms. Cancellation consumes no slot and returns to the edited preparation. The Android
confirmation receives the actual rendered preview rather than a generic empty
layout. Each request has a unique callback identity, including requests for
identical cards on different Home Assistant instances.

## Review findings and changes

- The actual dashboard journey exposed a startup `NoSuchMethodError`: Android
  and shared UI generated the same `DashboardListStateKt` file facade. The
  Android helper file now has a distinct name, keeping both implementations in
  the APK. The resumed journey test protects the real entry point.

- Installation was buried beneath history: a primary preparation action now
  appears immediately after the card preview.
- Real platform inflation rejected CoreText operation 239, admitted by AndroidX's
  experimental widget profile. Widget capture now excludes the new text/style
  operations and uses the writer's legacy TextLayout fallback, preserving data
  bindings and compatibility with Android 16.
- Android 15's original DrawInstructions decoder has no layout-widget support.
  The app still runs on Android 15, but providers stay out of its widget picker
  and preparation explains that home screen widgets require Android 16.
- AndroidX alpha21 also emits wire version 1.1 for API-7 captures. Android 16
  rejects it despite recognizing the layout operations, and its player then
  crashes while mapping colors. Final platform-bound documents retain the tagged header and
  advertise compatible version 1.0 after the AndroidX baking/codec passes; tests cover both capture and baked output.
- Android 16 also drops newer expression/resource operations nested in layout
  components. The final platform document lifts those definitions before the
  layout while retaining component values and actions in place. This fixes the
  missing invisible-width text that otherwise makes adaptive widgets show the
  platform's warning icon.
- Android 16 kept the size-predicate layout on its narrow branch. Launcher captures
  now select the matching tier from the requested size; the existing options-change
  callback recaptures on every launcher resize. Tests check that wide tiles include
  name and state rather than simply enlarging the icon.
- Widget updates and size changes used synchronous data loading/capture on the
  broadcast UI thread. The provider now uses a serialized background job held by
  `goAsync`, avoiding UI stalls and out-of-order updates.
- Editing and size selection no longer leave the keyboard covering the preview
  or launcher confirmation.
- Preview capture coalesces rapid typing/resize events and runs away from the UI
  thread; cancellation cannot publish an older card over a newer edit.
- The install sheet had one fixed-size, hard-coded light preview. It now uses
  the installed widget's template/direct capture, system theme, image handling
  and RemoteViews playback. Every preview size recaptures the document exactly
  as a launcher resize does. Preview actions cannot call Home Assistant.
- Widget-only edits were unavailable. The name can now be edited before
  installation, with the original dashboard configuration preserved.
- A large preview could push installation off screen. The primary install
  action is fixed below the scrolling preparation controls.
- Each five-second snapshot refresh used to clear the native preview and replace it
  with a spinner, then recreate its host. Capture and native inflation now finish
  before an atomic child swap in the existing host. The last successful preview
  stays visible during refresh, resize and replacement failures. New edits and
  failed replacements invalidate installation; cancelled captures cannot publish.
- Opening details froze the install snapshot. The sheet refreshes data while
  open, retaining local edits and the selected preview size.
- A rejected pin request closed the sheet. It now leaves the preparation open
  with a retry message. A failed widget render disables installation.
- Installed-slot counts now observe the store rather than taking a one-time
  count, with launcher reconciliation on entry.

## Boundaries that still need implementation

**UI Builder drafts do not return to the app as installable custom designs.**
The web editor is a separate authoring flow today. Its saved or agent-generated
layout is not imported into WidgetStore, which persists Lovelace card config.
The local name edit above does round-trip into the installed widget. A complete
web-design journey requires an authenticated, versioned export/import contract,
launcher-compatible compilation, persistent local designs and per-size render
validation before offering installation of those drafts. Do not label the
original card's install action as installation of an edited web draft.

Adaptive layout is supplied by the card converters and launcher templates,
not a switch that can make an arbitrary layout responsive. Unsupported card
layouts still need converter or template work. Preview grid cells are an
approximation; each launcher chooses the actual cell dimensions and final size.
Android's confirmation dialog is launcher-controlled: the app supplies a preview,
while interactive resize testing lives in the app before confirmation and on the
home screen after installation.

## Regression coverage

`WidgetPreparationTest` runs on an Android 16 emulator with a pin-capable launcher.
It checks that actual rendered pixels are non-empty, change with light state,
and remain non-empty at Compact and Roomy sizes. Roomy must reveal more content, not just enlarge the canvas. It edits a name, requests a pin,
cancels, confirms another pin and verifies the persisted edited card. Screenshots are retained through MediaStore so CI cleanup cannot erase the evidence. The install screenshot waits for actual content inside launcher widget bounds. It also
exercises the provider's launcher size options after installation.

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=ee.schimke.terrazzo.widget.WidgetPreparationTest
```

Use a disposable emulator; the install test leaves its widget installed for visual inspection. Separately test the real dashboard entry point and
launcher resize handles; programmatic option updates alone do not prove the
launcher resize gesture works. The long-press regression now checks the visible preparation action above history,
rather than looking for the old link below the fold. It exercises the dashboard
entry point separately from the isolated render/install tests.

`WidgetPreviewRefreshTest` adds temporal coverage of the setup lifecycle with
controllably delayed native RemoteViews captures. It observes every pre-draw over
three five-second refresh intervals on the Compose test clock, then Roomy/Compact size changes and a corner drag,
checking that content never disappears and the native host keeps its identity.
It writes `Download/TerrazzoWidgetTests/widget-preview-frames.csv` (elapsed time,
host identity and displayed live value); `run-connected-tests.sh` pulls it into
the instrumented-test artifact alongside the real renderer's screenshots. This
isolates replacement lifecycle from image conversion, which has separate parity
coverage. The second test covers failed inflation, retry, stale capture cancellation
and invalidating installation while a card edit is pending.

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=ee.schimke.terrazzo.widget.WidgetPreviewRefreshTest
```
