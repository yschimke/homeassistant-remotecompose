# Sliders and the control lock

Cards can carry **sliders**: a drag that shows the value as it moves and sends it once, on
release. On surfaces a passing swipe brushes (a launcher widget, the watch's card list) a card with
a slider starts **locked**: its controls are drawn muted, a touch on one shows a "Slide to unlock"
strip, and sliding the strip's knob across unlocks every control on the card.

Both live inside the card's Remote Compose document, so they behave the same in every player that
draws it — the launcher's, the app's and the watch's — with no host round trip while dragging.

## What it looks like

| State | Slider | Card |
| --- | --- | --- |
| Unlocked (or never locked) | Track filled from its start to the value in the tile's accent; the value rides the fill's edge in a bubble in the card's colour. | — |
| Locked | Fill muted grey, bubble text muted. A drag doesn't move it. | Lock badge in the top corner. |
| Locked, after a touch on any control | As locked. | The "Slide to unlock" strip across the bottom: a pill with a round knob (a chevron in it) at its start; the trail behind the knob washes in the accent. |
| Unlocking | — | The knob follows the finger. Released past halfway it eases to the end; short of that it eases back. |
| Unlocked | Fill in the accent; drags. | Badge shows an open lock in the accent; the strip fades out. |
| Dragging | Fill and bubble follow the finger, the bubble shows the value it will send. | — |
| Read-only (Wear widget) | Value shown in the usual colours; no drag. | No badge. |

The drag is **relative**: the slider moves by how far the finger moves, never to where it lands.
Touching a slider (or tapping it) changes nothing, so a brush can't jump a light to full; a
release where it rested is a tap, which no host runs.

`SliderVisualsTest` (`rc-converter`) and `WearSliderTest` (`wear`) save every state to
`build/slider-captures/` and `build/wear-sliders/`.

## Where controls lock

`HaControlLockMode`, chosen by the host per capture:

| Surface | Mode | Why |
| --- | --- | --- |
| Phone app dashboard | `Off` | The app has its own lock: the top bar's read-only chip. |
| Launcher widget | `Locked` for a card with a slider (`cardHasDragControls`), else `Off` | A slider makes the platform player claim every gesture that starts on the widget, so the card is unlocked on purpose with the same kind of gesture. A widget of taps alone keeps today's behaviour and leaves home-screen swipes to the launcher. |
| Wear app, card list | `Locked` for a card with a slider, else `Off` | Scrolling a watch list brushes the cards. A tile shows its compact form over the slider; the strip covers the slider while it is shown. |
| Wear app, full-screen card | `Off` | Opened deliberately, to be used. |
| Wear widget | `ReadOnly` | Every Wear widget tap opens the app on the card ([launcher-widget-actions.md](launcher-widget-actions.md#wear-widgets)). |

On a locked card every write control is held: a toggle or service call sends `HaAction.None`
(which no host runs) and shows the strip; once unlocked it sends its action. Taps that only show
something (more-info, navigate, open the app) and an alarm panel's keypad (its code is its own
check) are never locked. The card relocks when it is drawn again (a widget's next update, the card
scrolling back into the watch list).

The lock guards against brushes; it isn't a permission. Every host still applies its own rules:
the app's read-only mode, the phone's lock mode for high-risk actions, the trust checks below.

## How it works

### The document

- **`RemoteHaSlider`** (`rc-components`) draws the track, fill and bubble in one canvas. Its value is
  a Remote Compose `TouchExpression` (relative mode) in whole **ticks** `0..maxTicks` from the
  slider's minimum; its `onTouchUp` sends `HaAction.SetValue` with the ticks the finger left it at.
- **`RemoteHaControlLockFrame`** draws the badge and the strip after the card, as overlays. The
  strip's knob is another touch expression (stopping at its ends); `unlocked` is 1 once it reaches
  the end. A locked slider multiplies its drag by `unlocked`; a locked control's payload is
  `unlocked ? action : None` (`HaAction.toRemoteAction`), combined with showing the strip.
- **Ticks, not values**: the payload carries an integer, so no decimal separator to localise, and a
  host can check it against the range it drew. The service gets `(min + ticks × step) × scale`
  (`SetValue.toCallService`): a volume shows 0–100 % and sends `volume_level` 0–1.

Two rules of the AndroidX player shape the writer code (`TouchControls.kt`), both checked by
`SliderPlaybackTest`:

1. **The touch expression and the release are on one canvas.** The player runs a component's
   touch-up actions only for the component that took the touch-down, and a touch expression only
   moves for touches inside the component it is written in. The canvas also carries a touch-down
   action, which is what makes it the component that takes the touch.
2. **The release's payload is defined in the canvas.** The payload is in the canvas's modifier,
   written before its content; text made there from a touch value defined later is formatted once
   and never again. So the touch value's and payload's ids are reserved first and their ops are
   written inside the canvas (`reservedFloat`, `defineTouch`, `defineString`).

Two more rules of the player shape the layout:

3. **No slider inside a state layout.** A touch expression in one branch of a `RemoteStateLayout`
   (a size tier) gets the release but never moves. The tile's Wear form is therefore chosen at
   capture (`ha-cards/tile-wear-cell` in `tile.uid`): a tile with a slider draws
   `ha-cards/tile-compact-slider` (the compact tile over a 36 dp slider, which fits the Wear Large
   container's ~96 dp), anything else the height tiers.
4. **A gone component still takes the touches in its bounds.** The unlock strip is collapsed to
   0 dp as well as gone while hidden, so it never holds back the controls under it.

The Compose Multiplatform fork (desktop, web) has no touch-expression writer: sliders draw
read-only there and cards don't lock (`touchControlsSupported`).

### Players

- **Launcher widget**: the platform `RemoteComposePlayer`, the same code as the AndroidX View
  player the tests drive.
- **App and watch**: the Compose Multiplatform `RcComposePlayer` implements touch expressions only
  as a scroll's child, so its capability report sends a slider card to the AndroidX View player
  (`HaRcComposePlayer`'s fallback). That player reports named host actions only through the
  document's own action callbacks, which `WrapAdaptiveRemoteDocumentPlayer` now registers.
- **Scrolling**: by default the View player claims a gesture from its first touch whenever a
  document has a touch expression. The in-app profile (`androidXExperimentalWrap`) turns that off
  (`FEATURE_DISALLOW_INTERCEPT_TOUCH = 0`), so a dashboard or the watch's list still scrolls
  vertically over a slider card while the slider takes horizontal drags. The launcher widget keeps
  it on: there a sideways drag would page the home screen.

### Hosts

| Host | A `SetValue` release |
| --- | --- |
| App (`DashboardActionDispatcher`) | Unchanged → nothing. Else the service call, unless read-only mode drops it. |
| Launcher widget (`WidgetActionReceiver`) | The value travels in the tap's RemoteCompose metadata; the static payload on the `PendingIntent` is the slider resting where it was drawn, so a platform that drops the metadata sends nothing. `trustedWidgetAction` accepts only the app's own slider with a different position inside its range. A high-risk one (a garage door's position) runs only while the app's controls are unlocked: the confirmation activity's immutable `PendingIntent` can't receive the dragged value. A locked control's `None` is honoured even over a design template's resolved action. |
| Wear app (`WearTapDispatcher` → phone `wearTapCall`) | Unchanged → nothing sent. The phone accepts only its own capture's slider moved within range; one drawn from a value the phone has since seen change is refused, not guessed. |

## Cards with sliders today

Tile `features:` (HA `hui-*-card-feature`), in `TileContent.sliderFeature`:

| Feature | Entities | Sends |
| --- | --- | --- |
| `light-brightness` | a light with a brightness mode (not `onoff` only) | `light.turn_on` `brightness_pct` 0–100 (an off light rests at 0) |
| `cover-position` | a cover with `set_position` (feature 4) | `cover.set_cover_position` `position` 0–100 |
| `media-player-volume-slider` | a media player with `volume_set` (feature 4) | `media_player.volume_set` `volume_level` 0–1 |
| `numeric-input`, `style: slider` | `input_number`, `number` | `<domain>.set_value` `value`, in the entity's `step`s from `min` to `max` |

A slider's resting value is baked: a tile with features re-encodes on a data change
([live-card-updates.md](live-card-updates.md), path 2), so a release that lands is drawn from Home
Assistant's new state.

## Not yet

- **Verified only in Robolectric.** Drag, release, lock and unlock are driven through the AndroidX
  View player with real `MotionEvent`s; nothing has run on a launcher, phone or watch yet. On a
  device, check that a home screen still pages when a swipe starts beside (not on) a slider widget,
  and how a launcher treats a horizontal drag that starts on one.
- **Button-only cards don't lock.** Locking them with a slide would make the player claim their
  swipes too; a tap-based unlock (press and hold the badge) would suit them.
- **The unlock lasts until the card is redrawn.** A widget redraws when its entity changes, so on a
  live widget a successful release usually relocks it. Holding the unlock for a while needs the
  host to remember it per widget.
- **The Compose Multiplatform player** has no free-standing touch expression, so slider cards fall
  back to the AndroidX player in the app.
- **More sliders**: `fan-speed`, a light's colour temperature, a climate target as a slider, and the
  entities card's `input_number` row.
