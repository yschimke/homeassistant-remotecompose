# Card authoring on rc-players' creation API

**Status: cards are written against rc-players' Compose Multiplatform creation API
(`ee.schimke.composeai.rccreation`, `rc-creation-compose`) on every target, Android included.** This
note records what the move changed, what stays on AndroidX's creation API, and what is still to be
checked on a device.

## Where things are

| What | Version | Why |
| --- | --- | --- |
| `rc-player-*`, `rc-creation-*`, `rc-widget-creation`, `remote-compose-player-js-dist` (the BOM) | 3.1.1 | One BOM pins the players and the creation API. 3.1.0 is the first release whose writer keeps a declaration where it is made and whose capture takes a `Profile`. |
| AndroidX `remote-creation-compose` | 1.0.0-alpha23 | For the libraries compiled against it: see "What stays on AndroidX". |

`rc-material3-motion`, `rc-components`, `rc-converter`, `ha-cards-catalog` and `card-designs` are one
source (`commonMain`) compiled once per target against one API. There is no `forkMain` source set and
no `capabilitiesResolution` rule choosing between two libraries of the same package any more: the
two creation APIs are in different packages and their types do not mix.

## What changed in the cards

The API differences from alpha23 are rc-players'
[migration guide](https://github.com/yschimke/rc-players/blob/main/docs/MIGRATING_CREATION.md),
section 7: `FontWeight`, `TextOverflow`, `TextAlign`, `ContentScale`, `TileMode`, `PaintingStyle` and
`StrokeCap` are `Remote*` types, `RemoteModifier.weight` takes a `RemoteFloat`, `valueChange` is
`valueChangeAction`, a colour `tween` is `lerp`, `rememberMutableRemote*` are gone, and
`createNamedRemoteFloatExpression { componentWidth() }` is
`createNamedRemoteFloat(name, remoteComponent().width, domain)`.

## Capture profiles

A capture is for a player, and says so with rc-players' `Profile`
(`ee.schimke.composeai.rccreation.common.Profile`, AndroidX's class of that name for this API).
[`CaptureProfile.kt`](../../rc-components/src/commonMain/kotlin/ee/schimke/ha/rc/CaptureProfile.kt)
names the app's:

| Profile | What it is |
| --- | --- |
| `androidX` | `RcPlatformProfiles.ANDROIDX` |
| `androidXExperimental` | `RcCaptureProfiles.androidXExperimental` |
| `androidXExperimentalWrap` | The app's own: the experimental profile with `FEATURE_PAINT_MEASURE = 0` and `FEATURE_DISALLOW_INTERCEPT_TOUCH = 0` (rc-players' of this name has only the first). |
| `widgetsProfile`, `systemThemedWidgetsProfile` | The app's own, made with `RcTargetProfile.toCaptureProfile`: document API level 7's widget and experimental operations without `CoreText`, `TextStyle` and `FlowLayout`, and without `ColorTheme` on Android 16. |
| `wearWidgets` | `RcCaptureProfiles.wearWidgets` |

The writer refuses an operation the profile does not list, writes text as a `TextLayout` where there
is no `CoreText`, and writes the header (`DOC_PROFILES` and the feature tags). So nothing is done to
a capture afterwards: the app-side header rewrite and the launcher's `adaptTo` pass are gone.

The launcher profiles are not `RcCaptureProfiles.launcherWidgets` /
`systemThemedLauncherWidgets`, which are narrower: each is cut down to what rc-player-edit's table
for that launcher lists (document API level 6 for Android 16). The cards use an operation level 6
has no entry for: a relative time cuts its month name with `TextSubtext` (182), which
`launcherWidgets`' writer refuses (`Operation 182 is not supported for this version`). The app's
profiles keep the set the AndroidX-API ones had, so a launcher document holds what it held before.
Whether Android 16's decoder reads every operation in that set is the device check below.

A capture without Android (`CommonCaptureHost`: the design server, the browser) declares the
AndroidX experimental profile over the operations `LibraryCaptureProfile` lists.

## Launcher documents on Android 16

`platformWidgetDocument` is still run over a launcher document (the header's wire version, and
resources and measured sizes where Android 16's decoder keeps them). One thing in it is new: it
lifts a text out of a canvas as well. androidx-main's creation gives every paint a `wght` font axis
when the profile has no `CoreText`, and the axis name is a text the writer declares once, in the
first canvas that draws; alpha23 wrote an empty axis list. Later canvases, and the layout another
widget size selects, read the same id, so it is declared ahead of the layout with the other text.

## What stays on AndroidX's creation API

Because a library compiled against it is used: Glance Wear's own frame for a widget
(`WearWidgetDocument`, captured empty for its header and extras), `PendingIntentAction` for a Wear
widget's taps, and the four Remote Material 3 stickers in `ha-cards-catalog`. `Rc.AndroidColors` (the
system colour resource ids a themed colour names) has no counterpart in rc-players and comes from
AndroidX's `remote-creation-core`.

## Still to be checked on a device

No test here plays Android 16's platform player or an API 37 launcher. A launcher widget captured
this way has been played in AndroidX's player under Robolectric only (`LauncherGridSweepTest`,
`LauncherDesignsTest`); it should be seen on both launchers before a release.
