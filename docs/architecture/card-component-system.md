# Card component system

How a Home Assistant card is assembled from shared parts: the theming rules every part follows,
the three-layer data hierarchy from a raw reading to a typed visual, and the presentation ladder
that lets one part (a thermometer, say) grow, shrink and change what it shows with the cell it is
played in.

It also covers the **uber themes** (§5): whole looks such as Material 3 Expressive, a Sony-style
Trinitron, a segment LCD, Braun, Nixie and a skeuomorphic studio Console, which change shape,
type and meter drawing as well as colour.

It sits between three other documents and leans on them rather than repeating them:

- [`design/STYLE_GUIDE.md`](../../design/STYLE_GUIDE.md) — the palettes and the theme roles.
- [`live-card-updates.md`](live-card-updates.md) — the binding contract every value must keep.
- [`adaptive-card-layouts.md`](adaptive-card-layouts.md) — the ladder philosophy and alpha010's
  breakpoint limits.

## 1. Theming rules

The tokens live in
[`HaTokens.kt`](../../rc-components/src/main/kotlin/ee/schimke/ha/rc/components/HaTokens.kt).
A part is reusable across cards, a Wear widget and a launcher cell only if it follows all five.

| # | Rule | Token | Why |
|---|---|---|---|
| 1 | **Chrome colours come from the theme.** Card background, text, divider, link. | `haTheme()` → `RemoteHaTheme` | The four Terrazzo palettes, Material You and the launcher's system colours (`addThemedColor`) re-skin a part without a code change or a re-encode. |
| 2 | **Data colours are semantic and fixed across palettes.** An entity's state accent, a meter's band. | `HaStateColor` (rc-converter), `HaStatusColors`, `HaTemperatureColors`, `HaMeterColor` | A red warning is red in every theme; the palette describes the chrome, not the data. |
| 3 | **Text sizes come from the type scale**, chosen by what the text *is*. | `HaType` | One card's title and another's are the same size; a reading reads as a reading anywhere. |
| 4 | **Spacing and corners come from the scales.** | `HaSpace`, `HaShape` | Parts line up when composed. |
| 5 | **Parts draw no chrome.** Only a card draws the rounded surface, and only through `cardChrome`, which honours `ProvideCardChrome`. | `cardChrome`, `HaCardSurface` | The same part sits in a dashboard card, a stack-in-card, a Wear container (which brings its own shape) or a launcher cell without a card-in-a-card. |

### Type scale

| Role | sp | Use |
|---|---|---|
| `Micro` | 10 | axis ticks, the smallest widget captions |
| `Caption` | 12 | timestamps, range lines, a glance cell's state |
| `Label` | 13 | labels and secondary state (`secondaryText`) |
| `Body` | 15 | row names, a row's or tile's state, tile primary text (the card body) |
| `BodyLarge` | 16 | emphasised body, list items |
| `Title` | 17 | card titles and section headings |
| `ValueSmall` | 18 | a reading at a compact presentation |
| `Headline` | 22 | the heading card's title |
| `Value` | 24 | a card's primary reading (gauge, sensor, thermometer) |
| `ValueLarge` | 28 | a reading promoted on an expanded tier |
| `Display` | 32 | display numerals (a statistic, a thermostat target) |

The text roles are sized for a phone rather than for HA's desktop web: `Body` is 15 sp where HA's
card body is 13 px (Android lists use 14–16 sp). They were 11 / 12 / 13 / 14 / 16 for `Caption`
through `Title` until the card text was found too small on a phone; `Title` moved to 17 to stay a
step above `BodyLarge`. The scale is strictly ascending, so stepping a role down always shrinks
the text. An entity's state in a tile or an entities row is the value the card is read for, so it
is `Body` in `primaryText`, not a grey caption.

Adopting the scale collapsed the sixteen sizes the cards had drifted to. Card titles were 15 or
16 sp and are all `Title` now (`adaptiveTitleSize` steps long titles down to `BodyLarge` and
`Body`); primary readings were 22 or 24 sp and are all `Value`. The clock face is the one
exception, because its numerals are sized to the card rather than to a role.

## 2. The data hierarchy

```
 Layer 1 · reading          Layer 2 · visual meter        Layer 3 · typed measurement
 ─────────────────          ──────────────────────        ───────────────────────────
 HaReading                  RemoteHaArcMeter              RemoteHaThermometer
   text   ← <id>.state      RemoteHaBarMeter              RemoteHaBattery
   value  ← <id>.numeric_   RemoteHaThermometerGlyph      RemoteHaMeasurement (by kind)
            state           RemoteHaBatteryGlyph            ↳ RemoteHaAdaptiveMeasurement
   unit, min, max, kind                                       (picks the presentation live)
        │                            ▲                                  ▲
        └── fraction(), color() ─────┘                                  │
            (in-document)                         presentation ladder ──┘
```

### Layer 1 — the reading

[`HaReading`](../../rc-components/src/main/kotlin/ee/schimke/ha/rc/components/HaReading.kt) is
what Home Assistant says about one numeric entity, independent of how it is drawn: the formatted
text, the number, the unit, the scale, and the **kind** (`HaMeasureKind`: temperature, humidity,
battery, power, illuminance, percentage, generic).

It **owns the live-update contract**. `HaReading.live` binds the text to `<id>.state` and the
number to `<id>.numeric_state`, so it uses path 1 of [live-card-updates.md](live-card-updates.md)
and needs no `dataSignature`. Everything a visual derives from it is computed **in the document**
from those two bindings, so a new value tweens and recolours without a re-encode:

- `fraction()` gives `clamp((value − min) / (max − min), 0, 1)`, tweened.
- `color()` gives the kind's `HaMeterColor` rule as a select chain: `Fixed`, `Ramp` (temperature,
  with the stops converted for °F) or `LowIsBad` (battery).

A converter builds a reading with
[`haReading(entityId, entity, …)`](../../rc-converter/src/main/kotlin/ee/schimke/ha/rc/HaMeasurements.kt),
which uses `formatState(entity)` for the text (exactly what the hosts push) and classifies the
kind from `device_class`, falling back to `unit_of_measurement`. The card's own `name:`, `unit:`,
`min:` and `max:` override the entity's. Without a range, the kind's typical scale applies:
−10–40 °C, 14–104 °F, 0–100 %, 0–3000 W.

### Layer 2 — visual meters

[`RemoteHaMeters.kt`](../../rc-components/src/main/kotlin/ee/schimke/ha/rc/components/RemoteHaMeters.kt).
A meter draws a `0..1` fraction in a colour it is handed, and nothing else: no text, no chrome,
no entity. Each one:

- **fits the box it is given at its own aspect** (thermometer 1 : 2.6, battery 2 : 1, arc 2 : 1).
  The drawing is computed from the canvas `width` and `height` at playback, so the meter grows
  and shrinks with its cell continuously, inside a single tier and without a breakpoint;
- draws its **track in the theme's `divider`** and its value in the caller's colour, which can be
  an expression that changes with the live value;
- **prefers fills to strokes**, since a filled shape scales with the canvas while a stroke's width
  is fixed at capture.

The gauge card is now three presentations of `RemoteHaArcMeter`, where it used to be three
hand-copied canvases. Its severity bands moved to `HaStatusColors`.

### Layer 3 — typed measurements

[`RemoteHaMeasurement.kt`](../../rc-components/src/main/kotlin/ee/schimke/ha/rc/components/RemoteHaMeasurement.kt).
A measurement draws a reading the way its kind deserves. The kind picks the visual
(`HaMeasureVisual`):

| Kind | Visual | Colour rule |
|---|---|---|
| temperature | thermometer | comfort ramp: blue < 10 °C, cyan, green ≥ 18, amber ≥ 25, red ≥ 30 |
| battery | battery | green ≥ 30 %, amber, red < 15 % |
| humidity, power, generic | arc | fixed per kind |
| illuminance, percentage | bar | fixed per kind |

`RemoteHaThermometer` and `RemoteHaBattery` are the typed entry points; `RemoteHaMeasurement`
dispatches by the reading's kind. Adding a kind takes one enum entry with its visual, range and
colour rule. Adding a visual takes one glyph in Layer 2 and one branch in
`RemoteHaMeasurementGlyph`. The presentations below come for free.

## 3. Adaptive presentation

All measurements share one ladder (`HaPresentation`), so a thermometer and a battery shrink and
grow the same way. The ladder follows
[adaptive-card-layouts.md](adaptive-card-layouts.md)'s rungs and data priorities:

| Presentation | Shows | Priorities | Sizing |
|---|---|---|---|
| `Glyph` | the visual alone | P1 | sizes to its modifier |
| `Chip` | the visual over the value | P1 + P3 | fills its cell; for narrow cells (1×1 launcher) |
| `Compact` | small visual beside value + name | P1 P2 P3 | wraps; for wide-short cells (Wear S, 2×1) |
| `Standard` | card-sized visual beside value + name | P1 P2 P3 | wraps; the Wrap-mode dashboard default |
| `Expanded` | name, then the visual taking every spare dp, then the value at `ValueLarge` and the scale's range | P1–P4 | fills its cell; it *earns the canvas* (Principle 7) |

Two mechanisms make a measurement adaptive, and they stack:

1. **Continuous: the visual scales.** Inside any tier the glyph is drawn from the live canvas
   size, so a thermometer in a widget the user resizes grows and shrinks with no breakpoint.
2. **Discrete: one gate picks the rung.**
   [`RemoteHaAdaptiveMeasurement`](../../rc-converter/src/main/kotlin/ee/schimke/ha/rc/HaMeasurements.kt)
   plays `Expanded` in cells at least 120 × 132 dp and `Compact` (centred) in anything smaller.
   It uses the shared 2-D [`RemoteMinSizeGate`](../../rc-converter/src/main/kotlin/ee/schimke/ha/rc/RemoteMinSizeGate.kt),
   the single-expression `min(w, h · minW/minH) ≥ minW` test that alpha010 evaluates correctly
   (#224). That is the same gate the button card uses, now shared. The tap target sits on the
   gate rather than in the tiers, because a `RemoteAction` captured in both branches blanks the
   larger one (adaptive-card-layouts.md, Known gaps #9).

A card with more rungs than the one live gate allows picks rungs per surface instead. The sensor
card's narrow Fixed tier uses `Chip` for a temperature or battery, so a 1×1 launcher cell shows a
thermometer over `21.5 °C` instead of a generic tinted icon (Principle 1: identity before density).

## 4. In the UI builder (`ha-cards`)

The [`ha-cards` catalog](../../ha-cards-catalog/) offers each layer as a builder entry, under a
**Measurements** group:

| Entry | Layer | Notes |
|---|---|---|
| `ha-cards/arc-meter`, `ha-cards/bar-meter` | 2 | bare meters for composing a card of your own; `entityId` binds the value |
| `ha-cards/thermometer`, `ha-cards/battery` | 3 | typed |
| `ha-cards/measurement` | 3 | `kind` picks the visual, and `auto` reads the unit |
| `ha-cards/thermostat` | 3 | a climate entity's 270° dial; live through `<id>.current_temperature` / `<id>.target_temperature`; a knob in a lamp ring under `console` |

Every measurement takes `presentation`: one of the five rungs, or `adaptive`. The policy's
`allowedValues` are held to the code by `HaCardMeasurementsTest`. The starter templates use
`standard`: `component-thermometer` on its own, and inside both dashboard sections. A widget cell
wants `adaptive`, so it plays expanded at 2×2 and compact when resized smaller.

## 5. Uber themes

A palette (`ThemeStyle`, `HaTheme`) recolours the chrome. An **uber theme**
([`HaUberTheme`](../../rc-components/src/main/kotlin/ee/schimke/ha/rc/components/HaUberTheme.kt))
is a whole design: palette, card and control shape, typeface, how a value is set, and its own
**family of instruments** (`HaVisualFamily`). A family is a different *design* of each Layer 2
visual, not a restyle of one drawing. An LCD thermometer has no bulb, a Trinitron gauge has a
needle, and a Braun thermometer has no fill. Every part reads the theme through
`LocalHaUberTheme`, and hosts provide it with `ProvideCardStyle(theme, palette, dark)`.

| Theme | Dial (gauge, arc) | Thermometer | Battery | Bar | Readout | Card · controls |
|---|---|---|---|---|---|---|
| `classic` (default) | smooth arc | liquid tube over a bulb | outlined, filled | rounded bar | plain | 12 dp, border · pill switch |
| `expressive`: Material 3 Expressive | thick arc, **track gap + stop dot** | **fat capsule**, gap and stop dot, no bulb | pill with gap and dot | capsule with gap | value on a **tonal pill** | 28 dp, borderless · 16 dp |
| `trinitron`: techy Sony | **VU meter**: hairline scale, index ticks, red peak zone, swinging needle | **LED level column** beside a tick scale, red past the peak | hairline frame of LED cells | LED strip | **tracked label ruled off above** the value | 2 dp, hairline · square knob |
| `lcd`: segment display | 12 blocks with ghosts | **framed column of bars**, no bulb, **hot bars in a red ink** | **phone battery**, four bars, red when low | chunky segments | value in a **recessed screen** | 4 dp bezel · square block switch |
| `braun`: Dieter Rams | half-circle dial, index ticks, **orange pointer** on a black hub | **tuning scale**: spine, ticks, orange pointer, red ticks past hot | hairline, softly rounded | tuning scale | plain | 18 dp, borderless |
| `nixie` | glowing filament over electrode dots | **IN-9 bargraph tube** in its socket | horizontal tube | tube | plain | 14 dp · 4 dp |
| `console`: skeuomorphic studio desk | **backlit VU meter** behind glass: cream face, the standard VU scale, red from 0 VU, needle and pivot bridge | **fader**: metal cap in a lit slot beside a silkscreen scale, red marks past hot | **lamp in a metal bezel**, red when low | fader | ruled plate | 6 dp **painted-metal panel** (sheen) · metal cap in a recessed slot; dials are a **knob in a lamp ring** |

How each part takes it:

- **Meters**: `RemoteHaMeters.kt` dispatches each visual to the family's drawing in
  `Meters<Family>.kt`, which draws from the shared kit in `MeterDrawing.kt` (`MeterLook` colours,
  `FitBox`, `ArcBox`). Every family stays live. Fills grow with the fraction, and needles and
  pointers are `rotate`d or placed by it. Segments light when the fraction passes their midpoint,
  as an in-document select.
- **Zones** (`HaMeterZones`) mark where a scale turns noteworthy. A reading gives its own: the
  temperature ramp's 30 °C hot end, and a battery's 15 % critical level. A gauge passes its red
  severity band. Families draw a zone as hot segments (LCD), a red peak zone (Trinitron) or red
  ticks (Braun).
- **Readout** (`HaReadoutStyle`) sets the value: plain, a tonal pill, a ruled plate, or a screen.
- **Card chrome and controls**: `cardChrome` takes the theme's corner and border, and the toggle
  switch takes its corner and square or round knob (`HaCardShapeStyle`). `HaSurfaceMaterial` says
  what they are made of: `Flat`, or Console's `PaintedMetal`, where `cardChrome` lays a fixed
  top-lit sheen over the card colour (so a live background still shows) and the switch knob is a
  machined cap (a gradient brush) in a recessed slot.
- **Skeuomorphism stays live and original.** Console draws light and material with gradient
  shaders (`RemoteBrush` linear and radial gradients, applied to a paint) and shadows as offset
  translucent shapes — all vectors, sized from the canvas at playback, with the needle, fader cap
  and lamps moved by the live fraction. It is an original drawing of the 1970s-console genre; the
  only borrowed thing is the VU scale itself (ANSI C16.5), which is a standard. Don't trace a
  product's artwork: raster knobs from a CC-licensed gallery would also be legal with attribution,
  but they neither scale nor follow live values, so vectors are the rule.
- **Text**: every `RemoteHa*` text takes `style = haTextStyle()`, the theme's font family and
  tracking. Readings take `fontWeight = haValueWeight()`.
- **Ink** replaces rule 2's semantic data colours on themes that are monochrome by nature (LCD,
  Nixie): readings, and entity icons and switches (`HaToggleAccent.themed()`). Such a theme keeps a
  second **hot ink** for its hot and low segments, the way real LCDs had coloured overlay segments.

`classic` is exactly the look the cards had before uber themes (`HaUberThemeTest` pins it), so
nothing changes until a host provides another.

**In the app**, Settings has a **Card style** picker (`CardStylePref`, beside the palette).
Classic follows the app palette (`cardPalette` → `haThemeFor`), and every other style brings its
own. Dashboard cards and their section surfaces take it. It is part of the card's cache key, and
of its design key: Classic is `baked:<palette>:<mode>`, any other style `style:<id>:<mode>`, since
the app palette doesn't change it. The design server captures every style, and its manifest tags
each design with it ([card-design-server.md](card-design-server.md#server-api)).
Launcher widgets take the card style too (`LauncherLook`), and a card's own override. By default
they keep the launcher's system colours, with the style's instruments, type, shapes and ink on
them (`ProvideHaUberStyle`); Settings → **Launcher widget colours** → *Card style colours* draws
the style with its palette instead, as on the dashboard. The look is part of the launcher
template's key. Wear widgets use the watch's own card style.

In the builder, `ha-cards/uber-theme` is a container that takes a `theme` and `dark`.
`ha-cards/card-template` is the container a design that becomes a card design sits in: names and
titles inside it bind (`<entityId>.name`, `card.title`) as a template capture does.
`HaCardMeasurementsTest` keeps the policy's values in step with `HaUberTheme.all`. The showcase
previews are `Uber_*` in the `:previews` sheet, and the builder catalog shows Console itself in
`HaCard/UberThemeConsole`; the rest of the `:previews` showcases are in
[`UberThemePreviews.kt`](../../previews/src/main/kotlin/ee/schimke/ha/previews/UberThemePreviews.kt).

**Control dials and graphs.** The 270° dial of the thermostat, humidifier and light cards is
drawn per family too (`Dials.kt`; Console's knob in `MetersConsole.kt`), and so are graph rows
(`Charts.kt`): history sparklines and statistics bars.

| Theme | Control dial | Sparkline | Bars |
|---|---|---|---|
| `classic` | round arc, dot at target | 2 px line on a hairline baseline | flat bars |
| `expressive` | fat arc, gap, stop dot, ringed target | thick line over a soft area in the track tone | capsules |
| `trinitron` | ring of 36 LED cells inside a ticked scale, white index | hairline trace on a dotted graticule, newest sample marked | stacked LED cells |
| `lcd` | 15 chunky blocks with ghosts, pointer bar | bar graph of segments with ghosts | chunky segments with ghosts |
| `braun` | hairline scale with ticks, pointer bar in the accent | hairline trace, the latest reading an orange dot | thin bars |
| `nixie` | electrode dots and a glowing filament, a lit bead at target | glowing filament | glowing columns |
| `console` | knurled knob in a lamp ring | trace in a recessed screen | lamp-lit columns in a recessed screen |

**Controls and ink inside cards.** `haControlShape(classicDp, round)` gives a key, button, chip or
badge its shape under the theme: Classic keeps its own; the square-cornered themes (Trinitron,
LCD, Console) square controls off at their control corner; the soft ones keep circles and round
corners up to theirs. `haInk(color)` prints a marking accent (a calendar dot, the alarm keypad and
status ring, media controls, a dial's chip) in a monochrome theme's ink; given a capture-time
`Color`, it also makes it legible (`haLegible`): under any style but Classic an accent that
contrasts less than 3:1 with the card surface is mixed toward the primary text until it does, so a
green "Arm away" or a purple media accent doesn't sink into Console's blue panel. The alarm panel, media
control, calendar, area, picture, Bambu, weather and statistic cards take both.

**Wear and TV.** The Wear app has a **Card style** setting of its own (`WearPrefs.cardStyle`).
Slot widgets apply it with `ProvideHaUberStyle`, which brings the instruments, type, shapes and
ink but keeps the watch's palette (the WearM3 colours its host updates); changing it (or the
watch theme) re-renders placed widgets (`refreshAllSlotWidgets`). An explicit phone per-card or
per-card-type style override takes priority: the phone resolves `CardDesignOverrides.forCard` and
syncs `CardSummary.cardStyleOverride` with the pin, including when only the override changes.
Clearing it restores the watch-wide choice. An unknown style from a newer phone also follows the
watch choice. This field is persisted with the offline pin; the effective style is part of the
Wear template cache key. Variants, authors and device-specific server designs are still mobile
design selection features and are not transferred by this field. The TV kiosk has a card style
picker too (`TvPrefs.cardStyle`): its headline row is real cards (`TvKioskCards` — a light tile,
a thermostat and a humidity gauge from `TvDemoData.snapshot`), converted and played like the
dashboard's, so each style shows its own dials and meters there; the rest of the kiosk takes the
style's dark palette, corners, reading weight and ink.

**Feel and sound.** The app adds feedback for card actions on the host side, not in the
document: `rememberDashboardActionDispatcher(feedback = …)` cues each action that changes
something (`feedbackCueFor`: toggle on / off — read from the entity's current state — a key tap,
a confirm for an alarm, a reject when read-only mode drops an action or the call fails) and
plays it as a `HapticFeedbackConstants` haptic and, if the user turned **Interface sounds** on, a
quiet synthesised sound in the card style's voice (`SoundVoice`: a mechanical switch for Console,
a beep for LCD and Trinitron, a soft tick otherwise). The sounds are generated from code
(`FeedbackSynth.kt`), so there are no audio files to license. Navigation taps get nothing: their
ripple is enough. Remote Compose and the player also support in-document `HapticFeedback` and
`PlaySound` operations; a later step could let a card carry its own cue, which would reach
launcher widgets too.

## 6. Adoption checklist

When you add or change a part or a card:

1. No colour literals for chrome, only `haTheme()` roles. Data colours come from `HaStateColor`,
   `HaStatusColors` or a `HaMeterColor` rule.
2. No `N.rsp`: pick the `HaType` role for what the text is. Use `style = haTextStyle()` and,
   for readings, `fontWeight = haValueWeight()`, so uber themes reach the text.
3. A numeric reading is a `HaReading` built with `haReading(…)`, so its bindings are the hosts'
   and its derived values are computed in-document.
4. Draw with an existing Layer 2 meter before writing a new canvas. A new visual is a meter, not
   a card-private canvas, with a drawing for every `HaVisualFamily`, so each uber theme has its
   own design of it.
5. Give a measurement a presentation, or `RemoteHaAdaptiveMeasurement` in a Fixed-mode cell, and
   check the smallest and largest cells against adaptive-card-layouts.md's design-review checklist.
6. A new part that the builder should offer gets a `ha-cards` wrapper (scalar arguments only), a
   sticker and a policy entry.
