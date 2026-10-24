package ee.schimke.terrazzo.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.remote.creation.compose.text.RemoteFontFamily
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.HaUberTheme

/**
 * Card styles that theme the whole app. A card style that declares [HaUberTheme.appTheme] is
 * discovered here: [TerrazzoTheme] draws every screen in its palette and type instead of the user's
 * palette choice, and Settings sets that choice aside while the style is on.
 */

/** The card style the app is themed in, or null when the user's palette choice applies. */
fun HaUberTheme.appThemeOrNull(): HaUberTheme? = takeIf { it.appTheme }

/**
 * Whether the app is dark under [this] app theme: a single-mode style decides it ([darkPref] is
 * ignored), else [darkPref] does.
 */
fun HaUberTheme.appDark(darkPref: Boolean): Boolean =
  if (singleMode) palette(darkPref).isDark else darkPref

/**
 * A Material colour scheme from a card style palette: the dashboard background as the surface, the
 * card background for containers, its text and dividers, and [ink] (a monochrome style's single
 * colour) or the palette's link colour as the accent. Roles the palette has no word for come from
 * [base].
 */
fun HaTheme.toColorScheme(
  ink: Color?,
  base: ColorScheme = if (isDark) darkColorScheme() else lightColorScheme(),
): ColorScheme {
  val accent = ink ?: linkText
  val tonal = accent.copy(alpha = 0.22f).compositeOver(cardBackground)
  return base.copy(
    primary = accent,
    onPrimary = cardBackground,
    primaryContainer = tonal,
    onPrimaryContainer = primaryText,
    secondary = accent,
    onSecondary = cardBackground,
    secondaryContainer = tonal,
    onSecondaryContainer = primaryText,
    tertiary = accent,
    onTertiary = cardBackground,
    background = dashboardBackground,
    onBackground = primaryText,
    surface = dashboardBackground,
    onSurface = primaryText,
    surfaceVariant = cardBackground,
    onSurfaceVariant = secondaryText,
    surfaceContainerLowest = dashboardBackground,
    surfaceContainerLow = dashboardBackground,
    surfaceContainer = cardBackground,
    surfaceContainerHigh = cardBackground,
    surfaceContainerHighest = tileColor(),
    surfaceBright = cardBackground,
    surfaceDim = dashboardBackground,
    inverseSurface = primaryText,
    inverseOnSurface = cardBackground,
    outline = lerp(divider, secondaryText, 0.5f),
    outlineVariant = divider,
  )
}

/** A tile inside a card: a step from the card background toward the text. */
fun HaTheme.tileColor(): Color = lerp(cardBackground, primaryText, if (isDark) 0.08f else 0.05f)

/** This type scale in [theme]'s typeface (when it names one) with its tracking. */
fun Typography.inCardStyle(theme: HaUberTheme): Typography =
  skinned(theme.fontFamily.toComposeFamily(), theme.letterSpacingSp.sp)

/** The Compose family for a card style's typeface; null for the default. */
fun RemoteFontFamily.toComposeFamily(): FontFamily? =
  when (this) {
    RemoteFontFamily.Monospace -> FontFamily.Monospace
    RemoteFontFamily.Serif -> FontFamily.Serif
    RemoteFontFamily.SansSerif -> FontFamily.SansSerif
    else -> null
  }

/** This type scale in [family] (unchanged when null) with [tracking] added to every style. */
private fun Typography.skinned(family: FontFamily?, tracking: TextUnit): Typography {
  fun TextStyle.s(): TextStyle =
    copy(
      fontFamily = family ?: fontFamily,
      letterSpacing = if (tracking.value == 0f) letterSpacing else tracking,
    )
  return copy(
    displayLarge = displayLarge.s(),
    displayMedium = displayMedium.s(),
    displaySmall = displaySmall.s(),
    headlineLarge = headlineLarge.s(),
    headlineMedium = headlineMedium.s(),
    headlineSmall = headlineSmall.s(),
    titleLarge = titleLarge.s(),
    titleMedium = titleMedium.s(),
    titleSmall = titleSmall.s(),
    bodyLarge = bodyLarge.s(),
    bodyMedium = bodyMedium.s(),
    bodySmall = bodySmall.s(),
    labelLarge = labelLarge.s(),
    labelMedium = labelMedium.s(),
    labelSmall = labelSmall.s(),
  )
}
