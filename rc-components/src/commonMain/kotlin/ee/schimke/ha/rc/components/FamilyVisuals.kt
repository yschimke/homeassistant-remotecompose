@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.runtime.Composable

// ---------------------------------------------------------------------------------------------
// The bespoke families' own drawings of the visuals that predate slots — meters and the control
// dial's ring — as one table. A part asks [familyVisuals] for its family's renderer only when the
// theme has no drawing of its own for the slot (HaComponentSlots); the drawings themselves live in
// Meters<Family>.kt and Dials.kt, unchanged.
// ---------------------------------------------------------------------------------------------

/** How a family draws a control dial's ring. */
internal enum class HaDialRingKind {
  /** The cards' own arc, drawn by [RemoteHaArcDial]. */
  Standard,
  /** A rotary knob inside a lamp ring (Console, MetersConsole.kt). */
  Knob,
  /** The family's own ring in Dials.kt ([familyDial]). */
  Family,
}

/** A bespoke family's own visuals. */
internal class FamilyVisuals private constructor(val family: HaVisualFamily) {
  /** The half-circle dial of [RemoteHaArcMeter]. */
  val arc = HaVisualRenderer { spec ->
    val look = spec.look
    val fraction = spec.fraction
    val zones = spec.zones
    when (family) {
      HaVisualFamily.Classic -> classicArc(look, fraction, spec.strokePx, spec.baseline)
      HaVisualFamily.HomeAssistant -> homeAssistantArc(look, fraction, spec.baseline)
      HaVisualFamily.Expressive -> expressiveArc(look, fraction, spec.strokePx, spec.baseline)
      HaVisualFamily.Trinitron -> trinitronArc(look, fraction, spec.baseline, zones)
      HaVisualFamily.Lcd -> lcdArc(look, fraction, spec.strokePx, spec.baseline, zones)
      HaVisualFamily.Braun -> braunArc(look, fraction, spec.baseline)
      HaVisualFamily.Nixie -> nixieArc(look, fraction, spec.strokePx, spec.baseline)
      HaVisualFamily.Console -> consoleArc(look, fraction, spec.baseline, zones)
      HaVisualFamily.Ikea -> ikeaArc(look, fraction, spec.baseline)
      HaVisualFamily.Mushroom,
      HaVisualFamily.Bubble,
      HaVisualFamily.Minimalist,
      HaVisualFamily.Cupertino,
      HaVisualFamily.Catppuccin,
      HaVisualFamily.Obsidian,
      HaVisualFamily.Noctis,
      HaVisualFamily.Lcars,
      HaVisualFamily.Arctic,
      HaVisualFamily.Aeon ->
        communityArc(family, look, fraction, spec.strokePx, spec.baseline, zones)
    }
  }

  /** The straight meter of [RemoteHaBarMeter]. */
  val bar = HaVisualRenderer { spec ->
    val look = spec.look
    val fraction = spec.fraction
    val zones = spec.zones
    when (family) {
      HaVisualFamily.HomeAssistant,
      HaVisualFamily.Classic -> classicBar(look, fraction, spec.vertical)
      HaVisualFamily.Expressive -> expressiveBar(look, fraction, spec.vertical)
      HaVisualFamily.Trinitron -> trinitronBar(look, fraction, spec.vertical, zones)
      HaVisualFamily.Lcd -> lcdBar(look, fraction, spec.vertical, zones)
      HaVisualFamily.Braun -> braunBar(look, fraction, spec.vertical)
      HaVisualFamily.Nixie -> nixieBar(look, fraction, spec.vertical)
      HaVisualFamily.Console -> consoleBar(look, fraction, spec.vertical)
      HaVisualFamily.Ikea -> ikeaBar(look, fraction, spec.vertical)
      HaVisualFamily.Mushroom,
      HaVisualFamily.Bubble,
      HaVisualFamily.Minimalist,
      HaVisualFamily.Cupertino,
      HaVisualFamily.Catppuccin,
      HaVisualFamily.Obsidian,
      HaVisualFamily.Noctis,
      HaVisualFamily.Lcars,
      HaVisualFamily.Arctic,
      HaVisualFamily.Aeon -> communityBar(family, look, fraction, spec.vertical, zones)
    }
  }

  /** [RemoteHaThermometerGlyph]. */
  val thermometer = HaVisualRenderer { spec ->
    val look = spec.look
    val fraction = spec.fraction
    val zones = spec.zones
    when (family) {
      HaVisualFamily.HomeAssistant,
      HaVisualFamily.Classic -> classicThermometer(look, fraction)
      HaVisualFamily.Expressive -> expressiveThermometer(look, fraction)
      HaVisualFamily.Trinitron -> trinitronThermometer(look, fraction, zones)
      HaVisualFamily.Lcd -> lcdThermometer(look, fraction, zones)
      HaVisualFamily.Braun -> braunThermometer(look, fraction, zones)
      HaVisualFamily.Nixie -> nixieThermometer(look, fraction)
      HaVisualFamily.Console -> consoleThermometer(look, fraction, zones)
      HaVisualFamily.Ikea -> ikeaThermometer(look, fraction, zones)
      HaVisualFamily.Mushroom,
      HaVisualFamily.Bubble,
      HaVisualFamily.Minimalist,
      HaVisualFamily.Cupertino,
      HaVisualFamily.Catppuccin,
      HaVisualFamily.Obsidian,
      HaVisualFamily.Noctis,
      HaVisualFamily.Lcars,
      HaVisualFamily.Arctic,
      HaVisualFamily.Aeon -> communityThermometer(family, look, fraction, zones)
    }
  }

  /** [RemoteHaBatteryGlyph]. */
  val battery = HaVisualRenderer { spec ->
    val look = spec.look
    val fraction = spec.fraction
    val zones = spec.zones
    when (family) {
      HaVisualFamily.HomeAssistant,
      HaVisualFamily.Classic -> classicBattery(look, fraction)
      HaVisualFamily.Expressive -> expressiveBattery(look, fraction)
      HaVisualFamily.Trinitron -> trinitronBattery(look, fraction, zones)
      HaVisualFamily.Lcd -> lcdBattery(look, fraction, zones)
      HaVisualFamily.Braun -> braunBattery(look, fraction)
      HaVisualFamily.Nixie -> nixieBattery(look, fraction)
      HaVisualFamily.Console -> consoleBattery(look, fraction, zones)
      HaVisualFamily.Ikea -> ikeaBattery(look, fraction, zones)
      HaVisualFamily.Mushroom,
      HaVisualFamily.Bubble,
      HaVisualFamily.Minimalist,
      HaVisualFamily.Cupertino,
      HaVisualFamily.Catppuccin,
      HaVisualFamily.Obsidian,
      HaVisualFamily.Noctis,
      HaVisualFamily.Lcars,
      HaVisualFamily.Arctic,
      HaVisualFamily.Aeon -> communityBattery(family, look, fraction, zones)
    }
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
      HaVisualFamily.Classic -> HaDialRingKind.Standard
      HaVisualFamily.Console -> HaDialRingKind.Knob
      else -> HaDialRingKind.Family
    }

  companion object {
    private val byFamily = HaVisualFamily.entries.associateWith { FamilyVisuals(it) }

    fun of(family: HaVisualFamily): FamilyVisuals = byFamily.getValue(family)
  }
}

/** The active theme's family visuals. */
@Composable internal fun familyVisuals(): FamilyVisuals = FamilyVisuals.of(visualFamily())
