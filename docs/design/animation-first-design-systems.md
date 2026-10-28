# Animation-first design systems

Status: **in progress**, 2026-10-28. Implemented: the motion layer (§2) and its schemes, the
M3 Expressive switch (§3), and ambient mode (§5) on the TV kiosk. The rest is proposal.

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
| Switch track / button accent | 0.20 s cubic | `effectsFast` |
| Gauge, arc, meter fill | 0.45 s cubic | `spatial` (bounded `0..1`) |
| Control dial value and target | 0.45 s cubic | `spatial` |
| Wear container level | `LEVEL_SECONDS` | `spatialSlow` |
| Cover / door position | `DOOR_TWEEN_SECONDS` | `spatialSlow` |
| Meter colour (ramp, zone) | follows the fill | `effects` |

`HaUberTheme` gains a `motion: RcMotionScheme`, and `ProvideCardStyle` provides it with the rest
of the theme. `classic` keeps its current tweens as a `Tween` scheme, so `HaUberThemeTest`'s
"classic is unchanged" pin still holds.

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

| Building block | Motion | Mechanism |
|---|---|---|
| **Switch** | thumb grows from 16 to 24 dp as it turns on, then springs across; icon in the thumb crossfades | size and position on `spatialFast`, icon alpha on `effectsFast` |
| **Press squish** | a pressed button or key narrows its corners (shape morph) and rebounds on release | `FLOAT_TOUCH_EVENT_TIME` → a decaying press fraction → `PathTween` between rest and pressed shapes |
| **Shape morph on state** | a toggle tile's icon container morphs between two M3 shapes (circle ↔ cookie / pill) when on | `PathTween` on the bound on/off fraction, `spatial` |
| **Wavy progress** | a running vacuum, a printing Bambu job: the progress indicator's track waves; amplitude springs to flat when idle | wave phase from `FLOAT_ANIMATION_TIME` (a deliberate loop, not an entrance); amplitude on `effects` |
| **Track gap and stop dot** | the gap travels with the fill; the stop dot fades as the fill reaches it | gap on the fill's spring, dot on `effectsFast` |
| **Slider handle** | handle narrows while dragged, springs back on release | the drag state the [slider controls](../architecture/slider-controls.md) already keep in-document |
| **Loading indicator** | the M3 morphing-polygon loader for a card waiting on data | `PathTween` through the shape sequence, looping |

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
| Pilot lamp | incandescent filament: warms in ~100 ms, cools slower | effects (900, 1.0); see below |
| LED clock segments | instant | `Snap` |

**Asymmetry** is what makes real instruments read as real: a lamp cools slower than it warms, a
PPM meter (IEC 60268-10) rises in milliseconds and falls 20 dB over 1.5 s. One animated float has
one spec, so asymmetric parts animate two copies — the fast and slow responses of the same bound
value — and pick per frame with an in-document `select` on the direction of change (target above or
below the displayed value). That is one extra expression per part and no host work.

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
`LocalRcMotionMode` to `Ambient` and every theme's motion resolves to `RcMotionScheme.ambient()`
(`RcMotionMode.Reduced` works the same way for reduced motion). The TV kiosk's card row does this
today. A Wear ambient host is next: it re-renders on the ambient tick, where a slow critically
damped spring is the only motion that doesn't look like a glitch.

## 6. The rest: three archetypes

Every other theme picks one of three motion archetypes and tunes its tokens, rather than
inventing a fourth:

| Archetype | Themes | Character |
|---|---|---|
| **Designed springs** | `expressive`, `home-assistant`, `classic` (as tweens), the community families (Mushroom, Bubble, Cupertino …) | M3 `expressive()` or `standard()`; Cupertino-style families take an iOS-like critically damped `spatial` |
| **Physical ballistics** | `console`, `trinitron`, `braun` | needles with mass (Trinitron's VU, Braun's pointer on its hub), lamp and LED thermal response |
| **Discrete** | `lcd`, `nixie`, `lcars` | no interpolation between values: segments switch (`Snap`); an LCD segment fades in ~150 ms as liquid crystal does, a Nixie digit crossfades cathodes with a brief glow overlap |

## 7. Rules carried over

- **First frame shows current data.** Springs start settled (§1). No scheme may add an entrance
  animation; looping motion (wavy progress, a loader) is allowed only while the entity *is* doing
  something, and is driven by a bound state, never by appearance.
- **Live through named slots.** Motion only ever animates bound values; a scheme never needs a
  re-encode ([live-card-updates.md](../architecture/live-card-updates.md)).
- **Frozen captures stay deterministic.** `animate` encodes no animation when
  `LocalRemoteAnimationsEnabled` is off, as `animateRemoteFloat` does today.
- **Reduced motion is a scheme, not a theme.** A user setting (and the system's
  "remove animations") swaps spatial tokens for `Snap` and keeps short effects fades, under any
  theme.
- **Widgets.** Community widgets already bake settled values; the platform (launcher) player's
  spring support must be verified before springs reach widget documents, with a tween fallback.

## 8. Plan

1. `rc-material3-motion`: `RcMotionSpec`, `RcMotionScheme`, `animate`, the spring encoder, M3
   `expressive()` / `standard()`; unit tests that decode the spec and step the spring.
2. `HaUberTheme.motion`; move the `animateRemoteFloat` sites to roles (§2 table); spring
   fractions rather than raw values. `classic` pinned unchanged.
3. Expressive building blocks one at a time, switch first (most visible), each with a live-update
   test in the style of `CommunityFamilyLiveRenderTest` that captures mid-flight frames, since a
   screenshot of a settled frame proves nothing about motion.
4. Console ballistics scheme and the asymmetric lamp.
5. The discrete archetype for LCD and Nixie.
6. Graduate the module to `rc-players`.

## Decisions

- The reusable module incubates here as `rc-material3-motion`, then graduates to `rc-players`.
- Ambient is the third system, applied as a host motion mode rather than a card style.
