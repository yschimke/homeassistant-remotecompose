@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.ui.graphics.Color
import ee.schimke.composeai.rccreation.compose.text.RemoteFontFamily
import ee.schimke.composeai.rccreation.compose.text.RemoteFontWeight
import ee.schimke.rc.m3.RcMotionScheme

// ---------------------------------------------------------------------------------------------
// Theme kits: whole looks made of tokens, not of new drawings. Each kit is a palette, a shape, a
// typeface, a motion scheme and a component grammar over a bespoke theme it extends (for the card
// layouts and controls not yet drawn through slots), with HaComponentSlots.Grammar drawing its
// visuals. A kit adds no Meters<Family>.kt and touches no card: every card that draws through the
// shared parts takes it, and so does every component added later.
//
// Kits evoke a genre — an electric car's centre screen, an assistant's gradient, an engineered
// appliance, a car's digital cluster, a rugged digital sports watch — with original palettes and
// drawings. They carry no brand name, logo, typeface or artwork; see
// docs/architecture/theme-kits.md §"Brands".
// ---------------------------------------------------------------------------------------------

internal object ThemeKits {
  /**
   * **EV Dash** — the calm centre screen of an electric car: near-black glass or white, light
   * numerals, thin round-ended arcs and no borders. Everything quiet but the reading.
   */
  val EvDash =
    HaUberTheme(
      id = "ev-dash",
      displayName = "EV Dash",
      description = "Calm centre-screen glass · light numerals and thin arcs",
      light =
        palette(
          dark = false,
          card = 0xFFFFFFFF,
          dashboard = 0xFFEEEFF1,
          primary = 0xFF171A20,
          secondary = 0xFF5C5E62,
          link = 0xFF3457D5,
          divider = 0xFFD9DBDF,
          placeholder = 0xFFF1F2F4,
        ),
      dark =
        palette(
          dark = true,
          card = 0xFF16171A,
          dashboard = 0xFF0B0B0C,
          primary = 0xFFF5F5F7,
          secondary = 0xFFA1A4AA,
          link = 0xFF6E9BFF,
          divider = 0xFF2A2C31,
          placeholder = 0xFF1F2125,
        ),
      shape = HaCardShapeStyle(cornerDp = 14, borderDp = 0, controlCornerDp = 10),
      visuals = HaVisualFamily.Classic,
      fontFamily = RemoteFontFamily.SansSerif,
      valueWeight = RemoteFontWeight.Light,
      motion = RcMotionScheme.standard(),
      grammar = HaComponentGrammar(stroke = HaStrokeWeight.Light),
      components = HaComponentSlots.Grammar,
      extends = "classic",
    )

  /**
   * **Aurora** — an assistant's look: soft rounded surfaces, and a glowing blue-violet-rose
   * gradient halo under every value, which keeps its own colour. Borrows Expressive's switch and
   * icon chip.
   */
  val Aurora =
    HaUberTheme(
      id = "aurora",
      displayName = "Aurora",
      description = "Assistant gradients · glowing sweeps on soft rounded cards",
      light =
        palette(
          dark = false,
          card = 0xFFFFFFFF,
          dashboard = 0xFFF0F3F9,
          primary = 0xFF1B1C1F,
          secondary = 0xFF575B63,
          link = 0xFF2F62D6,
          divider = 0xFFDDE3EE,
          placeholder = 0xFFE8EEF9,
        ),
      dark =
        palette(
          dark = true,
          card = 0xFF1E1F22,
          dashboard = 0xFF131316,
          primary = 0xFFE8E9ED,
          secondary = 0xFFA9ADB6,
          link = 0xFF8FB5FF,
          divider = 0xFF33353A,
          placeholder = 0xFF2A2C31,
        ),
      shape = HaCardShapeStyle(cornerDp = 24, borderDp = 0, controlCornerDp = 20),
      visuals = HaVisualFamily.Expressive,
      fontFamily = RemoteFontFamily.SansSerif,
      valueWeight = RemoteFontWeight.Medium,
      motion = RcMotionScheme.expressive(),
      grammar =
        HaComponentGrammar(
          glow = true,
          gradient = listOf(Color(0xFF4C8DF6), Color(0xFF9A6FE0), Color(0xFFE0727F)),
        ),
      // A kit can borrow a bespoke family's control: Material 3 Expressive's switch.
      components =
        HaComponentSlots.Grammar.with(HaComponentSlot.Switch, FamilyControls.ExpressiveSwitch)
          .with(HaComponentSlot.IconChip, FamilyControls.ExpressiveIconChip),
      extends = "expressive",
    )

  /**
   * **Engineered** — an engineered appliance's display: nickel and iron, a fuchsia accent, square
   * machined corners and levels as rings of fine segments.
   */
  val Engineered =
    HaUberTheme(
      id = "engineered",
      displayName = "Engineered",
      description = "Nickel and iron · fine segment rings and a fuchsia accent",
      light =
        palette(
          dark = false,
          card = 0xFFF4F4F2,
          dashboard = 0xFFD9D9D6,
          primary = 0xFF1D1D1F,
          secondary = 0xFF55565A,
          link = 0xFFB0257A,
          divider = 0xFFC6C6C2,
          placeholder = 0xFFE6E6E3,
        ),
      dark =
        palette(
          dark = true,
          card = 0xFF1C1D20,
          dashboard = 0xFF101113,
          primary = 0xFFECECEE,
          secondary = 0xFFA4A6AB,
          link = 0xFFF06AB8,
          divider = 0xFF303237,
          placeholder = 0xFF26282C,
        ),
      shape = HaCardShapeStyle(cornerDp = 8, borderDp = 1, controlCornerDp = 4, squareKnob = true),
      visuals = HaVisualFamily.Braun,
      fontFamily = RemoteFontFamily.SansSerif,
      letterSpacingSp = 0.3f,
      valueWeight = RemoteFontWeight.Medium,
      motion = HaMotion.Instrument,
      grammar =
        HaComponentGrammar(
          stroke = HaStrokeWeight.Light,
          roundCaps = false,
          level = HaLevelStyle.Segmented,
          segments = 24,
          outline = true,
        ),
      components = HaComponentSlots.Grammar,
      extends = "braun",
    )

  /**
   * **Cockpit** — a car's digital instrument cluster at night: deep blue-black, cyan light, and
   * needles over tick scales. Dark only.
   */
  val Cockpit =
    HaUberTheme(
      id = "cockpit",
      displayName = "Cockpit",
      description = "Night instrument cluster · glowing needles over tick scales",
      light = null,
      dark =
        palette(
          dark = true,
          card = 0xFF0E1726,
          dashboard = 0xFF070C14,
          primary = 0xFFEAF2FF,
          secondary = 0xFF8FA3BF,
          link = 0xFF3FB7FF,
          divider = 0xFF1E2C42,
          placeholder = 0xFF142036,
        ),
      shape = HaCardShapeStyle(cornerDp = 10, borderDp = 1, controlCornerDp = 6),
      visuals = HaVisualFamily.Trinitron,
      fontFamily = RemoteFontFamily.SansSerif,
      letterSpacingSp = 0.4f,
      valueWeight = RemoteFontWeight.Normal,
      motion = HaMotion.Instrument,
      grammar =
        HaComponentGrammar(
          roundCaps = false,
          level = HaLevelStyle.Pointer,
          ticks = 12,
          glow = true,
        ),
      components = HaComponentSlots.Grammar,
      extends = "trinitron",
    )

  /**
   * **Rugged Digital** — a rugged digital sports watch: big segment numerals with their unlit
   * segments ghosted behind them, readings set in recessed screens, square-shouldered complication
   * tiles on a dark dial, and one cyan accent like a small seconds readout. By day it is the
   * positive display, black segments on pale grey. A one-off made with Greg Bullock, designer of
   * the GShark watch face it is drawn from, so a large clock carries the face's G-SPOT wordmark.
   */
  val RuggedDigital =
    HaUberTheme(
      id = "rugged",
      displayName = "Rugged Digital",
      description = "Rugged sports-watch display · ghosted segments in recessed screens",
      light =
        palette(
          dark = false,
          card = 0xFFD4D7D7,
          dashboard = 0xFFAFB4B6,
          primary = 0xFF0A0C0E,
          secondary = 0xFF33383D,
          link = 0xFF00657D,
          divider = 0xFFB9BEC0,
          placeholder = 0xFFC3C7C8,
        ),
      dark =
        palette(
          dark = true,
          card = 0xFF1A1D21,
          dashboard = 0xFF0B0C0E,
          primary = 0xFFF4F6F8,
          secondary = 0xFF9AA2AC,
          link = 0xFF1EC8F0,
          divider = 0xFF2C3036,
          placeholder = 0xFF24282D,
        ),
      shape =
        HaCardShapeStyle(
          cornerDp = 10,
          borderDp = 1,
          controlCornerDp = 4,
          squareKnob = true,
          material = HaSurfaceMaterial.MeshPlate,
        ),
      visuals = HaVisualFamily.Lcd,
      readout = HaReadoutStyle.Screen,
      fontFamily = RemoteFontFamily.SansSerif,
      headingFont = HaGSpotFont,
      valueWeight = RemoteFontWeight.Bold,
      motion = HaMotion.Instrument,
      grammar =
        HaComponentGrammar(
          stroke = HaStrokeWeight.Bold,
          roundCaps = false,
          level = HaLevelStyle.Segmented,
          segments = 12,
          ticks = 12,
          outline = true,
        ),
      // The LCD's layouts, with the watch's own clock face: italic segment digits in a window.
      components =
        HaComponentSlots.Grammar.with(
            HaComponentSlot.Layouts,
            familyLayouts(HaVisualFamily.Lcd)
              .copy(clock = HaClockFace.LcdWindow, glanceComplication = true, screenChamber = true),
          )
          // Keys are the watch's complication windows: square recessed tiles over a mesh.
          .with(HaComponentSlot.Key, LcdWindowKey)
          // On the watch the container is the face itself: plate, brushed sheen and steel bezel.
          .with(HaComponentSlot.WearContainer, RuggedWearContainer),
      extends = "lcd",
    )

  private fun palette(
    dark: Boolean,
    card: Long,
    dashboard: Long,
    primary: Long,
    secondary: Long,
    link: Long,
    divider: Long,
    placeholder: Long,
  ): HaTheme =
    HaTheme(
      cardBackground = Color(card),
      dashboardBackground = Color(dashboard),
      sectionBackground = Color(dashboard),
      primaryText = Color(primary),
      secondaryText = Color(secondary),
      linkText = Color(link),
      divider = Color(divider),
      placeholderAccent = Color(link),
      placeholderBackground = Color(placeholder),
      unknownAccent = Color(secondary),
      isDark = dark,
    )
}
