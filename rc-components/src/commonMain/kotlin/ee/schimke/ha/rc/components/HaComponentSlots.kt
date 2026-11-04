@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteDrawScope
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.RemoteBoolean
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
  /**
   * The 270° ring of a control dial ([RemoteHaArcDial]: thermostat, humidifier, light): its track,
   * its value and the target marker, all moved by live fractions.
   */
  DialRing,
  /**
   * How a control dial is laid out: which body it shows (the ring, or a family's own instrument)
   * and where its steppers sit.
   */
  DialLayout,
  /**
   * A media transport key (previous, play / pause, next) — its face, lit while its command is
   * available and marked while it is the active one.
   */
  TransportKey,
  /**
   * A button card's icon chip: the round face behind the icon, which may react to the entity being
   * on and to a press.
   */
  IconChip,
  /**
   * The Wear widget container a card plays in on the watch: its fill, the layers and edge that
   * carry the style's identity, and how a level reading shows in it ([HaWearBackground],
   * [HaWearLevelLook]).
   */
  WearContainer,
  /**
   * How a style arranges and frames cards ([HaLayouts]): tile composition and chip, media panel and
   * artwork, reading layouts, the unavailable label, the card frame. Not in
   * [HaComponentSlots.Grammar]: a kit keeps the arrangements of the style it extends unless it
   * picks its own.
   */
  Layouts,
  /** Graph rows: a history sparkline and a statistics bar row ([HaChartRenderer]). */
  Charts,
  /** The heading card, a markdown note's material and its title ([HaTextSurfaceRenderer]). */
  TextSurfaces,
}

/** Something that draws one slot; each slot takes one kind. */
internal sealed interface HaSlotRenderer

/** The kind of renderer [this] slot takes. */
internal fun HaComponentSlot.accepts(renderer: HaSlotRenderer): Boolean =
  when (this) {
    HaComponentSlot.Switch -> renderer is HaSwitchRenderer
    HaComponentSlot.Key -> renderer is HaKeyRenderer
    HaComponentSlot.DialRing -> renderer is HaDialRenderer
    HaComponentSlot.DialLayout -> renderer is HaDialLayoutRenderer
    HaComponentSlot.TransportKey -> renderer is HaTransportKeyRenderer
    HaComponentSlot.IconChip -> renderer is HaIconChipRenderer
    HaComponentSlot.WearContainer -> renderer is HaWearContainerRenderer
    HaComponentSlot.Layouts -> renderer is HaLayouts
    HaComponentSlot.Charts -> renderer is HaChartRenderer
    HaComponentSlot.TextSurfaces -> renderer is HaTextSurfaceRenderer
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
 * What a dial ring is handed: the theme's colours and grammar with the live value fraction
 * ([visual]), the live target fraction and whether to show it, the square to draw in, and the
 * cards' regular stroke for the size, in document pixels.
 */
internal class HaDialSpec(
  val visual: HaVisualSpec,
  val target: RemoteFloat?,
  val targetVisible: RemoteBoolean?,
  val box: DialBox,
  val stroke: Float,
)

/** Draws a control dial's 270° ring, opening at 135° (bottom left) clockwise to 45°. */
internal fun interface HaDialRenderer : HaSlotRenderer {
  fun RemoteDrawScope.render(spec: HaDialSpec)
}

/** The body a control dial shows. */
internal enum class HaDialBody {
  /** The ring ([HaComponentSlot.DialRing]) with the readout in its centre. */
  Ring,
  /** Console: a room display beside a separate set-point knob. */
  ConsoleThermostat,
  /** Cupertino: a filled bar in place of a ring. */
  CupertinoBar,
  /** IKEA: a light's moulded remote. */
  IkeaLight,
}

/**
 * How a control dial is laid out: the [body] it shows for a card (a family's own instrument for the
 * cards it suits, else the ring), and whether its steppers sit [beside][sideSteppers] the body
 * rather than under it.
 */
internal interface HaDialLayoutRenderer : HaSlotRenderer {
  fun body(centerLabelAttribute: String?): HaDialBody

  val sideSteppers: Boolean
}

/**
 * A media transport key: [icon] on a face [sizeDp] square, the icon [iconDp], in the marking colour
 * [accent] — a capture-time colour, made legible or inked by the renderer ([haInk]).
 */
internal class HaTransportSpec(
  val icon: androidx.compose.ui.graphics.vector.ImageVector,
  val accent: androidx.compose.ui.graphics.Color,
  val sizeDp: Int,
  val iconDp: Int,
)

/**
 * Draws media transport keys. Each sits inside the caller's touch target, which owns the action.
 */
internal interface HaTransportKeyRenderer : HaSlotRenderer {
  /** A transport key (previous, play / pause, next) inside its touch target. */
  @Composable fun Key(spec: HaTransportSpec)

  /**
   * A compact, non-clickable play / pause glyph — a glance target in a small cell, whose tap opens
   * the full card.
   */
  @Composable
  fun Glyph(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: androidx.compose.ui.graphics.Color,
    contentDescription: androidx.compose.remote.creation.compose.state.RemoteString,
  )
}

/**
 * What a button's icon chip is handed: the entity's [look] (its live on / off), the live [accent],
 * the chip's size, and the press fraction, 0 unless the renderer is [HaIconChipRenderer.pressable].
 */
internal class HaIconChipSpec(
  val look: HaEntityLook,
  val accent: RemoteColor,
  val chipDp: Int,
  val pressed: RemoteFloat,
)

/**
 * Draws a button's icon chip around [icon]. The icon is handed `onFace = true` when it sits on a
 * key face (a Console key), so it takes the face's ink and leaves room for the pilot lamp.
 */
internal interface HaIconChipRenderer : HaSlotRenderer {
  /**
   * Whether the chip reacts to a press, so the card should track one ([HaIconChipSpec.pressed]).
   */
  val pressable: Boolean
    get() = false

  @Composable fun Chip(spec: HaIconChipSpec, icon: @Composable (onFace: Boolean) -> Unit)
}

/**
 * A style's Wear container as data: the watch's widget host draws it at its own shape (round or
 * square, its own corner) from these, so it holds colours and proportions, not geometry.
 */
internal interface HaWearContainerRenderer : HaSlotRenderer {
  /** The container's fill, layers and edge for [theme] (dark: the watch is dark). */
  fun background(theme: HaUberTheme): HaWearBackground

  /** How a level reading ([HaWearLevel]) shows in the container for [theme]. */
  fun levelLook(theme: HaUberTheme): HaWearLevelLook
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

  internal val dial: HaDialRenderer?
    get() = renderers[HaComponentSlot.DialRing] as? HaDialRenderer

  internal val dialLayout: HaDialLayoutRenderer?
    get() = renderers[HaComponentSlot.DialLayout] as? HaDialLayoutRenderer

  internal val transportKey: HaTransportKeyRenderer?
    get() = renderers[HaComponentSlot.TransportKey] as? HaTransportKeyRenderer

  internal val iconChip: HaIconChipRenderer?
    get() = renderers[HaComponentSlot.IconChip] as? HaIconChipRenderer

  internal val wearContainer: HaWearContainerRenderer?
    get() = renderers[HaComponentSlot.WearContainer] as? HaWearContainerRenderer

  internal val textSurfaces: HaTextSurfaceRenderer?
    get() = renderers[HaComponentSlot.TextSurfaces] as? HaTextSurfaceRenderer

  internal val charts: HaChartRenderer?
    get() = renderers[HaComponentSlot.Charts] as? HaChartRenderer

  internal val layouts: HaLayouts?
    get() = renderers[HaComponentSlot.Layouts] as? HaLayouts

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
          HaComponentSlot.DialRing to GrammarRenderers.Dial,
          HaComponentSlot.DialLayout to StandardDialLayout,
          HaComponentSlot.TransportKey to TokenTransportKey,
          HaComponentSlot.IconChip to TokenIconChip,
          HaComponentSlot.WearContainer to GrammarWearContainer,
          HaComponentSlot.Charts to GrammarCharts,
          HaComponentSlot.TextSurfaces to GrammarTextSurfaces,
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

/** Draws a dial [spec] with [renderer] in this scope. */
internal fun RemoteDrawScope.render(renderer: HaDialRenderer, spec: HaDialSpec) {
  with(renderer) { render(spec) }
}

/** Draws [spec] with [renderer] in this scope. */
internal fun RemoteDrawScope.render(renderer: HaVisualRenderer, spec: HaVisualSpec) {
  with(renderer) { render(spec) }
}
