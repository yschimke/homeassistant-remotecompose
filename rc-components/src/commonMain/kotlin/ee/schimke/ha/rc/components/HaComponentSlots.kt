@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteDrawScope
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.runtime.Composable

// ---------------------------------------------------------------------------------------------
// Component slots: the parts of a card a theme may draw its own way. A theme supplies drawings
// for the slots it cares about and nothing else; every other slot resolves, in order, to
//
//   1. the theme's own drawing for the slot (HaUberTheme.components), else
//   2. the drawing every theme shares, read from the theme's grammar (GrammarMeters.kt) —
//      except for the visuals that predate slots (arc, bar, thermometer, battery), whose
//      fallback is still the bespoke family's drawing in Meters<Family>.kt.
//
// So a new component is one grammar drawing plus one slot, and it works under every theme at once;
// a new theme is tokens plus the overrides that make it itself. Neither multiplies by the other.
// See docs/architecture/theme-kits.md.
// ---------------------------------------------------------------------------------------------

/** A part of a card a theme can draw its own way. */
enum class HaComponentSlot {
  /** The half-circle dial of [RemoteHaArcMeter]. */
  ArcMeter,
  /** The straight meter of [RemoteHaBarMeter]. */
  BarMeter,
  /** [RemoteHaThermometerGlyph]. */
  Thermometer,
  /** [RemoteHaBatteryGlyph]. */
  Battery,
  /** [RemoteHaLockGlyph]: a padlock, its shackle and bolt placed by how locked it is. */
  Lock,
}

/** What a slot's drawing is handed: the theme's colours and grammar, and the live value. */
internal class HaVisualSpec(
  val look: MeterLook,
  /** The live `0..1` value. */
  val fraction: RemoteFloat,
  val grammar: HaComponentGrammar,
  val zones: HaMeterZones = HaMeterZones(),
  /** A dial's stroke at the cards' regular weight, in document pixels. */
  val strokePx: Float = 8f,
  /** A dial's base, as a fraction of the canvas height. */
  val baseline: Float = 1f,
  /** A bar's direction. */
  val vertical: Boolean = false,
)

/** One slot's drawing. It fills the canvas it is played in, sized from that canvas at playback. */
internal fun interface HaVisualRenderer {
  fun RemoteDrawScope.render(spec: HaVisualSpec)
}

/**
 * The drawings a theme supplies for its slots. Immutable; combine with [plus] (the right side wins)
 * and add one with [with].
 */
class HaComponentSlots
internal constructor(private val renderers: Map<HaComponentSlot, HaVisualRenderer>) {

  /** The slots this set draws. */
  val slots: Set<HaComponentSlot>
    get() = renderers.keys

  internal operator fun get(slot: HaComponentSlot): HaVisualRenderer? = renderers[slot]

  internal fun with(slot: HaComponentSlot, renderer: HaVisualRenderer): HaComponentSlots =
    HaComponentSlots(renderers + (slot to renderer))

  /** These slots, with [other]'s drawings replacing any they share. */
  operator fun plus(other: HaComponentSlots): HaComponentSlots =
    HaComponentSlots(renderers + other.renderers)

  override fun equals(other: Any?): Boolean =
    other is HaComponentSlots && other.renderers == renderers

  override fun hashCode(): Int = renderers.hashCode()

  override fun toString(): String = "HaComponentSlots(${renderers.keys})"

  companion object {
    /** No drawings of its own: every slot resolves to its fallback. */
    val None = HaComponentSlots(emptyMap())

    /**
     * Every slot drawn from the theme's grammar, the older visuals included. The base of a theme
     * kit — a theme with no bespoke family of its own — which then overrides what it wants.
     */
    val Grammar =
      HaComponentSlots(
        mapOf(
          HaComponentSlot.ArcMeter to GrammarRenderers.Arc,
          HaComponentSlot.BarMeter to GrammarRenderers.Bar,
          HaComponentSlot.Thermometer to GrammarRenderers.Thermometer,
          HaComponentSlot.Battery to GrammarRenderers.Battery,
          HaComponentSlot.Lock to GrammarRenderers.Lock,
        )
      )
  }
}

/** The active theme's own drawing for [slot], or null to fall back. */
@Composable
internal fun themedRenderer(slot: HaComponentSlot): HaVisualRenderer? =
  currentHaUberTheme().theme.components[slot]

/** The active theme's grammar. */
@Composable internal fun haGrammar(): HaComponentGrammar = currentHaUberTheme().theme.grammar

/** Draws [spec] with [renderer] in this scope. */
internal fun RemoteDrawScope.render(renderer: HaVisualRenderer, spec: HaVisualSpec) {
  with(renderer) { render(spec) }
}
