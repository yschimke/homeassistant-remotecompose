# Moving card authoring to rc-players' creation API

**Status: players are on rc-players 3.0.0; card authoring is not moved.** This note records why,
what a trial port found, and the order to do it in once the blockers are gone.

## Where things are

rc-players 3.0.0 moved its Compose Multiplatform creation API out of AndroidX's package:
`third-party-remote-creation-compose` (`androidx.compose.remote.creation.compose`) became
`rc-creation-compose` (`ee.schimke.composeai.rccreation.compose`), with `rc-creation-expression`,
`rc-creation-writer` and `rc-creation-foundation` beside it. The two libraries can now be on one
classpath; their types do not mix.

| What | Version | Why |
| --- | --- | --- |
| `rc-player-*`, `remote-compose-player-js-dist` (the BOM) | 3.0.0 | Players take a document's bytes. Nothing in them depends on a creation library. |
| The creation fork, off Android | `third-party-remote-creation-compose` 2.29.0 | The last release whose API is AndroidX alpha23's. See below. |
| Creation on Android | AndroidX `remote-creation-compose` 1.0.0-alpha23 | The app's captures need AndroidX's writer. See below. |

## Why the cards cannot move a module at a time

`rc-material3-motion`, `rc-components`, `rc-converter`, `ha-cards-catalog` and `card-designs` are
one source (`commonMain`) compiled twice: against AndroidX on Android and against the fork on the
JVM, wasmJs and iOS. That works only while both are the same API in the same package. Their
`Remote*` types are each other's parameters, and `app`, `wear`, `tv`, `previews`,
`rc-card-shutter` and `rc-components-ui` pass them on, so the whole chain has one creation API or
it does not compile.

From 3.0.0 the two differ twice over:

- **the package**, so one source cannot import both; and
- **the API**. The fork tracks androidx-main, and alpha23 is behind it: `FontWeight`,
  `TextOverflow`, `TextAlign`, `ContentScale`, `TileMode`, `PaintingStyle` and `StrokeCap` are
  `Remote*` types, `RemoteModifier.weight` takes a `RemoteFloat`, `valueChange` is
  `valueChangeAction`, a colour `tween` is `lerp`, `rememberMutableRemote*` are gone, and
  `createNamedRemoteFloatExpression { componentWidth() }` is
  `createNamedRemoteFloat(name, remoteComponent().width, domain)`.

So moving the cards means moving Android's captures to rc-players' writer in the same change.

## What a trial port found

The whole chain and its Android consumers were ported to `rc-creation-compose` 3.0.0 on every
target (the API changes above are mechanical: 456 compile errors in `rc-components`, all but about
ten by rule). `:rc-converter:testAndroidHostTest` then ran **671 passed, 19 failed**, and the
failures are not test expectations:

1. **Drag controls stop working in the AndroidX View player.** A slider's value is a touch
   expression in its canvas, and the text made from it has to be defined after it, in the same
   canvas ([slider-controls.md](slider-controls.md)). rc-players' writer writes every declaration
   (float expressions, text from a float, `TextMerge`) ahead of the layout, wherever it was made;
   AndroidX's writes it in place. The release then sends the value the card started with
   (`SliderPlaybackTest`, `SliderComposePlaybackTest`, `SliderVisualsTest`,
   `ExpressiveBlocksPlaybackTest`: 8 tests).
2. **A launcher widget's canvas expressions are no longer in their canvas** (`LauncherDesignsTest`),
   which `platformWidgetDocument` keeps local because Android 16's platform player evaluates a
   global one before its canvas is measured. No test here plays that player.
3. **Fewer animated float expressions are written** (`MotionSchemeSwitchTest`, 6 tests), a
   launcher's colours no longer fold to constants (`CommunityFamilyLiveRenderTest`), and an inline
   named bitmap draws the wrong colour (`NamedBitmapOverrideTest`). Not traced to a cause.

Two more things AndroidX's `Profile` did have to be done by hand around rc-players' capture, and
were: the header's feature tags (`FEATURE_PAINT_MEASURE`, `FEATURE_DISALLOW_INTERCEPT_TOUCH`, the
profile bits), which its writer does not write, and a launcher's text fallback, which is
`rc-player-edit`'s `adaptTo`.

## What has to be true first

- rc-players' writer can keep a declaration where it is made (or its capture can write through
  AndroidX's `RemoteComposeWriter` on Android), so (1) and (2) hold.
- The differences in (3) are explained.
- A launcher widget captured that way is seen on Android 16's and an API 37 launcher.

## The order then

1. **The chain, in one change**: rewrite the packages
   ([rc-players' guide](https://github.com/yschimke/rc-players/blob/main/docs/MIGRATING_CREATION.md)),
   apply the API changes above, drop `forkMain`'s creation actuals and the
   `capabilitiesResolution` rules from the five build files, and replace AndroidX's `Profile` with
   a capture profile of the app's own (operations, header tags, a launcher's vocabulary).
2. **`rc-card-shutter`**: its two `RemoteIcon`s (Wear `remote-material3`) become
   `rc-creation-foundation`'s `RemoteBasicIcon`, and the module needs neither AndroidX library.
3. **`wear`**: the cards can no longer be composed inside Glance Wear's `WearWidgetDocument`.
   `WearWidgetTemplate` already captures a card itself and makes it a widget's document
   (`instantiate`); the direct document goes the same way. Its colours are written as the named
   `WearM3.<role>` states by hand, where `RemoteColorScheme` did it.
4. **`app`, `tv`, `previews`, `rc-components-ui`**: imports and the capture call only.

What stays on AndroidX's creation API after that, because a library compiled against it is used:
Glance Wear's own frame for a widget (`WearWidgetDocument`, captured empty for its header and
extras), `PendingIntentAction` for a Wear widget's taps, and the four Remote Material 3 stickers in
`ha-cards-catalog`. `Rc.AndroidColors` (the system colour resource ids a themed colour names) has
no counterpart in rc-players and comes from AndroidX's `remote-creation-core`.
