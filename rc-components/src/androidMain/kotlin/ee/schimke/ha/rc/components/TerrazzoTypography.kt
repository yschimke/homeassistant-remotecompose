package ee.schimke.ha.rc.components

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/**
 * Build a Material 3 [Typography] scale for the given [style]. Same M3 sizes and line-heights as
 * the defaults — only the font family changes, so a theme switch never reflows the layout.
 */
fun terrazzoTypographyFor(style: ThemeStyle): Typography {
  val (display, body) =
    when (style) {
      ThemeStyle.Material3 -> null to null // system default
      ThemeStyle.TerrazzoHome -> RobotoFlexFamily to InterFamily
      ThemeStyle.TerrazzoMushroom -> FigtreeFamily to FigtreeFamily
      ThemeStyle.TerrazzoMinimalist -> IbmPlexSansFamily to IbmPlexSansFamily
      ThemeStyle.TerrazzoKiosk -> AtkinsonHyperlegibleFamily to AtkinsonHyperlegibleFamily
    }
  if (display == null && body == null) return Typography()
  return typographyWith(displayFamily = display ?: body!!, bodyFamily = body ?: display!!)
}

/**
 * Re-map a Material 3 [Typography] so display/headline/title roles use [displayFamily] and
 * body/label roles use [bodyFamily]. The only thing that changes is [TextStyle.fontFamily] — all
 * sizes, weights, and line-heights are the stock M3 defaults.
 */
private fun typographyWith(displayFamily: FontFamily, bodyFamily: FontFamily): Typography {
  val d = Typography()
  fun TextStyle.withFamily(family: FontFamily) = copy(fontFamily = family)
  return Typography(
    displayLarge = d.displayLarge.withFamily(displayFamily),
    displayMedium = d.displayMedium.withFamily(displayFamily),
    displaySmall = d.displaySmall.withFamily(displayFamily),
    headlineLarge = d.headlineLarge.withFamily(displayFamily),
    headlineMedium = d.headlineMedium.withFamily(displayFamily),
    headlineSmall = d.headlineSmall.withFamily(displayFamily),
    titleLarge = d.titleLarge.withFamily(displayFamily),
    titleMedium = d.titleMedium.withFamily(displayFamily),
    titleSmall = d.titleSmall.withFamily(displayFamily),
    bodyLarge = d.bodyLarge.withFamily(bodyFamily),
    bodyMedium = d.bodyMedium.withFamily(bodyFamily),
    bodySmall = d.bodySmall.withFamily(bodyFamily),
    labelLarge = d.labelLarge.withFamily(bodyFamily),
    labelMedium = d.labelMedium.withFamily(bodyFamily),
    labelSmall = d.labelSmall.withFamily(bodyFamily),
  )
}
