# One-handed gestures in the Wear app

Status: proposal. Nothing here is implemented yet.

Wear OS 7 (API 37) adds a system gesture framework. On a Pixel Watch 3 or later the wearer's hand
on the watch arm can drive the watch:

| System action | Pixel Watch gesture | Default |
| --- | --- | --- |
| **Primary** | Double pinch (thumb and index finger, twice) | Nothing. An app opts in per element. |
| **Dismiss** | Wrist turn | System Back. |

This doc covers how the Terrazzo Wear app (`:wear`) can use them for the three things a wearer does
most: **scroll** the pinned list, **expand** a card (or the Wear widget that shows it) to full
screen, and **run the card's action** (toggle a light, run a script).

## The API we already have

`:wear` is on `androidx.wear.compose:compose-material3:1.7.1`, which ships the API in
`androidx.wear.compose.material3.onehandedgesture`. No new dependency is needed. Checked against
the 1.7.1 jar:

```kotlin
// One per gesture target; `gestureId` ties the target to the system's per-experience hint muting.
val config = rememberOneHandedGestureConfiguration(
  action = OneHandedGestureAction.Primary,      // or .Dismiss
  priority = OneHandedGesturePriority.Clickable, // .Scrollable, .Unspecified
)

Modifier.oneHandedGesture(
  gestureConfiguration = config,
  onGestureLabel = "Turn off Kitchen",           // read out by TalkBack
  enabled = true,
  interactionSource = interactionSource,         // shared with the button: gesture shows as a press
  onGestureAvailable = { /* show the hint indicator */ },
  onGesture = { /* suspend */ },
)
```

Also in the package:

- **`OneHandedGestureDefaults`** has `scrollDown(…)` and `scrollDownToNextItem(…)` for both
  `ScalingLazyListState` (what `WearListScreen` uses) and `TransformingLazyColumnState`, plus
  `scrollToNextPage(PagerState)`. So there's no need to move to `TransformingLazyColumn`.
- **Hints:** `OneHandedGestureClickIndicator` wraps a button's content,
  `OneHandedGestureScrollIndicator` goes in `ScreenScaffold`'s `scrollIndicator` slot (it has a
  `ScalingLazyListState` overload), and there are horizontal and vertical page indicators. The system
  decides how often hints show (Always / Daily), so the app never builds its own tutorial.
- **`LocalOneHandedGestureEnabled`** is a composition local. It can turn gestures off for a subtree,
  for example in demo mode or in previews.
- Below API 37, or on a watch without the gestures, the modifier does nothing. `:wear`'s `minSdk = 30`
  needs no gating.

### Rules from the platform guidance

The [design guidance](https://developer.android.com/design/ui/wear/guides/patterns/gestures) sets
constraints that decide most of the design:

1. **One primary-action target at a time.** A visible `Clickable` target beats a `Scrollable` one. A
   scrollable container first scrolls until the clickable child is on screen, then the next pinch
   clicks it.
2. **Every gesture action needs a visible, tappable equivalent.** A gesture-only action is not
   allowed.
3. **Gesture scrolling is not for every list.** It suits screens the wearer reaches hands-free (a
   widget, a notification, a screen that opens on its own) and glanceable content.
4. **Dismiss stays Back.** Override it only to dismiss, silence or minimise, never for an app action.
   An empty subscription disables wrist turn on that screen.
5. **The gesture gets the same feedback as touch:** press ripple, haptic and sound.

## Proposed design

### 1. Scrolling: pinned list and section screens

`WearListScreen` gets an opt-in `gestureScroll` flag:

```kotlin
val config = rememberOneHandedGestureConfiguration(
  action = OneHandedGestureAction.Primary,
  priority = OneHandedGesturePriority.Scrollable,
)
val hint = remember(config) { OneHandedGestureScrollIndicatorState() }
ScreenScaffold(
  scrollState = state,
  scrollIndicator = {
    OneHandedGestureScrollIndicator(config, hint, state, Modifier.align(Alignment.CenterEnd))
  },
) { padding ->
  ScalingLazyColumn(
    state = state,
    modifier = modifier.oneHandedGesture(
      gestureConfiguration = config,
      onGestureLabel = "Next card",
      onGestureAvailable = { scope.launch { hint.showIndicator() } },
      onGesture = { OneHandedGestureDefaults.scrollDownToNextItem(state) },
    ),
    …
  )
}
```

Turn it on for `WearTopLevelScreen` (the pinned list) and `WearSectionScreen`. Leave it off for
Dashboards, Settings and Widgets. Those are setup screens, reached by touch.

`scrollDownToNextItem` beats `scrollDown` here: every list item is one card, so each pinch lands one
whole card in view. At the end of the list a pinch does nothing, and a wrist turn leaves, so there is
no dead end.

**No per-card clickable targets in the list.** Rule 1 allows one target per screen. With a toggle
target on every card, whichever card is visible would capture the pinch, and the wearer couldn't
predict which one. In the list, a pinch only ever scrolls. Running an action belongs on the
full-screen card (3).

### 2. Expanding a card to full screen

Add a `WearScreen.Card` destination: **`WearCardScreen`**, a `VerticalPager` over the cards of the
list it was opened from (the pinned cards, or the cards in a section). The opened card is the initial
page, drawn as large as the round screen allows. It is the same `WearRemoteCard` pipeline in a larger
`WearWidgetFrame` (the Large container, or a new full-screen frame), so it still plays live bindings.

Two ways in:

- **From a Wear widget.** Tapping a widget already opens the app with `EXTRA_WIDGET_CARD_KEY`
  (`WearWidgetTaps`). Today that lands on the card's section or on the pinned list. It should land on
  `WearCardScreen` at that card instead. This is the hands-free-style entry the guidance wants: the
  wearer looked at the card on the watch face, tapped once, and can now pinch.
- **From the list.** Touch needs an expand affordance that doesn't fight the card's own taps. The
  `CachedCardPreview` player consumes taps on the card body for its actions. See the open question
  below.

On the full-screen card, the Dismiss gesture is left as Back (rule 4), so a wrist turn returns to the
list. `BackHandler` in `WearApp` gets the new screen in its `when`. Nothing gesture-specific is needed.

### 3. Running the card's action on the full-screen card

`WearCardScreen` shows an **`EdgeButton`** for the card's primary action under the card, for
example "Turn off". That button is the gesture target:

```kotlin
val action = cardPrimaryAction(card, snapshot, registry)   // null → no button, no target
val config = rememberOneHandedGestureConfiguration(
  action = OneHandedGestureAction.Primary,
  priority = OneHandedGesturePriority.Clickable,
)
val interactions = remember { MutableInteractionSource() }
val hint = remember { OneHandedGestureClickIndicatorState() }
val run = { taps.dispatcher.dispatch(action) }
EdgeButton(
  onClick = run,
  interactionSource = interactions,
  modifier = Modifier.oneHandedGesture(
    gestureConfiguration = config,
    onGestureLabel = label,                     // "Turn off Kitchen"
    enabled = taps.enabled,                     // LocalWearTapSender != null
    interactionSource = interactions,
    onGestureAvailable = { scope.launch { hint.showIndicator() } },
    onGesture = { run() },
  ),
) {
  OneHandedGestureClickIndicator(config, hint) { Text(label) }
}
```

- **The action comes from the card's local config, never from the document.** For a template card
  that's `CardTemplatePlayback.tapAction`, which already exists and is already resolved from the
  card's own config. For a direct-document card, it's `defaultTapActionFor(card, entityId)`.
  `defaultTapActionFor` is `internal` in `:rc-converter`, so the plan adds one public
  `CardRegistry.primaryAction(card, snapshot): HaAction` beside `cardDataSignature` and uses it for
  both kinds of card.
- **It goes through the existing `WearTapDispatcher`** (`rememberWearCardTaps`). The pinch then gets
  the same haptics (tick on send, confirm or reject on the phone's answer), the same "Sending…" and
  error line, and the same phone-side checks (`wearTapCall` checks the action against the phone's own
  capture of the card). Rule 5 is met by reuse, and there is no new write path.
- **Only `Toggle` and `CallService` get a button.** `MoreInfo`, `Navigate` and `Url` do nothing on
  the watch. Alarm panels need a typed code, so they get no button, and their page's pinch falls back
  to the next page (below).
- **Domains where an accidental pinch costs too much stay touch-only:** `lock`,
  `alarm_control_panel`, and `cover` with device class `garage`, `gate` or `door`. These get no
  gesture target, or at most a target that opens a confirmation whose own button takes the next
  pinch. A double pinch can fire by accident (washing hands, gripping a bar), and unlocking the front
  door shouldn't take only that. This deny list is the main product decision in this doc.
- The label follows live state ("Turn on" / "Turn off"), so TalkBack and the hint describe what the
  pinch will do now.

**With no actionable button,** the pager registers a `Scrollable` target that moves to the next card
(`OneHandedGestureDefaults.scrollToNextPage`, with `OneHandedGestureVerticalPageIndicator`). Because
`Clickable` outranks `Scrollable`, one registration on the pager and one on the button produce the
right behaviour: a pinch toggles a light's page and pages past a sensor's page. Paging past an
actionable card is a swipe or the crown, which is touch-free enough.

### Demo mode, previews and screenshots

- When `wearTapsEnabled(settings)` is false (demo mode, input editing off), the button stays but the
  gesture target is disabled. The tap still shows the dispatcher's existing "turn on taps" reason.
- `CompositionLocalProvider(LocalOneHandedGestureEnabled provides false)` in previews keeps
  screenshot renders free of hint animations. Add one preview per indicator with the hint shown, for
  the catalog.

## Testing

- **Unit (Robolectric):** test `cardPrimaryAction` / `CardRegistry.primaryAction` per card type:
  the deny list, the alarm exclusion, and template versus direct cards giving the same action.
- **Compose UI test:** test that a `WearCardScreen` page with a toggle card has exactly one
  `Clickable` target, labelled for the current state, and a sensor page none. Add a check that the
  pinned list registers no clickable targets.
- **Emulator / device (API 37):** the framework has a shell hook:

  ```shell
  adb shell cmd IWearGestureService override-constraints offbody-state
  adb shell cmd IWearGestureService gesture DoublePinch
  adb shell cmd IWearGestureService gesture WristTurn
  adb shell cmd IWearGestureService get-active-gestures -readable
  adb shell cmd IWearGestureService override-constraints reset
  ```

  A `:wear-benchmark`-style instrumented test can drive this, as the keyboard-scrolling emulator
  matrix does for insets. The test opens the app on a seeded card (`EXTRA_TEST_SEED_PINS` plus
  `EXTRA_WIDGET_CARD_KEY`), sends `DoublePinch`, and asserts that a `/wear/tap/v1` request went out.
  Whether the API 37 Wear emulator image accepts these commands still needs checking.

## Phasing

1. **Scroll** (small): `WearListScreen(gestureScroll = true)` on the pinned list and section screens,
   plus the scroll indicator.
2. **Full-screen card**: `WearScreen.Card`, `WearCardScreen` pager, widget taps landing on it, and
   the list's expand affordance. Useful without gestures.
3. **Primary action**: `CardRegistry.primaryAction`, the `EdgeButton` target with the deny list,
   pager next-page fallback, and tests.

## Open questions

- **The expand affordance in the list.** The card body's taps belong to the card. Options:
  (a) a small expand icon button under each card, next to the status line, which is discoverable but
  adds a row per card; (b) long-press on the card, if `CachedCardPreview` lets a long press through,
  which is clean but hidden; (c) tapping the card's title or header strip, which needs the converters
  to leave that region free of actions. The recommendation is (a), starting from the widget entry,
  which needs none of these.
- **The deny list.** Should a high-risk domain be touch-only, or pinch to a confirm screen?
  Should the wearer be able to opt a card in from the phone?
- **Full-screen frame.** Reuse the Large container (it already has templates and parity tests), or
  add a full-round frame. The latter needs a new `wearDesignTemplate` size.
