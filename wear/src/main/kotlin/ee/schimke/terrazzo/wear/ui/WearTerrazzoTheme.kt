package ee.schimke.terrazzo.wear.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.Typography
import ee.schimke.ha.rc.components.AtkinsonHyperlegibleFamily
import ee.schimke.ha.rc.components.FigtreeFamily
import ee.schimke.ha.rc.components.IbmPlexSansFamily
import ee.schimke.ha.rc.components.InterFamily
import ee.schimke.ha.rc.components.RobotoFlexFamily
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.terrazzoColorScheme

/**
 * Project the (mobile) Material 3 [ColorScheme] built by
 * [terrazzoColorScheme] onto the Wear Material 3 [ColorScheme] for the
 * given [style]. Wear is dark-only on the watch surface, so this always
 * reads the dark variant.
 *
 * Only the roles Wear Material 3 exposes are mapped; the rest come from
 * the Wear `ColorScheme()` defaults.
 */
fun terrazzoWearColorScheme(style: ThemeStyle): ColorScheme {
    val m3 = terrazzoColorScheme(style, darkTheme = true)
    val defaults = ColorScheme()
    return defaults.copy(
        primary = m3.primary,
        onPrimary = m3.onPrimary,
        primaryContainer = m3.primaryContainer,
        onPrimaryContainer = m3.onPrimaryContainer,
        primaryDim = m3.primaryContainer,
        secondary = m3.secondary,
        onSecondary = m3.onSecondary,
        secondaryContainer = m3.secondaryContainer,
        onSecondaryContainer = m3.onSecondaryContainer,
        secondaryDim = m3.secondaryContainer,
        tertiary = m3.tertiary,
        onTertiary = m3.onTertiary,
        tertiaryContainer = m3.tertiaryContainer,
        onTertiaryContainer = m3.onTertiaryContainer,
        tertiaryDim = m3.tertiaryContainer,
        background = m3.background,
        onBackground = m3.onBackground,
        surfaceContainerLow = m3.surface,
        surfaceContainer = m3.surface,
        surfaceContainerHigh = m3.surfaceVariant,
        onSurface = m3.onSurface,
        onSurfaceVariant = m3.onSurfaceVariant,
        outline = m3.outline,
        outlineVariant = m3.outlineVariant,
    )
}

/**
 * Wear Material 3 [Typography] whose font family follows the Terrazzo
 * [style]. Sizes + line-heights stay on the Wear defaults — the only
 * thing that changes is the family so watch-face layout stays
 * deterministic regardless of the selected theme.
 */
fun wearTypographyFor(style: ThemeStyle): Typography {
    val display: FontFamily = when (style) {
        ThemeStyle.Material3 -> return Typography()
        ThemeStyle.TerrazzoHome -> RobotoFlexFamily
        ThemeStyle.TerrazzoMushroom -> FigtreeFamily
        ThemeStyle.TerrazzoMinimalist -> IbmPlexSansFamily
        ThemeStyle.TerrazzoKiosk -> AtkinsonHyperlegibleFamily
    }
    val body: FontFamily = when (style) {
        ThemeStyle.TerrazzoHome -> InterFamily
        else -> display
    }
    val d = Typography()
    fun TextStyle.withFamily(family: FontFamily) = copy(fontFamily = family)
    return Typography(
        displayLarge = d.displayLarge.withFamily(display),
        displayMedium = d.displayMedium.withFamily(display),
        displaySmall = d.displaySmall.withFamily(display),
        titleLarge = d.titleLarge.withFamily(display),
        titleMedium = d.titleMedium.withFamily(display),
        titleSmall = d.titleSmall.withFamily(display),
        bodyLarge = d.bodyLarge.withFamily(body),
        bodyMedium = d.bodyMedium.withFamily(body),
        bodySmall = d.bodySmall.withFamily(body),
        labelLarge = d.labelLarge.withFamily(body),
        labelMedium = d.labelMedium.withFamily(body),
        labelSmall = d.labelSmall.withFamily(body),
    )
}
