# One-handed gestures in the Wear app

Wear OS 7 (API 37) adds a system gesture framework. On a Pixel Watch 3 or later the wearer's hand
on the watch arm can drive the watch:

| System action | Pixel Watch gesture | Default |
| --- | --- | --- |
| **Primary** | Double pinch (thumb and index finger, twice) | Nothing. An app opts in per element. |
| **Dismiss** | Wrist turn | System Back. |

The Terrazzo Wear app (`:wear`) uses them for the three things a wearer does most: **scroll** the
pinned list, **open** a card full screen, and **run the card's action** (toggle a light, run a
script). High-risk actions (a lock, a garage door) follow the phone's lock mode, on the watch and on
the phone's launcher widgets.

## The API

`:wear` is on `androidx.wear.compose:compose-material3:1.7.1`, which ships the API in
`androidx.wear.compose.material3.onehandedgesture`:

```kotlin
// One per gesture target; `gestureId` ties the target to the system's per-experience hint muting.
val config = rememberOneHandedGestureConfiguration(
  action = OneHandedGestureAction.Primary,      // or .Dismiss
  priority = OneHandedGesturePriority.Clickable, // .Scrollable, .Unspecified
)

Modifier.oneHandedGesture(
  gestureConfiguration = config,
  onGestureLabel = "Turn off",                   // read out by TalkBack
  enabled = true,
  interactionSource = interactionSource,         // shared with the button: gesture shows as a press
  onGestureAvailable = { /* show the hint indicator */ },
  onGesture = { /* suspend */ },
)
```

`OneHandedGestureDefaults` scrolls a `ScalingLazyListState` (`scrollDownToNextItem`) and pages a
`PagerState` (`scrollToNextPage`). The hint indicators (`OneHandedGestureClickIndicator`,
`OneHandedGestureScrollIndicator`, `OneHandedGestureVerticalPageIndicator`) are drawn by the system
on its own schedule (Always / Daily), so the app never builds a tutorial. Below API 37, or on a watch
without the gestures, the modifier does nothing, so `minSdk = 30` needs no checks.

The [design guidance](https://developer.android.com/design/ui/wear/guides/patterns/gestures) sets
the rules the design follows:

1. **One primary-action target at a time.** A visible `Clickable` target beats a `Scrollable` one.
2. **Every gesture action has a visible, tappable equivalent.**
3. **Gesture scrolling is for glanceable lists**, not every list.
4. **Dismiss stays Back.**
5. **A gesture gets the same feedback as a tap.**

## Scrolling: pinned list and section screens

`WearListScreen(gestureScroll = true)` puts a `Scrollable` gesture target on the `ScalingLazyColumn`
and swaps the scroll indicator for `OneHandedGestureScrollIndicator`. Each double pinch brings the
next card into view (`scrollDownToNextItem`). It's on for `WearTopLevelScreen` (the pinned list) and
`WearSectionScreen`, and off for Dashboards, Settings and Widgets, which are setup screens reached
by touch.

In a list, a pinch only ever scrolls. A card in a list registers no gesture target: with one on every
card, whichever card happened to be visible would take the pinch. Running an action belongs on the
full-screen card.

## Opening a card full screen

`WearScreen.Card` shows `WearCardScreen`, a `VerticalPager` over the cards of the list it was opened
from (`fullScreenDeck`): the pinned cards in order, or the cards of one section. Message cards are
input forms and don't open. Each page draws its card through the same `WearRemoteCard` pipeline in
the Large Wear widget frame (`wearFullScreenContainer`). A card that only fills the Small container
keeps it. A details screen with a history graph, like the phone's `CardHistoryScreen`, is a follow-up:
[`wear-card-details-screen.md`](../followups/wear-card-details-screen.md).

There are four ways in:

- **Long press** anywhere on a card in the pinned list or a section.
- **A tap the card doesn't take** (`wearCardGestures`): outside its buttons, or on a card with no tap
  of its own. The player's clickables consume a tap's release (`clickable` in `RcComposePlayer`), so
  a parent watching the `Final` pass sees an unconsumed release only when nothing in the card handled
  the tap. The modifier never consumes a normal tap, so the card's own clicks run as before. A press
  that moves past the touch slop, or that a scroll consumed, counts as neither. A long press consumes
  the rest of its gesture in the `Initial` pass, so the click under the finger doesn't also run.
- **The card's own more-info tap.** It used to do nothing on the watch, which has no entity details.
  `WearTapDispatcher` now calls `onMoreInfo`, and the full-screen card stands in for details.
- **A Wear widget tap.** `EXTRA_WIDGET_CARD_KEY` now opens the widget's card full screen, paging
  through its pinned section or the pinned list. Back returns through that section.

The Dismiss gesture is left as system Back (rule 4), so a wrist turn returns to the list. `WearApp`'s
`BackHandler` routes `Card` back to its section or to the pinned list.

## Running the card's action

Each page with an action shows an `EdgeButton` under the card, labelled for the live state ("Turn
off" for a light that is on, "Unlock"). The button is the page's `Clickable` gesture target, so a
double pinch presses it. The press shows on the button through the shared `MutableInteractionSource`,
and its hint is `OneHandedGestureClickIndicator`.

- **The action comes from the card's local config, never from the document:**
  `CardDesignTemplates.primaryAction(designCard(card))`. That's the card's `tap_action`, else Home
  Assistant's default for its entity, the same action a tap on the card runs (`playback`'s
  `tapAction`).
- **Only `Toggle` and `CallService` get a button** (`wearCardPrimaryAction`). More-info, navigate and
  URL only show something. An alarm panel's arm and disarm need the code typed on the card.
- **It goes through the card's own `WearTapDispatcher`** (`rememberWearCardTaps`, shared with the
  card through `WearRemoteCard(taps = …)`). A pinch then gets the same haptics (tick on send, confirm
  or reject on the phone's answer), the same status line, and the same phone-side checks (`wearTapCall`
  only runs an action the phone's own capture of the card makes). There is no new write path.
- **The gesture target is disabled while watch controls are off** (`WearCardTaps.enabled`). The
  button stays, and a tap on it says how to turn controls on.
- **A page with no action** takes the gesture on the pager instead (`Scrollable`,
  `scrollToNextPage`), so a pinch pages past a sensor and toggles a light.

## High-risk actions and lock mode

`HighRiskActions` (in `:rc-converter`) names the actions that open the house to someone:

- any write to a `lock` or `alarm_control_panel` entity;
- any write to a `cover` whose device class is `garage`, `gate` or `door`;
- any write to a cover whose device class isn't known.

The phone's **lock mode** is its write-mode toggle: the dashboard top bar's lock chip
(`SessionWriteMode`, process-scoped) over the persisted Settings opt-in (`permanentWriteMode`).

**On the watch,** that toggle already reaches the watch as `WearSettings.inputEditingEnabled`. The
phone refuses every watch tap while it is locked (`wearTapCall`'s `readOnly`). So a high-risk card's
button and pinch only run while the phone is unlocked, and no watch-side deny list is needed.

**On a phone launcher widget,** taps used to ignore lock mode ("a pinned widget is a deliberate
control"). A high-risk toggle or service call now opens `WidgetConfirmActivity` instead of the
background receiver: an activity `PendingIntent`, because a receiver can't put a question on screen.
The activity reads lock mode when the tap lands. Unlocked, it runs the call (`WidgetActionRunner`)
and closes without drawing. Locked, it asks first ("Unlock Front door?"). Every other widget tap runs
directly as before: locked is the default on a cold start, so holding every widget tap to it would
make widgets ask every time.

- The activity isn't exported and is reached only through the widget's immutable `PendingIntent`,
  whose extras the launcher can't replace. So the action is the one the app put on the widget, the
  same guarantee `trustedWidgetAction` gives the receiver.
- Alarm panels on a widget still go through the receiver: their keypad is paired there
  (`WidgetAlarmPairing`), and the panel's own code is its check.
- Wear widgets need no change. Every Wear widget tap already opens the Wear app, now on the card full
  screen.

## Testing

- `HighRiskActionsTest` (`:rc-converter`): which actions are high risk, and `primaryAction` against a
  card's `tap_action` and defaults.
- `WearCardScreenTest`: the button's action and label, no button for a more-info card, and the deck
  per list.
- `WearTapsTest`: a more-info tap opens the card and sends nothing.
- `WidgetConfirmTest` (`:app`): which actions the confirmation runs, and its prompt.
- On an API 37 emulator or a Pixel Watch, the framework has a shell hook:

  ```shell
  adb shell cmd IWearGestureService override-constraints offbody-state
  adb shell cmd IWearGestureService gesture DoublePinch
  adb shell cmd IWearGestureService gesture WristTurn
  adb shell cmd IWearGestureService get-active-gestures -readable
  adb shell cmd IWearGestureService override-constraints reset
  ```

  A `:wear-benchmark`-style instrumented test could drive it: open the app on a seeded card
  (`EXTRA_TEST_SEED_PINS` plus `EXTRA_WIDGET_CARD_KEY`), send `DoublePinch`, and assert a
  `/wear/tap/v1` request. Whether the API 37 Wear emulator image accepts these commands hasn't been
  checked yet.
