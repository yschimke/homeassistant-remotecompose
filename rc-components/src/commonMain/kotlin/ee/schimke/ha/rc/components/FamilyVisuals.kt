@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.runtime.Composable

// ---------------------------------------------------------------------------------------------
// The bespoke families' visuals that predate slots — meters and the control dial's ring — as one
// table. A part asks [familyVisuals] for its family's renderer only when the theme has no drawing
// of
// its own for the slot (HaComponentSlots). Each entry is the grammar, or the family's hand drawing
// in Meters<Family>.kt and Dials.kt where it has something the grammar cannot say.
// ---------------------------------------------------------------------------------------------

/** How a family draws a control dial's ring. */
internal enum class HaDialRingKind {
  /** The grammar's ring ([GrammarRenderers.Dial]), in the family's tokens. */
  Grammar,
  /** A rotary knob inside a lamp ring (Console, MetersConsole.kt). */
  Knob,
  /** The family's own ring in Dials.kt ([familyDial]). */
  Family,
}

/**
 * A bespoke family's own visuals: its hand drawing where it has something to say, else the grammar
 * ([GrammarRenderers]) drawn with the family's [HaComponentGrammar.forFamily] tokens. A family
 * keeps a drawing only for what the grammar cannot express — Home Assistant's chunky half gauge, an
 * LCD's framed segment columns, a Nixie tube — so the cards' own style (Classic) and the styles the
 * grammar was modelled on draw from the same code as every theme kit.
 */
internal class FamilyVisuals private constructor(val family: HaVisualFamily) {
  /** The half-circle dial of [RemoteHaArcMeter]. */
  val arc: HaVisualRenderer =
    when (family) {
      HaVisualFamily.Classic,
      HaVisualFamily.Expressive,
      HaVisualFamily.Lcd,
      HaVisualFamily.Minimalist,
      HaVisualFamily.Arctic -> GrammarRenderers.Arc
      HaVisualFamily.HomeAssistant -> drawn { homeAssistantArc(it.look, it.fraction, it.baseline) }
      HaVisualFamily.Trinitron ->
        drawn { trinitronArc(it.look, it.fraction, it.baseline, it.zones) }
      HaVisualFamily.Braun -> drawn { braunArc(it.look, it.fraction, it.baseline) }
      HaVisualFamily.Nixie -> drawn { nixieArc(it.look, it.fraction, it.strokePx, it.baseline) }
      HaVisualFamily.Console -> drawn { consoleArc(it.look, it.fraction, it.baseline, it.zones) }
      HaVisualFamily.Ikea -> drawn { ikeaArc(it.look, it.fraction, it.baseline) }
      HaVisualFamily.Mushroom,
      HaVisualFamily.Bubble,
      HaVisualFamily.Cupertino,
      HaVisualFamily.Catppuccin,
      HaVisualFamily.Obsidian,
      HaVisualFamily.Noctis,
      HaVisualFamily.Lcars,
      HaVisualFamily.Aeon ->
        drawn { communityArc(family, it.look, it.fraction, it.strokePx, it.baseline, it.zones) }
    }

  /** The straight meter of [RemoteHaBarMeter]. */
  val bar: HaVisualRenderer =
    when (family) {
      HaVisualFamily.Classic,
      HaVisualFamily.HomeAssistant,
      HaVisualFamily.Expressive,
      HaVisualFamily.Trinitron,
      HaVisualFamily.Lcd,
      HaVisualFamily.Cupertino,
      HaVisualFamily.Obsidian,
      HaVisualFamily.Arctic -> GrammarRenderers.Bar
      HaVisualFamily.Braun -> drawn { braunBar(it.look, it.fraction, it.vertical) }
      HaVisualFamily.Nixie -> drawn { nixieBar(it.look, it.fraction, it.vertical) }
      HaVisualFamily.Console -> drawn { consoleBar(it.look, it.fraction, it.vertical) }
      HaVisualFamily.Ikea -> drawn { ikeaBar(it.look, it.fraction, it.vertical) }
      HaVisualFamily.Mushroom,
      HaVisualFamily.Bubble,
      HaVisualFamily.Minimalist,
      HaVisualFamily.Catppuccin,
      HaVisualFamily.Noctis,
      HaVisualFamily.Lcars,
      HaVisualFamily.Aeon ->
        drawn { communityBar(family, it.look, it.fraction, it.vertical, it.zones) }
    }

  /** [RemoteHaThermometerGlyph]. */
  val thermometer: HaVisualRenderer =
    when (family) {
      HaVisualFamily.Classic,
      HaVisualFamily.HomeAssistant,
      HaVisualFamily.Expressive,
      HaVisualFamily.Obsidian,
      HaVisualFamily.Arctic -> GrammarRenderers.Thermometer
      HaVisualFamily.Trinitron -> drawn { trinitronThermometer(it.look, it.fraction, it.zones) }
      HaVisualFamily.Lcd -> drawn { lcdThermometer(it.look, it.fraction, it.zones) }
      HaVisualFamily.Braun -> drawn { braunThermometer(it.look, it.fraction, it.zones) }
      HaVisualFamily.Nixie -> drawn { nixieThermometer(it.look, it.fraction) }
      HaVisualFamily.Console -> drawn { consoleThermometer(it.look, it.fraction, it.zones) }
      HaVisualFamily.Ikea -> drawn { ikeaThermometer(it.look, it.fraction, it.zones) }
      HaVisualFamily.Mushroom,
      HaVisualFamily.Bubble,
      HaVisualFamily.Minimalist,
      HaVisualFamily.Cupertino,
      HaVisualFamily.Catppuccin,
      HaVisualFamily.Noctis,
      HaVisualFamily.Lcars,
      HaVisualFamily.Aeon -> drawn { communityThermometer(family, it.look, it.fraction, it.zones) }
    }

  /** [RemoteHaBatteryGlyph]. */
  val battery: HaVisualRenderer =
    when (family) {
      HaVisualFamily.Classic,
      HaVisualFamily.HomeAssistant,
      HaVisualFamily.Expressive -> GrammarRenderers.Battery
      HaVisualFamily.Trinitron -> drawn { trinitronBattery(it.look, it.fraction, it.zones) }
      HaVisualFamily.Lcd -> drawn { lcdBattery(it.look, it.fraction, it.zones) }
      HaVisualFamily.Braun -> drawn { braunBattery(it.look, it.fraction) }
      HaVisualFamily.Nixie -> drawn { nixieBattery(it.look, it.fraction) }
      HaVisualFamily.Console -> drawn { consoleBattery(it.look, it.fraction, it.zones) }
      HaVisualFamily.Ikea -> drawn { ikeaBattery(it.look, it.fraction, it.zones) }
      HaVisualFamily.Mushroom,
      HaVisualFamily.Bubble,
      HaVisualFamily.Minimalist,
      HaVisualFamily.Cupertino,
      HaVisualFamily.Catppuccin,
      HaVisualFamily.Obsidian,
      HaVisualFamily.Noctis,
      HaVisualFamily.Lcars,
      HaVisualFamily.Arctic,
      HaVisualFamily.Aeon -> drawn { communityBattery(family, it.look, it.fraction, it.zones) }
    }

  /**
   * The arc meter's track: Home Assistant's gauge sits on the dashboard colour, else the divider.
   */
  @Composable
  fun arcTrack(): androidx.compose.remote.creation.compose.state.RemoteColor? =
    if (family == HaVisualFamily.HomeAssistant) haTheme().dashboardBackground else null

  /** How the family draws a control dial's ring. */
  val dialRing: HaDialRingKind =
    when (family) {
      HaVisualFamily.Classic,
      HaVisualFamily.Expressive,
      HaVisualFamily.Lcd,
      HaVisualFamily.Minimalist,
      HaVisualFamily.Obsidian,
      HaVisualFamily.Arctic -> HaDialRingKind.Grammar
      HaVisualFamily.Console -> HaDialRingKind.Knob
      else -> HaDialRingKind.Family
    }

  companion object {
    /** A hand drawing: [draw] with the spec as its argument. */
    private fun drawn(
      draw: androidx.compose.remote.creation.compose.layout.RemoteDrawScope.(HaVisualSpec) -> Unit
    ) = HaVisualRenderer { spec -> draw(spec) }

    private val byFamily = HaVisualFamily.entries.associateWith { FamilyVisuals(it) }

    fun of(family: HaVisualFamily): FamilyVisuals = byFamily.getValue(family)
  }
}

/** The active theme's family visuals. */
@Composable internal fun familyVisuals(): FamilyVisuals = FamilyVisuals.of(visualFamily())
