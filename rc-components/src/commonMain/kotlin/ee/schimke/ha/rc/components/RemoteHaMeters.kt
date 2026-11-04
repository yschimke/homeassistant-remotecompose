@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteCanvas
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.runtime.Composable

// ---------------------------------------------------------------------------------------------
// Layer 2 of the card data hierarchy: visual meters. Each one draws a normalised `0..1` fraction
// in a colour it is handed, and nothing else — no text, no chrome, no knowledge of entities — so
// the same meter serves a gauge card, a thermometer, a launcher chip or a Wear widget.
//
// Rules every meter follows (card-component-system.md §"Visual meters"):
//
// - **It fits the box it is given**, keeping its own aspect: the drawing is computed from the
//   canvas `width` / `height` at playback, so a meter grows and shrinks with its cell with no
//   re-encode. Size it from outside with the modifier.
// - **The track is the theme's `divider`**, the value is the caller's colour (a [HaMeterColor]
//   expression, so it can change with the live value) — or the theme's ink.
// - **Its design is the active uber theme's [HaVisualFamily]**: each function below dispatches to
//   that family's own drawing (Meters<Family>.kt). Every family stays live: fills and needles move
//   with the fraction, segments light by a select on it.
// ---------------------------------------------------------------------------------------------

/**
 * A half-circle meter — a dial — its base at [baseline] of the canvas height (1 = the bottom edge)
 * and its diameter the largest that fits. Classic sweeps an arc `fraction × 180°`; Trinitron swings
 * a VU needle, Braun a pointer over index ticks, LCD lights blocks, Nixie glows, Console is a
 * backlit VU meter.
 *
 * @param strokeWidthPx the arc's stroke, in document pixels (fixed at capture).
 * @param zones the scale's hot zone, drawn as a red zone or hot segments.
 */
@Composable
@RemoteComposable
fun RemoteHaArcMeter(
  fraction: RemoteFloat,
  color: RemoteColor,
  modifier: RemoteModifier = RemoteModifier,
  strokeWidthPx: Float = 8f,
  baseline: Float = 1f,
  trackColor: RemoteColor? = null,
  zones: HaMeterZones = HaMeterZones(),
) {
  val family = familyVisuals()
  val look =
    meterLook(
      color,
      trackColor ?: family.arcTrack(),
    )
  val themed = themedRenderer(HaComponentSlot.ArcMeter)
  val grammar = haGrammar()
  RemoteCanvas(modifier = modifier) {
    render(
      themed ?: family.arc,
      HaVisualSpec(look, fraction, grammar, zones, strokeWidthPx, baseline),
    )
  }
}

/**
 * A straight meter: a track the full length of the canvas and the value `fraction` of the way along
 * it, horizontal (left to right) or [vertical] (bottom to top). The bar is the canvas's cross-axis
 * thickness, so set that with the modifier (`height(8.rdp)`).
 */
@Composable
@RemoteComposable
fun RemoteHaBarMeter(
  fraction: RemoteFloat,
  color: RemoteColor,
  modifier: RemoteModifier = RemoteModifier,
  vertical: Boolean = false,
  trackColor: RemoteColor? = null,
  zones: HaMeterZones = HaMeterZones(),
) {
  val look = meterLook(color, trackColor)
  val family = familyVisuals()
  val themed = themedRenderer(HaComponentSlot.BarMeter)
  val grammar = haGrammar()
  RemoteCanvas(modifier = modifier) {
    render(themed ?: family.bar, HaVisualSpec(look, fraction, grammar, zones, vertical = vertical))
  }
}

/**
 * A thermometer, fitted and centred in the canvas. Classic is a liquid tube over a bulb; the other
 * families are their own instruments — an LCD segment column, a Trinitron LED level meter, a Braun
 * tuning scale, a Nixie bargraph tube, an Expressive capsule. [zones] marks the hot end.
 */
@Composable
@RemoteComposable
fun RemoteHaThermometerGlyph(
  fraction: RemoteFloat,
  color: RemoteColor,
  modifier: RemoteModifier = RemoteModifier,
  trackColor: RemoteColor? = null,
  zones: HaMeterZones = HaMeterZones(),
) {
  val look = meterLook(color, trackColor)
  val family = familyVisuals()
  val themed = themedRenderer(HaComponentSlot.Thermometer)
  val grammar = haGrammar()
  RemoteCanvas(modifier = modifier) {
    render(themed ?: family.thermometer, HaVisualSpec(look, fraction, grammar, zones))
  }
}

/**
 * A battery, fitted and centred in the canvas, its charge `fraction` of it. [zones]' `lowBelow`
 * marks the critical level.
 */
@Composable
@RemoteComposable
fun RemoteHaBatteryGlyph(
  fraction: RemoteFloat,
  color: RemoteColor,
  modifier: RemoteModifier = RemoteModifier,
  trackColor: RemoteColor? = null,
  zones: HaMeterZones = HaMeterZones(),
) {
  val look = meterLook(color, trackColor)
  val family = familyVisuals()
  val themed = themedRenderer(HaComponentSlot.Battery)
  val grammar = haGrammar()
  RemoteCanvas(modifier = modifier) {
    render(themed ?: family.battery, HaVisualSpec(look, fraction, grammar, zones))
  }
}

/** Height : width of the classic [RemoteHaThermometerGlyph]. */
const val ThermometerAspect = 2.6f

/** Width : height of the classic [RemoteHaBatteryGlyph]. */
const val BatteryAspect = 2f
