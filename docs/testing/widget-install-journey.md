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

- Installation was buried beneath history: a primary preparation action now
  appears immediately after the card preview.
- The install sheet had one fixed-size, hard-coded light preview. It now uses
  the installed widget's template/direct capture, system theme, image handling
  and RemoteViews playback. Every preview size recaptures the document exactly
  as a launcher resize does. Preview actions cannot call Home Assistant.
- Widget-only edits were unavailable. The name can now be edited before
  installation, with the original dashboard configuration preserved.
- A large preview could push installation off screen. The primary install
  action is fixed below the scrolling preparation controls.
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
and remain non-empty at Compact and Roomy sizes. It edits a name, requests a pin,
cancels, confirms another pin and verifies the persisted edited card. It also
exercises the provider's launcher size options after installation.

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=ee.schimke.terrazzo.widget.WidgetPreparationTest
```

Use a disposable emulator; the install test leaves its widget installed for visual inspection. Separately test the real dashboard entry point and
launcher resize handles; programmatic option updates alone do not prove the
launcher resize gesture works. Existing long-press regression coverage is
quarantined under issue #396 and must not be counted as passing journey coverage.
