package ee.schimke.ha.rc.components

import androidx.compose.ui.graphics.Color

/**
 * What a card style paints a Wear widget container with: a [base] fill and [layers] painted over
 * it, in order. On the watch the card's own surface is the container's (the card draws no chrome of
 * its own there), so this is what tells one style from another at a glance, not only its typeface.
 *
 * It holds no shape. Whoever draws the container clips every layer to the container's own corner
 * radius, which only the watch's widget host knows: Glance Wear's `WearWidgetBrush`, the Wear
 * template's named `wear.container.corner_radius`, or a preview's approximation of either. So the
 * same background fits a round watch's 26dp container and a square one's.
 *
 * @property base the container's fill: the style's card background. A card's state tint
 *   (`ContainerTint`) is mixed onto it.
 * @property layers gradients over [base], each covering the whole container.
 */
data class HaWearBackground(val base: Color, val layers: List<HaWearGradient> = emptyList())

/** A gradient across the whole container: top to bottom when [vertical], else start to end. */
data class HaWearGradient(val colors: List<Color>, val vertical: Boolean = true)

/**
 * This style's Wear container background, or null for [HaUberTheme.Classic], which keeps the
 * watch's own surface and palette. Every other style brings its card surface in its dark palette
 * (the watch is dark), plus a layer that carries its material or identity:
 * - Home Assistant: its blue falling from the top edge.
 * - Material 3 Expressive: a tonal sweep of its primary container from the start edge.
 * - A painted-metal panel: the light falling across it, as its cards' sheen.
 * - A birch frame: the warmth of timber at the bottom edge.
 */
fun HaUberTheme.wearBackground(): HaWearBackground? {
  if (id == HaUberTheme.Classic.id) return null
  val palette = palette(darkTheme = true)
  val layers =
    when {
      shape.material == HaSurfaceMaterial.PaintedMetal ->
        listOf(
          HaWearGradient(
            listOf(
              Color.White.copy(alpha = 0.10f),
              Color.White.copy(alpha = 0.02f),
              Color.Black.copy(alpha = 0.08f),
              Color.Black.copy(alpha = 0.24f),
            )
          )
        )
      shape.material == HaSurfaceMaterial.BirchFrame ->
        listOf(HaWearGradient(listOf(Color.Transparent, BIRCH.copy(alpha = 0.22f))))
      visuals == HaVisualFamily.HomeAssistant ->
        listOf(HaWearGradient(listOf(palette.linkText.copy(alpha = 0.22f), Color.Transparent)))
      visuals == HaVisualFamily.Expressive ->
        listOf(
          HaWearGradient(
            listOf(palette.placeholderBackground, palette.placeholderBackground.copy(alpha = 0f)),
            vertical = false,
          )
        )
      else -> emptyList()
    }
  return HaWearBackground(palette.cardBackground, layers)
}

private val BIRCH = Color(0xFFD9B98A)
