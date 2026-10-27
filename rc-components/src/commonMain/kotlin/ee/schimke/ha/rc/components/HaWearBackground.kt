package ee.schimke.ha.rc.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * What a card style paints a Wear widget container with: a [base] fill, [layers] painted over it in
 * order, and an [edge] drawn on top. On the watch the card's own surface is the container's (the
 * card draws no chrome of its own there), so this is what tells one style from another at a glance,
 * not only its typeface.
 *
 * It holds no shape: the container's shape is the watch's widget host's, and stays it on every
 * device. Whoever draws the container draws every layer to fit inside it at the host's own corner
 * radius (the Wear template's named `wear.container.corner_radius`, Glance Wear's
 * `WearWidgetBrush`, or a preview's approximation of either): glows and gradients are its rounded
 * rect, bands and edges follow its outline. So the same background fits a pill, a round watch's
 * 26dp container and a square one's 4dp. Positions are fractions of the container, for the same
 * reason.
 *
 * Every layer kind plays in the Wear widget profile (`WearWidgetProfileCapabilitiesTest`). Glance
 * Wear's own container brush can only paint colours and straight gradients, so the direct capture,
 * the fallback when a card has no template, keeps the [base] and its [HaWearGradient] layers and
 * leaves the rest out.
 *
 * @property base the container's fill: the style's card background. A card's state tint
 *   (`ContainerTint`) is mixed onto it.
 * @property layers what is painted over [base], each over the whole container.
 * @property edge a line just inside the container's outline, or null for none.
 */
data class HaWearBackground(
  val base: Color,
  val layers: List<HaWearLayer> = emptyList(),
  val edge: HaWearEdge? = null,
)

/** Something painted over a [HaWearBackground]'s base, across the whole container. */
sealed interface HaWearLayer

/** A straight gradient across the container: top to bottom when [vertical], else start to end. */
data class HaWearGradient(val colors: List<Color>, val vertical: Boolean = true) : HaWearLayer

/** A gradient from the top start corner to the bottom end corner. */
data class HaWearDiagonal(val colors: List<Color>) : HaWearLayer

/**
 * A round glow: [colors] from the centre at ([x], [y]) outwards to [radius], each a fraction of the
 * container's width (positions may fall outside it, for a glow that rises from an edge).
 */
data class HaWearGlow(
  val colors: List<Color>,
  val x: Float,
  val y: Float,
  val radius: Float,
) : HaWearLayer

/**
 * A band [thicknessDp] wide along one [side] of the container, following its outline: the top or
 * bottom edge from corner to corner, or round the start corners and edge (an LCARS elbow).
 */
data class HaWearBand(val color: Color, val side: Side, val thicknessDp: Float) : HaWearLayer {
  enum class Side {
    Top,
    Bottom,
    Start,
  }
}

/**
 * A line [widthDp] wide following the container's outline, [insetDp] inside it. More than one
 * colour sweeps round the container (a gradient stroke).
 */
data class HaWearEdge(val colors: List<Color>, val widthDp: Float, val insetDp: Float = 0f)

/**
 * How a reading shows in a style's Wear container when the container is a level ([HaWearLevel]), in
 * [color] at [alpha].
 *
 * A ring ([ringDp] above 0) runs the reading round the container, 4dp inside its outline, in the
 * container's padding: it never sits under the card's text, so it is drawn at full strength over a
 * faint track. A fill covers the container up to the reading, under the text, so its [alpha] is the
 * strongest the style allows while its text keeps its contrast on it ([readableAlpha]).
 *
 * @property ringDp the ring's width, or 0 for a fill.
 * @property track whether a ring's track shows the whole way round under its reading.
 * @property pointer whether a ring marks its reading with a short pointer, as a dial does, rather
 *   than sweeping round to it.
 * @property sweep colours a ring's reading sweeps through round the container, in place of [color];
 *   empty for [color] alone.
 * @property segments how many cells a fill is cut into, as an LED column or an LCD bar; 0 for a
 *   continuous fill.
 * @property ghosts whether a fill's unlit cells show faintly, as an LCD's unlit segments do.
 * @property marker whether a fill's leading edge is ruled with a line in [color].
 * @property markerAlpha the marker's alpha over the fill: as strong as the style's text allows
 *   where the marker crosses it, the two together kept within [readableAlpha].
 * @property fades whether a fill rises from transparent to [color] towards its leading edge, a glow
 *   rather than a block.
 */
data class HaWearLevelLook(
  val color: Color,
  val alpha: Float,
  val ringDp: Float = 0f,
  val track: Boolean = true,
  val pointer: Boolean = false,
  val sweep: List<Color> = emptyList(),
  val segments: Int = 0,
  val ghosts: Boolean = false,
  val marker: Boolean = false,
  val markerAlpha: Float = 0f,
  val fades: Boolean = false,
)

/**
 * This style's Wear container background, or null for [HaUberTheme.Classic], which keeps the
 * watch's own surface and palette. Every other style brings its card surface in its dark palette
 * (the watch is dark), and the layers and edge that carry its material or identity: Home
 * Assistant's blue falling from the top, a Material 3 Expressive glow, Trinitron's hairline and
 * rule, an LCD's backlit glass and bezel, a Nixie tube's glow, a birch frame, LCARS's elbow band.
 */
fun HaUberTheme.wearBackground(): HaWearBackground? {
  if (id == HaUberTheme.Classic.id) return null
  val p = palette(darkTheme = true)
  val accent = p.linkText
  val base = p.cardBackground
  return when (visuals) {
    HaVisualFamily.Classic -> HaWearBackground(base)
    HaVisualFamily.HomeAssistant ->
      HaWearBackground(base, listOf(HaWearGradient(listOf(accent.copy(alpha = 0.22f), CLEAR))))
    HaVisualFamily.Expressive ->
      HaWearBackground(
        base,
        listOf(
          HaWearGlow(
            listOf(p.placeholderBackground, p.placeholderBackground.copy(alpha = 0f)),
            0f,
            0f,
            0.9f,
          ),
          HaWearGlow(listOf(accent.copy(alpha = 0.18f), CLEAR), 1f, 0.6f, 0.45f),
        ),
      )
    HaVisualFamily.Trinitron ->
      HaWearBackground(
        base,
        listOf(
          HaWearGradient(listOf(Color.White.copy(alpha = 0.05f), CLEAR)),
          HaWearBand(accent.copy(alpha = 0.85f), HaWearBand.Side.Top, 1.5f),
        ),
        HaWearEdge(listOf(p.divider), 1f),
      )
    HaVisualFamily.Lcd ->
      HaWearBackground(
        base,
        listOf(HaWearGlow(listOf(p.placeholderBackground, CLEAR), 0.5f, 0.5f, 0.6f)),
        HaWearEdge(listOf(p.divider), 2f, insetDp = 3f),
      )
    HaVisualFamily.Braun ->
      HaWearBackground(base, listOf(HaWearBand(accent, HaWearBand.Side.Bottom, 3f)))
    HaVisualFamily.Nixie ->
      HaWearBackground(
        base,
        listOf(HaWearGlow(listOf(accent.copy(alpha = 0.20f), CLEAR), 0.5f, 1.15f, 0.7f)),
        HaWearEdge(listOf(p.divider), 1f),
      )
    HaVisualFamily.Console ->
      HaWearBackground(
        base,
        listOf(
          HaWearGradient(
            listOf(
              Color.White.copy(alpha = 0.10f),
              Color.White.copy(alpha = 0.02f),
              Color.Black.copy(alpha = 0.08f),
              Color.Black.copy(alpha = 0.24f),
            )
          )
        ),
        HaWearEdge(listOf(Color.White.copy(alpha = 0.14f), Color.Black.copy(alpha = 0.4f)), 1f),
      )
    HaVisualFamily.Ikea ->
      HaWearBackground(
        base,
        listOf(HaWearGradient(listOf(CLEAR, BIRCH.copy(alpha = 0.22f)))),
        HaWearEdge(listOf(BIRCH.copy(alpha = 0.75f)), 3f),
      )
    HaVisualFamily.Mushroom ->
      HaWearBackground(
        base,
        listOf(HaWearGlow(listOf(accent.copy(alpha = 0.22f), CLEAR), 1f, 1f, 0.7f)),
      )
    HaVisualFamily.Bubble ->
      HaWearBackground(
        base,
        listOf(
          HaWearGlow(listOf(accent.copy(alpha = 0.22f), CLEAR), 0.95f, 0.05f, 0.35f),
          HaWearGlow(listOf(p.divider, CLEAR), 0.08f, 1f, 0.3f),
        ),
      )
    HaVisualFamily.Minimalist ->
      HaWearBackground(base, listOf(HaWearBand(accent, HaWearBand.Side.Start, 4f)))
    HaVisualFamily.Cupertino ->
      HaWearBackground(
        base,
        listOf(HaWearGradient(listOf(Color.White.copy(alpha = 0.09f), CLEAR))),
        HaWearEdge(listOf(Color.White.copy(alpha = 0.16f)), 1f),
      )
    HaVisualFamily.Catppuccin ->
      HaWearBackground(
        base,
        listOf(
          HaWearDiagonal(
            listOf(accent.copy(alpha = 0.16f), CLEAR, CATPPUCCIN_SKY.copy(alpha = 0.10f))
          )
        ),
        HaWearEdge(listOf(accent, CATPPUCCIN_SKY, CATPPUCCIN_PINK, accent), 1.5f),
      )
    HaVisualFamily.Obsidian ->
      HaWearBackground(
        base,
        listOf(HaWearBand(p.divider, HaWearBand.Side.Top, 1f)),
        HaWearEdge(listOf(p.divider), 1f),
      )
    HaVisualFamily.Noctis ->
      HaWearBackground(
        base,
        listOf(HaWearGlow(listOf(accent.copy(alpha = 0.20f), CLEAR), 1f, 0f, 0.8f)),
        HaWearEdge(listOf(accent.copy(alpha = 0.35f)), 1f),
      )
    HaVisualFamily.Lcars ->
      HaWearBackground(
        base,
        listOf(
          HaWearBand(accent, HaWearBand.Side.Start, 10f),
          HaWearBand(p.secondaryText, HaWearBand.Side.Top, 4f),
        ),
      )
    HaVisualFamily.Arctic ->
      HaWearBackground(
        base,
        listOf(HaWearGradient(listOf(accent.copy(alpha = 0.16f), CLEAR))),
        HaWearEdge(listOf(accent.copy(alpha = 0.30f)), 1f),
      )
    HaVisualFamily.Aeon ->
      HaWearBackground(
        base,
        listOf(HaWearGradient(listOf(CLEAR, accent.copy(alpha = 0.08f)))),
        HaWearEdge(listOf(accent, accent.copy(alpha = 0.35f), accent), 2f, insetDp = 3f),
      )
  }
}

/**
 * How a reading shows in this style's Wear container ([HaWearLevel]), in the style's accent. The
 * soft and instrument styles run it round the container as a ring (Home Assistant, Material 3
 * Expressive, Control Centre's activity ring, a pastel sweep for Catppuccin, a hairline for Arctic,
 * a dial's pointer for Braun); the display styles fill the container with it, cut into cells where
 * the style is segmented (LCD, Trinitron, Minimalist) and as a glow for Nixie. Classic, on the
 * watch's own surface, rings it in white.
 *
 * A fill's strength is capped so the style's text keeps its contrast where it overlaps the fill
 * ([readableAlpha]): an accent close to the text colour (an LCD's cyan ink) fills faintly, one far
 * from it more strongly.
 */
fun HaUberTheme.wearLevelLook(): HaWearLevelLook {
  val background = wearBackground()
  val p = if (background == null) CLASSIC_WEAR else palette(darkTheme = true)
  val base = background?.base ?: CLASSIC_WEAR_SURFACE
  val color =
    when (visuals) {
      HaVisualFamily.Classic -> Color.White
      HaVisualFamily.Ikea -> IKEA_YELLOW
      HaVisualFamily.Console -> p.linkText
      else -> inkFor(darkTheme = true) ?: p.linkText
    }
  fun fill(max: Float) = readableAlpha(base, color, p.primaryText, p.secondaryText, max)
  // A marker over a fill of [alpha]: the most it can add before the two pass what text allows.
  fun marker(alpha: Float): Float {
    val readable = fill(MARKER_MAX)
    return (1f - (1f - readable) / (1f - alpha)).coerceAtLeast(0f)
  }
  return when (visuals) {
    HaVisualFamily.Classic -> HaWearLevelLook(color, 0.85f, ringDp = 3f)
    HaVisualFamily.HomeAssistant -> HaWearLevelLook(color, 1f, ringDp = 4f)
    HaVisualFamily.Expressive -> HaWearLevelLook(color, 1f, ringDp = 6f)
    HaVisualFamily.Cupertino -> HaWearLevelLook(color, 1f, ringDp = 5f)
    HaVisualFamily.Braun -> HaWearLevelLook(color, 1f, ringDp = 4f, pointer = true)
    HaVisualFamily.Bubble -> HaWearLevelLook(color, 1f, ringDp = 6f)
    HaVisualFamily.Mushroom,
    HaVisualFamily.Noctis -> HaWearLevelLook(color, 1f, ringDp = 3f)
    HaVisualFamily.Catppuccin ->
      HaWearLevelLook(
        color,
        1f,
        ringDp = 3f,
        sweep = listOf(color, CATPPUCCIN_SKY, CATPPUCCIN_PINK, color),
      )
    HaVisualFamily.Arctic -> HaWearLevelLook(color, 1f, ringDp = 2f, track = false)
    HaVisualFamily.Lcd -> HaWearLevelLook(color, fill(0.40f), segments = 10, ghosts = true)
    HaVisualFamily.Trinitron -> HaWearLevelLook(color, fill(0.40f), segments = 20)
    HaVisualFamily.Minimalist -> HaWearLevelLook(color, fill(0.40f), segments = 5, ghosts = true)
    HaVisualFamily.Nixie ->
      fill(0.45f).let {
        HaWearLevelLook(color, it, fades = true, marker = true, markerAlpha = marker(it))
      }
    HaVisualFamily.Console,
    HaVisualFamily.Ikea,
    HaVisualFamily.Obsidian,
    HaVisualFamily.Lcars,
    HaVisualFamily.Aeon ->
      fill(0.30f).let { HaWearLevelLook(color, it, marker = true, markerAlpha = marker(it)) }
  }
}

/**
 * The strongest alpha up to [max] at which [color] over [base] keeps [primary] text at 4.5:1 and
 * [secondary] at 3:1 (WCAG AA for normal text and for large text and graphics), with a margin for a
 * card's state tint ([`ContainerTint`]), which mixes a quarter of an accent into the base.
 */
fun readableAlpha(base: Color, color: Color, primary: Color, secondary: Color, max: Float): Float {
  var alpha = max
  while (alpha > 0f) {
    val filled = lerp(base, color, alpha)
    if (
      contrast(primary, filled) >= PRIMARY_CONTRAST &&
        contrast(secondary, filled) >= SECONDARY_CONTRAST
    )
      return alpha
    alpha -= 0.02f
  }
  return 0f
}

/** The most a fill's marker is drawn at, before its contrast cap. */
private const val MARKER_MAX = 0.9f

private const val PRIMARY_CONTRAST = 4.5f + 0.5f
private const val SECONDARY_CONTRAST = 3f + 0.3f

private val CLEAR = Color.Transparent
private val BIRCH = Color(0xFFD9B98A)
private val IKEA_YELLOW = Color(0xFFFBD75B)
private val CATPPUCCIN_SKY = Color(0xFF89DCEB)
private val CATPPUCCIN_PINK = Color(0xFFF5C2E7)

/** The surface a Glance Wear container fills by default (`surfaceContainerLow`): Classic's. */
val CLASSIC_WEAR_SURFACE: Color = Color(39, 36, 48)

/** A Wear Material 3 dark palette's text roles, which Classic's text keeps on the watch. */
private val CLASSIC_WEAR =
  HaTheme.Dark.copy(primaryText = Color(0xFFE6E1E5), secondaryText = Color(0xFFCAC4D0))
