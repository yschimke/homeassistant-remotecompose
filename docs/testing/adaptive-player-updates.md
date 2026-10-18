# Android adaptive player update evidence

Issue: [#328](https://github.com/yschimke/homeassistant-remotecompose/issues/328), incorporating
the distinct upstream reports [#153](https://github.com/yschimke/homeassistant-remotecompose/issues/153)
and [#402](https://github.com/yschimke/homeassistant-remotecompose/issues/402).

## Baseline and scope

Reproduced on main `6b00df1dfaf5f46483b921e87e9e1e629365c535` on 2026-10-18:
AndroidX Remote Compose **1.0.0-alpha21**, Compose BOM **2026.09.00**,
compose-ai-tools player **2.13.1**, Robolectric **4.17**, native graphics, Android API **35**,
mdpi. Java is Temurin **21.0.12.1+1**. These are the repository's dependencies, with no
dependency downgrade or patch. The tests here exercise the AndroidX fallback/launcher preview
host, rather than the default Compose Multiplatform player.

Before editing, fetched main, read the three issue reports, inspected all open PRs and available
Hive session diffs. PR #914 owns periodic setup-preview refresh; #910 owns editable inputs.
The active 1x1-widget worker (`session-1792327532080-e1e66e`) owns default/minimum sizes and
converter tiers. Its diff did not change the adaptive player. Picture preview work is also
separate. No widget defaults, converters, image loading, iOS code or refresh policy are changed.

## App-host regression and fix

The initial seven-test run against unchanged production code had one failure:
`wrapSlotReplacesBytesAndIntrinsicHeightWithoutCallerKey`. The slot grew from 40px to 70px,
but its pixel at (2,2) remained red (`ffb71c1c`) instead of the replacement's blue (`ff1565c0`).
The fixed-slot replacement already passed. This reproduces the missing `AndroidView` replacement
in the wrap branch on current dependencies.

The fix gives that branch the same internal `key(view)` lifecycle as the fixed branch.
Callers can retain a stable composition node. Replacing the prepared player now replaces the
hosted View as well. Existing measurement warmup and fixed-slot recreation remain in place.

| After replacing bytes on the same wrap slot, before fix | After fix |
| --- | --- |
| ![Old red content remains](evidence/adaptive-player/before-wrap-replacement.png) | ![Replacement blue content fills its intrinsic height](evidence/adaptive-player/after-wrap-replacement.png) |

## Embedded host matrix

`AdaptivePlayerLifecycleTest` attaches real AndroidViews through a Compose activity. Every
transition waits for idle and draws the **attached** player; assertions check dimensions and
pixels. There are no caller-provided keys. Fixed fill documents include a white bottom-end
marker, whose position must change with both axes, so a background-only frame cannot pass.
Eight methods cover six regression cases plus two upstream observation fixtures.

| Host / profile | Transitions after first draw | Expected and observed with fix |
| --- | --- | --- |
| Fixed 180x30, paint-measure off | Red 40px document → blue 70px → red 40px | Updated pixels; host stays 180x30 and clips |
| Width 180, unbounded height, paint-measure off | Same byte replacements | Updated pixels and intrinsic heights 40 → 70 → 40 |
| Fixed, paint-measure off | 180x90 → 280x140 → 100x30 → 180x90; replace bytes at every size | Full fill, correct marker position and replacement color |
| Fixed, paint-measure on | Same size and byte matrix | Full fill, correct marker position and replacement color |
| Width constrained, bounded wrap, paint-measure off | Same size and byte matrix, 70px content | Width follows constraints; heights 70 → 70 → 30 → 70; updated pixels |
| Fixed width 180, intrinsic 40px document | Natural 40 → slack 80 → tight 20 | Exact host heights; content stays intrinsic in slack slot, clips in tight slot |

![Host after growth](evidence/adaptive-player/host-grow.png)
![Host after shrinkage](evidence/adaptive-player/host-shrink.png)

## Upstream observations, kept separate

These fixtures deliberately bypass `WrapAdaptiveRemoteDocumentPlayer`. Their passing test status
means the observation was collected, **not** that the dependency satisfies the host contract.
Raw observations are checked in as [text](evidence/adaptive-player/upstream-observations.txt).

### #153: intrinsic height still does not reach the public Compose host

`upstreamWidthOnlyIntrinsicHeightEvidence` enables `shouldPlayerWrapContentSize`, uses the
wrap profile and only constrains width to 180px. The public AndroidX `RemoteDocumentPlayer`
reports **800px** height for **40px** content. The adaptive host reports 40px for the same
fixture with its warmup. Preserve the warmup and `invalidateMeasure` workaround.

![Raw public host takes the available height](evidence/adaptive-player/upstream-width-only.png)

### #402: resize is still defective, but the old hard-blank claim is not reproduced

One raw player is measured, laid out and drawn at 180x90, then 280x140, then 100x30.
At the larger size, its center remains blue but its newly exposed bottom-right is transparent
(`0`); the background stays at the first size. Both paint-measure **on and off** reproduce this.
At the smaller size the background is visible, but the bottom-end marker remains at its original
position rather than reflowing. The original alpha11 report's completely blank frame was **not**
observed in this alpha21 fixture. Do not generalize that old symptom to current dependencies.

| Paint-measure on, raw player after growth | Paint-measure off, raw player after growth |
| --- | --- |
| ![Incomplete fill with paint measure](evidence/adaptive-player/upstream-paint-grow.png) | ![Incomplete fill without paint measure](evidence/adaptive-player/upstream-wrap-grow.png) |

Preserve fixed-slot player recreation, widget profile `FEATURE_PAINT_MEASURE = 0`, and launcher
recapture on options changes. This AndroidX result does not establish how Android 16's separate
platform runtime behaves; its verification is below.

## Launcher/platform coverage and limits

`AdaptiveWidgetResizeTest` is an API 36 instrumentation fixture. It covers:

- One reused framework `AppWidgetHostView`, newly captured bytes and changing entity values at
  144x168 → 288x252 → 72x84dp. Each attached, drawn host must contain text and match a fresh host.
- A registered widget bound through `AppWidgetHost`/`AppWidgetManager`. Updating launcher options
  must invoke the provider and deliver a new document; its rendered pixels must match a fresh
  capture at the requested size. The fixture only removes its own allocated widget and store row.

The first fixture exports PNGs through MediaStore to `Pictures/TerrazzoWidgetTests`, collected by
the existing `scripts/run-connected-tests.sh` CI runner. Images never exceed 1800px.

This VM has no attached Android device or `/dev/kvm`. The instrumentation source is compiled
locally, but a compiled fixture is **not** evidence of a platform pass. The existing API 36 CI job
runs both fixtures and the real launcher installation journey. Record that run and its results
in the PR before claiming launcher verification. No manual Launcher3 resize gesture has been
verified by the local Robolectric matrix.

## Commands

```sh
./gradlew :rc-converter:testDebugUnitTest \
  --tests '*AdaptivePlayerLifecycleTest' --tests '*WidgetProfileTest' \
  --tests '*LauncherWidgetPlayerA11yTest' :app:compileDebugAndroidTestKotlin ktfmtCheck

./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=ee.schimke.terrazzo.widget.AdaptiveWidgetResizeTest
```

Native local frames are written to `rc-converter/build/adaptive-player-evidence/`. The regression
tests use small deterministic documents so the lifecycle and sizing checks do not depend on
network images, converter behavior or clocks. Existing `WrapContentHeightRepro` and
`SizingExperimentPreviews` remain available unchanged as historical visual reproductions.
