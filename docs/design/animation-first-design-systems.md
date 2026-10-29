# Animation-first design systems

Status: **implemented**, 2026-10-29: the motion layer (§2) on every animated card part, the three
systems (§3–§5), the archetypes for every card style (§6) and the Expressive building blocks in §3.
Open: ambient on Wear
([#1323](https://github.com/yschimke/homeassistant-remotecompose/issues/1323)), and the module's
move to `rc-players` (on hold, §8).

Today every uber theme ([card-component-system.md §5](../architecture/card-component-system.md#5-uber-themes))
is designed as a set of still frames, and motion is added afterwards: a dozen `animateRemoteFloat`
call sites, each with its own hand-picked duration (0.20 s toggles, 0.45 s gauges and dials, a
door tween), and the same cubic `Standard` easing in every theme. An LCD segment, a Console VU
needle and a Material 3 Expressive switch thumb all move along the same curve.

An **animation-first** design system starts the other way round: motion is a token set, like
colour and type, and each theme says *how its things move* before it says how they look. This
document proposes:

1. a theme-independent **motion layer** for Remote Compose documents, built on the player's spring
   engine;
2. a **Material 3 Expressive** motion scheme on that layer, in a reusable module with no Home
   Assistant code in it;
3. a second, contrasting system — **Console instrument ballistics** — to prove the layer is not just
   M3 in disguise;
4. a third, **Ambient**: calm motion for always-on and glanceable displays, applied by the host over
   any theme;
5. how the remaining themes fall out of the same three archetypes.

## 1. What the player can already do

Checked against `remote-core` 1.0.0-alpha20 (the player the compose-preview CLI ships; the app's
alpha22 creation side writes the same spec):

| Capability | Where | Use |
|---|---|---|
| **Physical springs** | `FloatExpression` builds a `SpringStopEngine` when the animation spec is longer than 4 floats and its first is `0`: `[0, stiffness, damping, stopThreshold, boundaryMode]`, mass 1. | M3 springs, needle ballistics |
| Spring **boundaries** | `boundaryMode` (an int in float bits) is a bit mask: bit 1 reflects off `0`, bit 2 reflects off `1`. | A fraction that bounces off full instead of overshooting past it |
| Starts settled | The first evaluated value sets both the spring's position and target. | The [first-frame rule](../architecture/live-card-updates.md#first-frame-shows-current-data-only-updates-animate) holds by construction |
| Cubic tweens | `CUBIC_STANDARD` … `CUBIC_OVERSHOOT`, `CUBIC_CUSTOM` (4 control points), `SPLINE_CUSTOM` (monotonic spline), bounce, elastic | Duration-based motion where a spring is wrong (a fixed-length fade) |
| Path morphing | `PathTween`, `DrawTweenPath` | Shape morphs: a square key rounding into a pill as it turns on |
| Touch time | `FLOAT_TOUCH_EVENT_TIME`, `TouchExpression` | Press feedback that decays after release |
| Global off switch | `RemoteContext.setAnimationEnabled` | Reduced motion and frozen captures |

Two details of the spring decide how it is used:

- **Damping is a coefficient, not a ratio.** The engine integrates `a = (−k·(x − target) − c·v) / m`,
  so a design token written as Compose's `(stiffness, dampingRatio)` encodes as
  `c = 2 · ζ · √k` (mass 1).
- **The stop threshold is in the animated value's units.** Springing a temperature in °C and
  springing a `0..1` fraction need different thresholds. The layer should spring **fractions**
  (always `0..1`) and derive units from them, the reverse of `HaReading.fraction()` today, which
  tweens the raw value and normalises afterwards. That also makes the boundary bits mean "the ends
  of the scale".

Springs are also **interruptible**: a new target mid-flight keeps the current velocity, so a
thermostat target stepped five times in a second glides, where a tween restarts five times.

## 2. The motion layer

A small set of types, all creation-side, all pure Kotlin over `remote-creation-compose`:

```kotlin
/** One motion token: a spring, or (rarely) a fixed-length tween. */
sealed interface RcMotionSpec {
  data class Spring(val stiffness: Float, val dampingRatio: Float, val bounds: RcBounds = RcBounds.None) : RcMotionSpec
  data class Tween(val seconds: Float, val easing: RcEasing) : RcMotionSpec
  data object Snap : RcMotionSpec          // discrete systems, reduced motion
}

/** Six tokens, the M3 shape: what moves × how fast. */
data class RcMotionScheme(
  val spatialFast: RcMotionSpec, val spatial: RcMotionSpec, val spatialSlow: RcMotionSpec,
  val effectsFast: RcMotionSpec, val effects: RcMotionSpec, val effectsSlow: RcMotionSpec,
)

val LocalRcMotionScheme: ProvidableCompositionLocal<RcMotionScheme>

@Composable fun RemoteFloat.animate(spec: RcMotionSpec): RemoteFloat
```

- **Spatial** tokens move things: a fill, a needle, a thumb, a size. They may overshoot.
- **Effects** tokens change things in place: colour, alpha, a crossfade. They never overshoot —
  a colour past its target is a wrong colour.
- `animate` returns the input untouched when animations are off (the existing
  `LocalRemoteAnimationsEnabled` moves into the layer) and encodes `Snap` as no animation at all.
- **Readings never overshoot.** Text shows numbers, and a number the entity never had is a lie;
  a reading takes an effects token or snaps. Only its *visual* (fill, needle) is spatial.

`rc-components` then maps its parts to roles once, instead of choosing seconds at each call site:

| Part | Role today | Token |
|---|---|---|
| Switch thumb, toggle knob | 0.20 s cubic | `spatialFast` |
| Switch track / button accent | 0.20 s cubic | `effectsFast` (Console: `effectsFast` up, `effectsSlow` down) |
| Gauge, meter fill (`HaReading.fraction`) | 0.45 s cubic | `spatial` (bounded `0..1`) |
| Control dial value and target | 0.45 s cubic | `spatial` |
| Wear container level | 0.6 s cubic | `spatialSlow` |
| Cover / door position | 0.6 s cubic | `spatialSlow` |
| Meter colour (ramp, zone) | follows the fill | follows the fill |

Every part animates a `0..1` fraction; the gauge and `HaReading.fraction` used to animate the raw
value and normalise it afterwards. `HaUberTheme.motion` defaults to its family's archetype
(`HaMotion.forFamily`, §6) and `haMotion()` reads it under the host's mode and the viewer's
reduce-motion preference. `classic` keeps its tweens as a `Tween` scheme (`HaMotion.Classic`),
whose values are the durations above, so its documents carry the same animations.

## 3. System one: Material 3 Expressive

The scheme is Compose Material 3's `MotionScheme.expressive()` (with `standard()` alongside),
encoded for the player:

| Token | Stiffness | Damping ratio | Encoded damping `c` | Feel |
|---|---:|---:|---:|---|
| `spatialFast` | 800 | 0.6 | 33.94 | snappy, visible bounce |
| `spatial` | 380 | 0.8 | 31.19 | the default move, slight overshoot |
| `spatialSlow` | 200 | 0.8 | 22.63 | large travel |
| `effectsFast` | 3800 | 1.0 | 123.29 | critically damped |
| `effects` | 1600 | 1.0 | 80.00 | critically damped |
| `effectsSlow` | 800 | 1.0 | 56.57 | critically damped |

`standard()` uses spatial springs of 1400 / 700 / 300 at ratio 0.9 and the same effects. Re-check
both against the M3 motion tokens when this lands; they are the published values at the time of
writing.

Beyond the scheme, the Expressive *behaviours* the current `expressive` theme draws as still
frames, each a building block:

| Building block | Motion | Mechanism | Status |
|---|---|---|---|
| **Switch** (`RemoteM3Switch`) | thumb grows from half to three quarters of the track as it turns on, then springs across | size and position on `spatialFast`, colours on `effectsFast` | done; the toggle switch under Expressive |
| **Press** (`rememberRcPress`) | a pressed control squishes and springs back on release | touch down / up / cancel write an in-document `0/1`; the fraction follows on `spatialFast`. It sits on the same component as the click and doesn't take it | done; the button card under Expressive |
| **Shape morph** (`RemoteM3ShapeMorph`, `m3MorphCorner`) | a container is round at rest, squarer when selected, squarer still while pressed | a rounded rectangle whose corner is an expression of the two fractions. Material morphs polygons (`MorphTweenUtility`), but that is Android-only and writer-level; a corner morph is portable and covers the toggle-button shapes | done; the button chip under Expressive |
| **Wavy progress** (`RemoteM3WavyProgress`, `RemoteM3WavyProgressRing`) | the filled part ripples while the thing it shows works; flat when not | line segments along a sine whose phase is the player's animation clock; a gap and a stop dot on the linear form | done; the Bambu progress ring under Expressive, rippling only while printing |
| **Track gap and stop dot** | the gap travels with the fill or handle; the stop dot fades as the fill reaches it | the gap is placed from the same fraction as the fill, and the dot's alpha from the room left before it | done; the wavy indicator and the slider |
| **Slider** (`drawM3Slider`) | active track, gap, a narrow full-height handle, gap, inactive track ending in a stop dot; the handle narrows to half while held and springs back | drawn in the canvas that takes the drag, so it follows the finger with no lag; the press ([rememberRcPress]) is a second set of touch handlers beside the drag's own | done; tile slider features (brightness, position, volume) under Expressive |
| **Loading indicator** (`RemoteM3LoadingIndicator`) | a shape that turns and breathes between circle and rounded square | rotation and corner on the player's clock. Material cycles seven polygons; this keeps to what a rounded rectangle draws | built; no card shows a loading state yet |

Loops on the player's clock (wavy progress, the loader) are motion the design asks for while
something works, not entrances. They draw their first frame when animations are off (frozen
previews), when the viewer asks for less motion (reduce motion, or the reduced or ambient mode),
and when the player is a system renderer (`LocalRcInteractivePlayer`: launcher and watch widgets),
where press feedback is off too. `ExpressiveBlocksPlaybackTest` plays them in the Compose
player: a held press reshapes the chip and the tap still sends, the running wave, ring and loader
move between frames, and a ring that isn't running stays still.

Each block is a Remote Compose composable that takes bound values and a scheme, and knows nothing
about entities.

### Where it lives: outside `ee.schimke.ha`

A module, **`rc-material3-motion`**, package `ee.schimke.rc.m3`:

- depends only on `remote-creation-compose` (the AndroidX artifact on Android, the
  `third-party-remote-creation-compose` fork elsewhere, as `rc-components` already does) and the
  Compose runtime;
- **no** `ha-model`, `rc-converter` or `rc-components` dependency, so nothing Home Assistant leaks
  in (its build file says so; a hygiene check can enforce it once a second module depends on it);
- holds the motion layer (§2) plus the M3 scheme and building blocks (§3); the Console scheme
  (§4) stays in `rc-components`, since it is ours.

Incubate it here, where every card converter exercises it, then **graduate it to the `rc-players`
family** (`ee.schimke.composeai`, versioned by `rc-players-bom`), which already publishes the
non-HA Remote Compose pieces this app consumes. The hosted `m3-catalog` and `wear-m3-catalog`
reference systems are the comparison point for its previews. Longer term the blocks are a natural
fit for AndroidX `remote-material3`, which today has the components but not the motion.

## 4. System two: Console instrument ballistics

The contrast to M3's designed-for-delight springs is motion taken from **physical standards**.
Console already draws a VU meter to ANSI C16.5's scale; the same standard specifies how the needle
moves: it reaches 99 % of a step in **300 ms** and overshoots by **1–1.5 %**. As a spring:

- overshoot 1.25 % → damping ratio `ζ = −ln(0.0125) / √(π² + ln²(0.0125)) ≈ 0.81`;
- first crossing of 99 % at 0.3 s → `ωₙ ≈ 13.5 rad/s`, so **stiffness ≈ 183, damping `c` ≈ 22.0**.

That is, within rounding, M3 Expressive's `spatialSlow` (200, 0.8) — a nice check that both systems
sit on one engine — but the rest of the Console scheme is nothing like M3:

| Console part | Physical model | Token |
|---|---|---|
| VU needle, fader cap | VU ballistics above | `spatial` = (183, 0.81) |
| Knob in a lamp ring | heavy detented knob: overdamped, no bounce | (300, 1.2) |
| Rocker / key | mechanical throw: very stiff, one tiny rebound | (2000, 0.55), bounded |
| Pilot lamp | incandescent filament: warms in ~0.2 s, cools in ~0.6 s | `effectsFast` (900, 1.0) up, `effectsSlow` (100, 1.0) down |
| LED clock segments | instant | (the clock is not animated) |

**Asymmetry** is what makes real instruments read as real: a lamp cools slower than it warms, a
PPM meter (IEC 60268-10) rises in milliseconds and falls 20 dB over 1.5 s. One animated float has
one spec, so an asymmetric part animates two copies of the same bound value, the attack and the
release, and shows the **larger**: on a rise the fast copy leads, on a fall the slow one lags
(`animateAttackRelease`). Both must be critically damped, or an overshoot shows through. It costs
one extra animated expression and no host work. Console's switch lamp uses it; a PPM or a
Trinitron peak-hold would too.

`HaMotion.Instrument` serves Trinitron's VU meter and Braun's pointer as well as Console.

Console's motion keeps its skeuomorphism rule: everything is a vector moved by the live fraction,
and the sounds and haptics (`SoundVoice`, `FeedbackSynth`) already match it.

## 5. System three: Ambient

A kiosk on a wall, a watch in ambient, a TV screensaver: displays watched from across the room, or
not at all, for hours. Bounce there reads as nervous and every flicker costs attention (and, on
OLED, pixels). Ambient is a scheme of **slow, critically damped springs** — nothing overshoots,
nothing snaps — settling to 2 % in about 0.35 / 0.6 / 0.9 s spatially and 0.4 / 0.65 / 1.05 s for
effects:

| Token | Stiffness | Damping ratio |
|---|---:|---:|
| `spatialFast` / `spatial` / `spatialSlow` | 300 / 100 / 40 | 1.0 |
| `effectsFast` / `effects` / `effectsSlow` | 200 / 80 / 30 | 1.0 |

Ambient is a property of the *display*, not of a design, so it is not a card style. A host sets
`LocalRcMotionMode` to `Ambient` inside the card's capture and every theme's motion resolves to
`RcMotionScheme.ambient()`. The TV kiosk's card row does.

A watch's always-on is a different thing: a low-power display that draws about once a minute. That
is a runtime flag the host writes (`host.ambient`), not this capture-time mode. Under it, values
show their targets, loops rest and the building blocks draw outlines and thin tracks; see
[ambient mode](../architecture/ambient-mode.md) for the contract and the host's sequence. The Wear
app sets neither yet ([#1323](https://github.com/yschimke/homeassistant-remotecompose/issues/1323)).

## 6. The rest: three archetypes

Every other theme picks one of three motion archetypes and tunes its tokens, rather than
inventing a fourth:

| Archetype | Themes | Character |
|---|---|---|
| **Designed springs** | `expressive` (`expressive()`), `home-assistant`, IKEA and the community families (`standard()`), `cupertino` (`HaMotion.Cupertino`), `classic` (`HaMotion.Classic` tweens) | Cupertino's springs are iOS's: critically damped, by response time 0.3 / 0.55 / 0.8 s (`stiffness = (2π / response)²`) |
| **Physical ballistics** | `console`, `trinitron`, `braun` (`HaMotion.Instrument`) | needles with VU mass, a stiff rocker, an overdamped knob, a lamp's attack and release |
| **Discrete** | `lcd`, `lcars` (`HaMotion.Lcd`), `nixie` (`HaMotion.Nixie`) | spatial tokens `Snap`: segments switch, nothing glides. Effects fade linearly, 0.15 s for liquid crystal, 0.25 s for a Nixie cathode's lingering glow |

## 7. Rules carried over

- **First frame shows current data.** Springs start settled (§1). No scheme may add an entrance
  animation; looping motion (wavy progress, a loader) is allowed only while the entity *is* doing
  something, and is driven by a bound state, never by appearance.
- **Live through named slots.** Motion only ever animates bound values; a scheme never needs a
  re-encode ([live-card-updates.md](../architecture/live-card-updates.md)).
- **Frozen captures stay deterministic.** `animate` encodes no animation when
  `LocalRemoteAnimationsEnabled` is off, as `animateRemoteFloat` does today.
- **Reduced motion is a preference, not a theme.** `LocalRcReduceMotion` swaps spatial tokens for
  `Snap` and keeps short effects fades, under any theme and over any mode. `CachedCardPreview`
  sets it from Android's "Remove animations" (`ValueAnimator.areAnimatorsEnabled()`) and keys the
  document by it. Pre-captured template documents (server designs) carry the motion they were
  captured with.
- **Widgets.** Launcher widgets are re-baked on every change, so a launcher capture encodes springs
  as no animation (and community widgets nothing at all, to fold their colours). Wear widgets are
  played by the watch's system renderer, whose spring support is unverified, so their capture sets
  `LocalRcSpringsSupported` off and a spring encodes as a tween of about its length
  (`Spring.asTween()`).

## 8. Plan

1. **Done.** `rc-material3-motion` (package `ee.schimke.rc.m3`): `RcMotionSpec`, `RcMotionScheme`
   (`expressive()`, `standard()`, `ambient()`, `reducedMotion()`), `RcMotionMode`, `animate`, the
   spring encoder. `RcMotionSpecTest` steps the encoded springs through the player's own
   `SpringStopEngine`.
2. **Done.** `HaUberTheme.motion` from the family's archetype; every `animateRemoteFloat` site in
   the §2 table reads a token. `haMotion()` resolves from the uber theme, so a part under a palette
   alone keeps the Classic tweens.
3. **Done.** The switch, press, shape morph, wavy progress, slider and loader blocks (§3's table),
   used by the toggle switch, the button card, the tile sliders and the Bambu ring under
   Expressive.
4. **Done.** `HaMotion.Instrument` and `animateAttackRelease` on Console's lamp.
5. **Done.** `HaMotion.Lcd` / `HaMotion.Nixie`.
6. **Done.** Reduced motion from the system setting; ambient on the TV kiosk; the Wear widget
   spring fallback.
7. Graduate the module to `rc-players` (another repository): on hold.

`RcMotionSpecTest` steps the encoded springs, the attack/release pair and the tween stand-ins
through the player's own engines; `MotionSchemeSwitchTest` checks the animations encoded in real
documents for every archetype, mode and preference.

## Decisions

- The reusable module incubates here as `rc-material3-motion`, then graduates to `rc-players`.
- Ambient is the third system, applied as a host motion mode rather than a card style.
