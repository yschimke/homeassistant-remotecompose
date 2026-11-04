# Theme kits: one card, any look

Cards should take a new look without being redrawn for it, and a new card should arrive in every
look without being drawn once per look. This document describes how theming and component
customisation make that work, and how to add a brand-style theme or a new component.

It extends the uber themes in [card-component-system.md §5](card-component-system.md#5-uber-themes)
and keeps every rule there: chrome from the theme, semantic data colours, `HaType`, parts draw no
chrome, and values bound by name ([live-card-updates.md](live-card-updates.md)).

![The lock, written once, under every theme](images/theme-kits/lock-every-theme.png)

*One lock component, with no code specific to any theme, drawn locked and unlocked under all 23
themes beside a dial and the `Switch` slot. A segment LCD lights bars, Braun and Cockpit slide a bolt,
Nixie glows, and a kit uses its own tokens.*

## The problem: N themes × M components

The bespoke uber themes (Console, LCD, Trinitron, …) draw each visual by hand: every Layer 2 meter
has an exhaustive `when (family)` over 19 families, and some cards branch on a family's identity
(`visualFamily() == HaVisualFamily.Console`). That gave the bespoke themes their character, but it
does not scale:

- a **new theme** (an EV dashboard or an engineered-appliance style) needs a drawing in every
  `when`: about 40 files;
- a **new component** (a car's door lock) needs a drawing for every family: 19 drawings.

## The model: tokens, grammar, slots

A theme is three layers, each a fallback for the one above it:

```
 HaUberTheme
 ├─ tokens      palette (HaTheme) · shape (HaCardShapeStyle) · type (font, tracking, weight)
 │              · motion (RcMotionScheme) · ink                               ── existing
 ├─ grammar     HaComponentGrammar: stroke weight · round/flat caps
 │              · level = Continuous | Segmented(n) | Pointer · ticks · track gap
 │              · stop dot · glow · gradient halo · outline bodies            ── new
 └─ components  HaComponentSlots: the theme's own drawing per slot — visuals
                (ArcMeter, BarMeter, Thermometer, Battery, Lock) and controls
                (Switch, Key, DialRing, DialLayout, TransportKey, IconChip)   ── new
```

**Resolution.** A part asks the active theme for its slot's drawing:

1. the theme's own drawing for that slot (`theme.components`), else
2. for a part that predates slots, the bespoke family's own, unchanged: the meters in
   `Meters<Family>.kt`, and the switch and key mount from the one family table in
   [`HaControls.kt`](../../rc-components/src/commonMain/kotlin/ee/schimke/ha/rc/components/HaControls.kt)
   (`familySwitch`, `familyKey`), else
3. the shared drawing, which reads only the theme's grammar and tokens: `GrammarMeters.kt` for
   visuals, `TokenSwitch` and `GrammarKey` for controls.

A slot takes one kind of renderer: a canvas drawing for a visual (`HaVisualRenderer`), a
composable control for the switch (`HaSwitchRenderer`, handed live knob and colour progress, the
accents, and the caller's click), and a modifier for a key's mount (`HaKeyRenderer`, applied
inside the caller's target so the action, accessible name and touch area stay with the card).
`HaComponentSlots` rejects a renderer of the wrong kind.

Every bespoke family has a grammar derived from it (`HaComponentGrammar.forFamily`): LCD and
Trinitron are segmented, Braun and Console use a pointer, Expressive is bold with a gap and stop
dot, Nixie glows. So a component with only a grammar drawing still looks native in a bespoke
theme. The lock above is that case: it has no LCD drawing, yet under LCD it is an LCD lock.

**Two kinds of theme.**

| | Bespoke theme | Theme kit |
|---|---|---|
| What it is | its own `HaVisualFamily`, drawn by hand | tokens + grammar over a bespoke theme it `extends` |
| Older visuals | the family's `Meters<Family>.kt` | `HaComponentSlots.Grammar` (all slots drawn from the grammar) |
| New components | grammar drawing, from its derived grammar | grammar drawing, from its own grammar |
| Controls (switch, keys, dial, transport keys, icon chip) | the family's own, from the family table, else the token control | the token / grammar controls, or a family's control it borrows |
| Card layouts and controls not yet in slots | the family's branches | inherited from the base it extends |
| Cost to add | a family, about 40 files | one `HaUberTheme(...)` value |

## Adding a component (the car door lock)

[`RemoteHaLock.kt`](../../rc-components/src/commonMain/kotlin/ee/schimke/ha/rc/components/RemoteHaLock.kt)
is the reference:

1. Add a `HaComponentSlot` entry.
2. Write **one** grammar drawing in `GrammarMeters.kt`, and register it in
   `HaComponentSlots.Grammar`. Use the grammar's terms rather than a family's: what a
   `Segmented` lock shows (lit bars), what a `Pointer` lock shows (a sliding bolt), and what
   `outline`, `roundCaps`, `glow` and `stroke` do to it. Follow the meter rules: fit the canvas at
   your own aspect, size everything from the canvas at playback, put the track in `divider`, the
   value in the caller's live colour, and move moving parts by the live fraction.
3. Add the composable: resolve with `themedRenderer(slot) ?: GrammarRenderers.X` and draw with
   `render(...)`. Never branch on `visualFamily()`.
4. Bind live data by name. `RemoteHaLiveLockGlyph` reads `<entityId>.is_on`, which hosts push as
   `true` while a lock is `locked`, and animates the change with `haMotion().spatial`, so the
   first frame shows the seeded value.
5. Make it semantic. The body is `HaStatusColors.Normal` when locked and `Warning` when unlocked,
   made legible with `haLegible`. A theme with an ink prints both in its ink and carries the state
   in the shackle and bars.

`RemoteHaLockRow` (glyph, name, live state) is then a card part: a car's doors, boot and charge
port are a column of these, and a front door is one. A theme that wants a bespoke lock (Console's
brass padlock, say) overrides `HaComponentSlot.Lock` and nothing else changes.

## Controls: the switch and the key mount

Cards ask for controls by role. `RemoteHaToggleSwitch` (an entities row, a tile) and
`haKeyChrome(accent, selected)` (about twenty key, chip and button sites across the cards) used to
branch on Console, IKEA, Expressive and the community families. They now resolve through the
`Switch` and `Key` slots, and no card names a family for them. `HaControlSlotsTest` checks that.

- **Bespoke themes are unchanged.** The family table hands Expressive its M3 switch, IKEA its
  paddle and moulded keys, Console its rocker and machined keys, and the community families their
  thumbs. Classic, Home Assistant, Trinitron, LCD, Braun and Nixie keep the token switch and keys
  with no mount. All 39 `Uber*` previews render byte-identical before and after the move.
- **Kits draw the shared controls.** `TokenSwitch` takes its look from the shape tokens (track
  corner, square or round knob), the material (a machined cap on painted metal) and the ink.
  `GrammarKey` draws nothing on a filled grammar, an outlined rim in the live accent on an outline
  grammar (Engineered), and a selected mode key's dot, with a halo on a glowing grammar.
- **A kit can borrow a family's control.** Aurora keeps Material 3 Expressive's switch with
  `HaComponentSlots.Grammar.with(HaComponentSlot.Switch, FamilyControls.ExpressiveSwitch)`.
- **One filament stays a token.** A painted-metal switch's lamp warms on the fast effect and
  cools on the slow one. That is now keyed on `HaSurfaceMaterial.PaintedMetal`, not on Console.

![The same glance card's keys across themes](images/theme-kits/keys.png)

### The control dial, transport keys and icon chip

Three more controls resolve the same way. Each family's own moved unchanged into the one table in
`HaControls.kt` (`familyDialLayout`, `familyTransportKey`, `familyIconChip`), and the cards ask by
role.

- **Control dial: two slots.** `DialRing` draws the 270° ring of a thermostat, humidifier or
  light. Kits get `GrammarRenderers.Dial`, built from their grammar:
  - Engineered: a ring of cells, lit as layers so a widget can play them.
  - Cockpit: ticks with a pointer on the ring. A needle from the centre would cross the readout.
  - Aurora: a gradient halo under the arc.
  - EV Dash: a thin arc.

  The target marker hides by size, not by colour. `DialLayout` is data rather than a drawing: which
  body a card shows (the ring, Console's thermostat with a separate set-point knob, Cupertino's bar,
  IKEA's light remote) and whether the steppers sit beside it (Bubble, Cupertino, LCARS, Aeon).
  Bespoke rings keep their fallback table: Classic's arc, Console's knob, the rest in `Dials.kt`.
- **Transport keys.** `TransportKey` draws the media card's previous, play/pause and next keys, and
  the compact play glyph. Console keeps its key faces with pilot lamps. Everything else is
  `TokenTransportKey`: the icon in the marking colour, mounted on the theme's `Key` slot when it
  has one. That gives IKEA its moulded key and Engineered an outlined rim.
- **Icon chip.** `IconChip` draws the button card's chip:
  - Expressive: the shape morph. It is `pressable`, so the card tracks a press only for chips that
    react to one.
  - Console: its key face.
  - IKEA: its moulded key.
  - Everyone else: `TokenIconChip`, a tinted round face with the theme's key mount when it has one.

  Aurora borrows Expressive's chip, as it borrows its switch.
- **Unchanged.** 187 previews (all `Uber*` showcases plus media, button, thermostat, dial, light
  and humidifier) render byte-identical before and after the move.

| Dials | Soft dials | Media keys | Button chips |
|---|---|---|---|
| ![Dials](images/theme-kits/dials.png) | ![Soft dials](images/theme-kits/dials-soft.png) | ![Media keys](images/theme-kits/media-keys.png) | ![Button chips](images/theme-kits/button-chips.png) |

## Adding a brand-style theme

[`ThemeKits.kt`](../../rc-components/src/commonMain/kotlin/ee/schimke/ha/rc/components/ThemeKits.kt)
has four kits:

| Kit | Genre | Extends | Grammar |
|---|---|---|---|
| `ev-dash` | an electric car's centre screen | classic | light strokes, round caps, smooth fill; light numerals |
| `aurora` | an assistant's gradients | expressive | regular, round, a glowing **gradient halo** under each value |
| `engineered` | an engineered appliance's display | braun | light, flat, **24 fine segments**, outlined bodies; fuchsia accent |
| `cockpit` | a car's digital cluster at night (dark only) | trinitron | flat, **pointer needles** over 12 ticks |

|  |  |  |
|---|---|---|
| ![Aurora](images/theme-kits/aurora-dark.png) | ![Cockpit](images/theme-kits/cockpit.png) | ![Engineered](images/theme-kits/engineered-light.png) |

To add one:

1. Choose the bespoke theme it `extends`. That is what its card layouts and controls inherit until
   they move into slots.
2. Write the tokens: a light and/or dark `HaTheme` (primary and secondary text at 4.5:1 or more on
   the card, accent at 3:1 or more; `kitPalettesAreLegible` checks this), the shape, the font
   family, weight and tracking, and a motion scheme.
3. Write the grammar. Most of a look is here: how heavy, round or flat, and whether a level is
   smooth, segmented or a pointer.
4. Set `components = HaComponentSlots.Grammar`, plus any overrides that make it itself (for
   example `HaComponentSlots.Grammar.with(HaComponentSlot.ArcMeter, mySweep)`).
5. Register the id. Until the lists below read `HaUberTheme.all`, that means `HaUberTheme.all`,
   `CardStylePref`, the Settings tagline `when`, the design server's style picker
   (`dashboard/index.html`), the `ha-cards/uber-theme` policy's `allowedValues`, and the demo's
   style list. The design server's request check already reads `HaUberTheme.all`.
6. Add a `Kit_*` preview in `ThemeKitPreviews.kt`. Kits use the default launcher icon until one
   is drawn for them.

### Brands

A kit evokes a **genre**, not a company. Ids, display names, palettes and drawings are original,
and the code ships no brand name, logo, proprietary typeface or traced artwork. This is the same
rule the IKEA-inspired showroom and the Sony-inspired Trinitron follow. "Tesla style" becomes an
EV centre-screen kit, "Gemini style" an assistant-gradient kit, "Dyson style" an engineered
appliance kit, and "VW style" a cockpit kit. A licensed partner theme with real marks and fonts
would be the same kit with licensed assets, behind whatever agreement allows it; it would need no
code change.

## Rules that do not bend

- **Semantic colours win over brand colours.** A grammar `gradient` is a halo **under** the value.
  The value keeps its own colour, so a locked lock stays green and a severity band stays red. The
  first Aurora render broke this, painting locked and unlocked locks the same gradient, which is
  why the rule exists.
- **No family checks in new code.** A new part never calls `visualFamily()`. If a theme needs to
  differ, that difference is a grammar token (if many themes would use it) or a slot override (if
  one theme would).
- **Live, then styled.** A slot drawing is handed the live fraction and live colour. It cannot
  read entity state itself, so a theme can't break the binding contract.

## Migration: what still multiplies

The kit layer is in place for Layer 2 visuals. These places still branch on the family and are
next to move into slots. Each one moved makes every kit look more like itself, without per-kit
code:

| Part | Where | Slot it should become |
|---|---|---|
| Media panel layouts and artwork (Cupertino, Arctic, Aeon panels; Console's taped placeholder) | `RemoteHaMediaControl` | `MediaLayout`, `Artwork` |
| Bespoke dial rings (still a fallback table, like the meters) | `Dials.kt`, `RemoteHaArcDial` | one `DialRing` per family |
| Sparklines and bars | `Charts.kt`, `HistoryTraces.kt` | `Sparkline`, `StatBars` |
| Tile layout | `RemoteHaTile`, `TileFamilies.kt` | `TileLayout` (a layout slot, not a canvas) |
| Card chrome material | `CardChrome.kt` (`communityFrame`, painted metal) | `CardSurface` |
| Wear container and level | `HaWearBackground.kt` | derived from grammar + palette |
| Theme id lists | prefs enum, Settings, policy JSON, design-server page, demo | read `HaUberTheme.all` |

Renderer types (`HaVisualRenderer`, `HaVisualSpec`) are `internal` to `rc-components` for now.
Making them public is the step that lets a theme come from outside the module, for example from
the design server.
