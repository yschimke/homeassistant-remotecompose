package ee.schimke.ha.rc.components

import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------------------------
// The component grammar: *how* a theme draws any visual, as tokens rather than code. A palette says
// which colours a card uses; the grammar says how heavy its lines are, whether their ends are
// round,
// whether a level is a smooth fill, a row of lit segments or a pointer over a tick scale, and
// whether the value glows or sweeps through a gradient.
//
// Every visual written against the grammar (GrammarMeters.kt) works under every theme the day it
// lands, so a new component — a car's door lock, say — is written once, not once per theme. A
// theme that wants a bespoke drawing of one component overrides that slot (HaComponentSlots.kt).
// See docs/architecture/theme-kits.md.
// ---------------------------------------------------------------------------------------------

/** How heavy a theme's lines and fills are, as a multiple of a component's regular weight. */
enum class HaStrokeWeight(val scale: Float) {
  /** Instrument hairlines: Braun, Trinitron. */
  Hairline(0.35f),
  /** Quiet, thin strokes. */
  Light(0.65f),
  /** The cards' own weight. */
  Regular(1f),
  /** Chunky, toy-like or high-legibility strokes: Material 3 Expressive, an LCD. */
  Bold(1.6f),
}

/** How a theme shows a `0..1` level — a meter's value, a battery's charge, a lock's bolt. */
enum class HaLevelStyle {
  /** A smooth fill that grows with the value. */
  Continuous,
  /** A row of cells, lit once the value passes each cell's midpoint, unlit ones as ghosts. */
  Segmented,
  /** A thin scale with index ticks and a pointer (or needle) placed by the value. */
  Pointer,
}

/**
 * The tokens a grammar-driven visual draws from.
 *
 * @property stroke line and fill weight.
 * @property roundCaps round line ends and fully rounded bars; false for flat, machined ends.
 * @property level how a level is drawn.
 * @property segments the cell count of a [HaLevelStyle.Segmented] level (a component may use fewer
 *   when it is small, a battery four).
 * @property ticks index ticks on a scale; 0 for none.
 * @property trackGap a gap between the value and the rest of its track, as Material 3 Expressive's
 *   progress indicators draw.
 * @property stopDot a dot at the far end of the track, marking where the scale ends.
 * @property glow a soft halo under the value: the theme's ink glow, else the value at low alpha.
 * @property gradient colours a faint halo sweeps through under the value, along its scale — a brand
 *   moment. The value itself stays in its own colour over it, so data colours keep their meaning
 *   (rule 2): a locked lock stays green, a severity band red. Ignored under an inked (monochrome)
 *   theme, whose ink wins.
 * @property outline draw bodies (a battery's, a lock's) as outlines rather than solid shapes.
 */
data class HaComponentGrammar(
  val stroke: HaStrokeWeight = HaStrokeWeight.Regular,
  val roundCaps: Boolean = true,
  val level: HaLevelStyle = HaLevelStyle.Continuous,
  val segments: Int = 10,
  val ticks: Int = 0,
  val trackGap: Boolean = false,
  val stopDot: Boolean = false,
  val glow: Boolean = false,
  val gradient: List<Color>? = null,
  val outline: Boolean = false,
) {
  init {
    require(segments in 2..48) { "segments must be 2..48, was $segments" }
    require(ticks in 0..48) { "ticks must be 0..48, was $ticks" }
    require(gradient == null || gradient.size >= 2) { "a gradient needs two colours or more" }
  }

  companion object {
    /** The cards' own grammar: regular weight, round ends, a smooth fill. */
    val Default = HaComponentGrammar()

    /**
     * The grammar a bespoke family's hand-drawn visuals follow, so a component that family has no
     * drawing of yet (a lock, say) still reads as part of it: segmented on an LCD, a pointer on a
     * Braun scale, a fat capsule with a stop dot under Expressive.
     */
    fun forFamily(family: HaVisualFamily): HaComponentGrammar =
      when (family) {
        HaVisualFamily.Classic -> Default
        HaVisualFamily.HomeAssistant -> HaComponentGrammar(roundCaps = false)
        HaVisualFamily.Expressive ->
          HaComponentGrammar(stroke = HaStrokeWeight.Bold, trackGap = true, stopDot = true)
        HaVisualFamily.Trinitron ->
          HaComponentGrammar(
            stroke = HaStrokeWeight.Hairline,
            roundCaps = false,
            level = HaLevelStyle.Segmented,
            segments = 20,
            ticks = 10,
            outline = true,
          )
        HaVisualFamily.Lcd ->
          HaComponentGrammar(
            stroke = HaStrokeWeight.Bold,
            roundCaps = false,
            level = HaLevelStyle.Segmented,
            segments = 10,
            outline = true,
          )
        HaVisualFamily.Braun ->
          HaComponentGrammar(
            stroke = HaStrokeWeight.Hairline,
            level = HaLevelStyle.Pointer,
            ticks = 10,
            outline = true,
          )
        HaVisualFamily.Nixie -> HaComponentGrammar(glow = true, outline = true)
        HaVisualFamily.Console ->
          HaComponentGrammar(roundCaps = false, level = HaLevelStyle.Pointer, ticks = 10)
        HaVisualFamily.Ikea -> HaComponentGrammar(stroke = HaStrokeWeight.Bold, roundCaps = false)
        HaVisualFamily.Mushroom -> HaComponentGrammar(level = HaLevelStyle.Segmented, segments = 13)
        HaVisualFamily.Bubble ->
          HaComponentGrammar(
            stroke = HaStrokeWeight.Bold,
            level = HaLevelStyle.Segmented,
            segments = 9,
          )
        HaVisualFamily.Minimalist ->
          HaComponentGrammar(
            stroke = HaStrokeWeight.Light,
            level = HaLevelStyle.Segmented,
            segments = 5,
          )
        HaVisualFamily.Cupertino -> HaComponentGrammar(stroke = HaStrokeWeight.Bold)
        HaVisualFamily.Catppuccin -> HaComponentGrammar(stopDot = true)
        HaVisualFamily.Obsidian ->
          HaComponentGrammar(stroke = HaStrokeWeight.Light, roundCaps = false, ticks = 10)
        HaVisualFamily.Noctis -> HaComponentGrammar(stroke = HaStrokeWeight.Light)
        HaVisualFamily.Lcars ->
          HaComponentGrammar(
            stroke = HaStrokeWeight.Bold,
            roundCaps = false,
            level = HaLevelStyle.Segmented,
            segments = 8,
          )
        HaVisualFamily.Arctic ->
          HaComponentGrammar(stroke = HaStrokeWeight.Hairline, level = HaLevelStyle.Pointer)
        HaVisualFamily.Aeon -> HaComponentGrammar(stroke = HaStrokeWeight.Light, glow = true)
      }
  }
}
