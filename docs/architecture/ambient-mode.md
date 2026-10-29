# Ambient mode

A watch in always-on, or a display handed to a low-power co-processor, keeps showing a card while it
draws almost nothing: about one frame a minute, sometimes only a snapshot taken when it went
ambient. This page is the contract between such a host and a card document: what each tells the
other, and the order the host does things in so that animations finish before the switch, and every
frame drawn while ambient is the truth.

Two things share the word "ambient" and aren't the same:

| | `RcMotionMode.Ambient` | The ambient flag (`host.ambient`) |
|---|---|---|
| What | calm motion: slow, critically damped springs | a low-power display state |
| Decided | when the card is captured (`LocalRcMotionMode`) | while it plays, by the host |
| For | a kiosk, a TV row: always on, always drawing | a watch's always-on, an offloaded display |
| Changes the document | yes, a different capture | no, one document plays both ways |

This page is about the flag.

## Host to document: one flag

The host writes one named float, `host.ambient` (`RcAmbientValue` in `rc-material3-motion`): `1`
while ambient, `0` otherwise. It is an ordinary named binding (design principle 10): pushed like a
card's live values, seeded before the first frame like them, and never a re-encode.

- **A document that declares the name handles ambient itself.** That declaration is the document's
  whole message to the host: a host checks the document's named variables (as `HaRcComposePlayer`
  already does for every value) and finds `host.ambient` there or not.
- **A host that doesn't know its display state writes nothing.** The document keeps the value it
  was captured with (`0`, or `1` under `LocalRcAmbientInitial`, for a capture made while ambient or
  a preview of the look). `HaRcComposePlayer`'s `ambient` parameter is `null` by default for this
  reason.
- **A document that doesn't declare it** can't promise anything below. The host dims it, draws a
  placeholder, or settles it before every frame (below, "Ticking").

The motion layer reads the flag once per document however many parts use it: named values are
cached by name when the document is built.

## What the document promises while the flag is set

An ambient host may draw a frame and keep it for a minute, or keep only one frame. So every frame
must be complete on its own:

1. **Values show their targets.** `animate()` mixes its spring with its target by the flag:
   `animated + (target − animated) × flag`. A value that changes between ambient frames lands in the
   next frame, where it would otherwise show the first step of a spring nobody will see the rest
   of. The spring still runs underneath, so leaving ambient picks up wherever it is, with no jump.
2. **Going ambient takes one frame.** The look's fraction (`rcAmbient().fraction`) is the larger of
   the flag and its fade, so it jumps to `1` and fades back to `0`: the first frame after the flag
   is already the ambient look, and leaving ambient fades the interactive look back in.
3. **Loops come to rest.** A wave's amplitude is multiplied by `1 − fraction`, so the wavy
   indicators are flat lines. (Their phase still reads the clock; an ambient host doesn't advance
   it, see below.)
4. **The ambient look: fewer lit pixels.** The building blocks in `rc-material3-motion` draw
   outlines where they filled (the shape morph's container, the switch's track, the loading
   indicator), thin their tracks to about a third (the slider, both wavy indicators), and drop what
   only decorates (the inactive track, the stop dot). The switch's thumb is lit in its outline's
   colour so its side still reads on black. Preview: `ExpressiveAmbient`.
5. **No reason to wake faster than a minute.** A clock on the page reads minutes, not seconds, while
   ambient. Nothing in the motion layer asks for a sooner wake.

`ExpressiveBlocksPlaybackTest` plays each of these in the Compose player: a change lands in the
first frame while ambient and springs while not, the first frame after the flag is final, a running
wave is still, and leaving ambient restores the interactive frame exactly.

## Document to host: does anything need to come back?

**Not for animations that end.** Both players already know whether anything is in flight, so the
document doesn't have to say:

- The AndroidX player: `CoreDocument.needsRepaint()` after a paint is `1` while a spring or tween is
  running (each animated `FloatExpression` asks for the next frame until it settles), a touch or
  ripple is in progress, or bounds are animating; a number of milliseconds when only a clock tick
  is due (a document reading seconds asks for the next second, one reading minutes for the next
  minute, a `WakeIn` for its own time); and `-1` when nothing will change on its own.
- rc-players: `RcPlayerState` tracks `hasActiveFloatAnimations` (springs and tweens in flight)
  apart from `readsContinuousClock` (the document reads the animation clock), and emits
  `NextFrame` and `WakeIn` effects.

**The catch is loops.** A loop on the animation clock never settles, so "wait until nothing is
animating" would wait forever, and the AndroidX value can't tell a loop on the continuous clock from
a spring (both are "next frame"). Two things close it, neither a new signal:

- the document stops its loops under the flag (promise 3), and
- the host judges "settled" on animations that end only (rc-players can, from
  `hasActiveFloatAnimations`), with a deadline as the backstop for a player that can't tell.

**One gap is the player's, not the document's.** `RcComposePlayer` keeps its state to itself: a
host can't yet ask it whether a spring is in flight. rc-players needs a small API for it, for
example a callback with the player's demand after each frame:

```kotlin
sealed interface RcFrameDemand {
  data object NextFrame : RcFrameDemand        // a spring, tween, touch or ripple is in flight
  data class At(val epochMillis: Long) : RcFrameDemand  // only a clock tick or WakeIn is due
  data object Idle : RcFrameDemand             // nothing changes until the host writes a value
}
```

with loops (continuous-clock reads) left out of `NextFrame`, since a host ignores them in ambient
anyway. Until it exists, a host waits a fixed settle time instead. Moving the motion module into
rc-players is on hold, so this is a proposal, not a change.

## The host's sequence

**Entering ambient.**

1. The system says the display is going ambient.
2. Keep drawing at the full rate until no animation that ends is in flight. A press springing back,
   a value that changed a moment ago: let it finish, so the snapshot isn't a frame from the middle
   of one. Cap the wait (half a second is plenty for the Material springs, which settle in
   0.15–0.6 s); past the cap, go on: promise 1 snaps whatever is left.
3. Set `host.ambient = 1`.
4. Draw one frame. It is the ambient look with every value at its target. A snapshot player keeps
   this one.

**Ticking** (about once a minute, or when the system wakes the display).

1. Write the values that changed since the last tick, coalesced: only the latest of each.
2. Advance the player's clock to now and draw one frame. Don't follow the player's requests for
   more: a spring started by step 1 is hidden behind its target, and loops are at rest.

A document that doesn't declare the flag gets no promise, so to show it truthfully the host would
have to draw until it settles on every tick. It is cheaper to dim it, or to show its last
interactive frame greyed.

**Leaving ambient.**

1. Set `host.ambient = 0` and go back to drawing on demand.
2. The look fades back on `effectsFast`. Values follow their springs, which are timed from the last
   change: a change made a minute ago has long settled, one made just now finishes its motion.

## Not done yet

- **The Wear app.** Entering and leaving ambient (`AmbientLifecycleObserver`), passing `ambient` to
  `HaRcComposePlayer`, and the tick cadence are
  [#1323](https://github.com/yschimke/homeassistant-remotecompose/issues/1323).
- **Card chrome.** The flag reaches the Material 3 building blocks (and so the Expressive tile
  sliders). A card's own background, text and icons don't read it yet; the theme's colour roles are
  the place to do that once, for every card, rather than card by card.
- **The player API** above, in rc-players.
