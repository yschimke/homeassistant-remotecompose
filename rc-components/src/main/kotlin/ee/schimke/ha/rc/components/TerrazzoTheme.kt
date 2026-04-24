package ee.schimke.ha.rc.components

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import com.materialkolor.Contrast
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme

/**
 * Per-palette tuning of how [dynamicColorScheme] derives the full
 * Material 3 `ColorScheme` from the seed. The [PaletteStyle] choice is
 * the biggest lever — `Expressive` produces warmer, higher-chroma
 * containers than `TonalSpot`, `Monochrome` strips all hue.
 * [contrast] nudges the WCAG contrast target; Kiosk uses
 * [Contrast.High] since wall-mounted panels need headroom.
 */
private data class PaletteSpec(val style: PaletteStyle, val contrast: Contrast = Contrast.Default)

private val ThemeStyle.paletteSpec: PaletteSpec
    get() = when (this) {
        ThemeStyle.Material3 -> PaletteSpec(PaletteStyle.TonalSpot)
        // Home: default M3 derivation — HA's blue reads cleanly under
        // TonalSpot and the resulting containers match HA's own UI.
        ThemeStyle.TerrazzoHome -> PaletteSpec(PaletteStyle.TonalSpot)
        // Mushroom: Fidelity keeps the salmon seed as the primary rather
        // than hue-rotating it. Expressive looked gorgeous but produced a
        // purple primary (complementary rotation) — wrong identity for a
        // theme whose brief is "warm".
        ThemeStyle.TerrazzoMushroom -> PaletteSpec(PaletteStyle.Fidelity)
        // Minimalist: Neutral drains saturation from everything but
        // primary — matches the matt8707 aesthetic of a mostly-grey UI
        // with a single accent.
        ThemeStyle.TerrazzoMinimalist -> PaletteSpec(PaletteStyle.Neutral)
        // Kiosk: Vibrant + high contrast — we want every state marker
        // to read unambiguously at ≥1 m viewing distance under ambient
        // room light.
        ThemeStyle.TerrazzoKiosk -> PaletteSpec(PaletteStyle.Vibrant, Contrast.High)
    }

/**
 * Build a Material 3 [ColorScheme] for the given [style] and mode.
 *
 * Each Terrazzo palette is **derived from its seed** via materialkolor's
 * `dynamicColorScheme(...)`, using the palette's [PaletteStyle] +
 * contrast target. All M3 roles are populated — `tertiary`,
 * `surfaceContainer*`, `inverseSurface`, etc. — not just the seven we
 * used to hand-code.
 *
 * [ThemeStyle.Material3] falls back to the stock `lightColorScheme()` /
 * `darkColorScheme()`. The app's `TerrazzoTheme` composable promotes
 * that to dynamic colour on Android 12+ via the platform API; this
 * module has no `Context` so it can't call `dynamicLightColorScheme`.
 */
fun terrazzoColorScheme(style: ThemeStyle, darkTheme: Boolean): ColorScheme {
    if (style == ThemeStyle.Material3) {
        return if (darkTheme) darkColorScheme() else lightColorScheme()
    }
    val seed = style.seedColor ?: return if (darkTheme) darkColorScheme() else lightColorScheme()
    val spec = style.paletteSpec
    return dynamicColorScheme(
        seedColor = seed,
        isDark = darkTheme,
        isAmoled = false,
        style = spec.style,
        contrastLevel = spec.contrast.value,
    )
}

/**
 * Build a Material 3 [Typography] scale for the given [style]. Same
 * M3 sizes and line-heights as the defaults — only the font family
 * changes, so a theme switch never reflows the layout.
 */
fun terrazzoTypographyFor(style: ThemeStyle): Typography {
    val (display, body) = when (style) {
        ThemeStyle.Material3 -> null to null                                  // system default
        ThemeStyle.TerrazzoHome -> RobotoFlexFamily to InterFamily
        ThemeStyle.TerrazzoMushroom -> FigtreeFamily to FigtreeFamily
        ThemeStyle.TerrazzoMinimalist -> IbmPlexSansFamily to IbmPlexSansFamily
        ThemeStyle.TerrazzoKiosk -> AtkinsonHyperlegibleFamily to AtkinsonHyperlegibleFamily
    }
    if (display == null && body == null) return Typography()
    return typographyWith(displayFamily = display ?: body!!, bodyFamily = body ?: display!!)
}

/**
 * Re-map a Material 3 [Typography] so display/headline/title roles use
 * [displayFamily] and body/label roles use [bodyFamily]. The only thing
 * that changes is [TextStyle.fontFamily] — all sizes, weights, and
 * line-heights are the stock M3 defaults.
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
