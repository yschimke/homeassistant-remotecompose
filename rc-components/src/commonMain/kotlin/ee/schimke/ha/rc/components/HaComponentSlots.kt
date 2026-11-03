@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteDrawScope
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.runtime.Composable

// ---------------------------------------------------------------------------------------------
// Component slots: the parts of a card a theme may draw its own way. A theme supplies drawings
// for the slots it cares about and nothing else; every other slot resolves, in order, to
//
//   1. the theme's own drawing for the slot (HaUberTheme.components), else
//   2. for a part that predates slots, the bespoke family's own (Meters<Family>.kt for the meters,
//      HaControls.kt's one family table for the switch and keys), else
//   3. the drawing every theme shares, read from the theme's grammar and tokens.
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
  /** [RemoteHaToggleSwitch]: an on / off control, its knob and colour moved by live progress. */
  Switch,
  /**
   * The mount a key or button face sits in ([haKeyChrome]): a machined key, a moulded paddle, an
   * outlined rim — or nothing, on a flat theme. Applied inside the caller's target, so the caller
   * keeps the action, accessible name and touch area.
   */
  Key,
}

/** Something that draws one slot; each slot takes one kind. */
internal sealed interface HaSlotRenderer

/** The kind of renderer [this] slot takes. */
internal fun HaComponentSlot.accepts(renderer: HaSlotRenderer): Boolean =
  when (this) {
    HaComponentSlot.Switch -> renderer is HaSwitchRenderer
    HaComponentSlot.Key -> renderer is HaKeyRenderer
    else -> renderer is HaVisualRenderer
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

/** One visual slot's drawing. It fills the canvas it is played in, sized from that canvas. */
internal fun interface HaVisualRenderer : HaSlotRenderer {
  fun RemoteDrawScope.render(spec: HaVisualSpec)
}

/**
 * What a switch is handed: live progress for the knob ([progress]) and for the colours
 * ([colorProgress], which a style may animate apart), the accents, and the caller's modifier and
 * click. The renderer sizes the control and applies [clickable] itself, so a face can sit inside
 * the target (a rocker) or be the target (a track).
 */
internal class HaSwitchSpec(
  val progress: RemoteFloat,
  val colorProgress: RemoteFloat,
  val activeAccent: RemoteColor,
  val inactiveAccent: RemoteColor,
  val modifier: RemoteModifier,
  val clickable: RemoteModifier,
)

/** Draws a switch. It owns no state and no action: [HaSwitchSpec] brings both. */
internal interface HaSwitchRenderer : HaSlotRenderer {
  @Composable fun Switch(spec: HaSwitchSpec)
}

/**
 * Draws a key's mount as a modifier on the caller's key. [accent] is a live state or selection
 * colour for the rim, and [selected] a mode key's state (a lamp, a dot), or null for a plain key.
 */
internal interface HaKeyRenderer : HaSlotRenderer {
  @Composable fun chrome(accent: RemoteColor?, selected: Boolean?): RemoteModifier
}

/**
 * The drawings a theme supplies for its slots. Immutable; combine with [plus] (the right side wins)
 * and add one with [with].
 */
class HaComponentSlots
internal constructor(private val renderers: Map<HaComponentSlot, HaSlotRenderer>) {
  init {
    renderers.forEach { (slot, renderer) ->
      require(slot.accepts(renderer)) { "$slot can't take ${renderer::class.simpleName}" }
    }
  }

  /** The slots this set draws. */
  val slots: Set<HaComponentSlot>
    get() = renderers.keys

  internal fun visual(slot: HaComponentSlot): HaVisualRenderer? =
    renderers[slot] as? HaVisualRenderer

  internal val switch: HaSwitchRenderer?
    get() = renderers[HaComponentSlot.Switch] as? HaSwitchRenderer

  internal val key: HaKeyRenderer?
    get() = renderers[HaComponentSlot.Key] as? HaKeyRenderer

  internal fun with(slot: HaComponentSlot, renderer: HaSlotRenderer): HaComponentSlots =
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
          HaComponentSlot.Switch to TokenSwitch,
          HaComponentSlot.Key to GrammarKey,
        )
      )
  }
}

/** The active theme's own drawing for [slot], or null to fall back. */
@Composable
internal fun themedRenderer(slot: HaComponentSlot): HaVisualRenderer? =
  currentHaUberTheme().theme.components.visual(slot)

/** The active theme's grammar. */
@Composable internal fun haGrammar(): HaComponentGrammar = currentHaUberTheme().theme.grammar

/** Draws [spec] with [renderer] in this scope. */
internal fun RemoteDrawScope.render(renderer: HaVisualRenderer, spec: HaVisualSpec) {
  with(renderer) { render(spec) }
}
