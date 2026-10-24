package ee.schimke.terrazzo.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.terrazzoColorScheme
import ee.schimke.ha.rc.components.terrazzoTypographyFor
import ee.schimke.terrazzo.core.prefs.CardStylePref
import ee.schimke.terrazzo.core.prefs.DarkModePref
import ee.schimke.terrazzo.core.prefs.ThemePref

/**
 * The active [ThemeStyle] downstream of [TerrazzoTheme]. Screens that render HA cards — which need
 * an `HaTheme`, not a Material 3 `ColorScheme` — read this together with [LocalIsDarkTheme] to
 * build the right `HaTheme` via `haThemeFor(style, dark)`.
 */
val LocalThemeStyle = staticCompositionLocalOf { ThemeStyle.TerrazzoHome }

/**
 * Resolved dark-mode flag (the user's [DarkModePref] merged with `isSystemInDarkTheme()`). Exposed
 * separately so it's available in non-Material contexts (e.g. a `RemotePreview` host that doesn't
 * read `MaterialTheme.colorScheme`).
 */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

/**
 * The card style ([HaUberTheme]) dashboard cards draw in, from the user's [CardStylePref]. Card
 * hosts read it with [LocalThemeStyle] and [LocalIsDarkTheme]: `cardStyle.cardPalette(style, dark)`
 * for the palette, then `ProvideCardStyle(…)` around the card.
 */
val LocalCardStyle = staticCompositionLocalOf { HaUberTheme.Classic }

/**
 * App-level Material 3 theme. Reads the user-selected [ThemeStyle] and [DarkModePref] from the
 * store (wired via [TerrazzoThemeController] one level up) and composes:
 *
 * - **Material3 style + wallpaper available**: seed sampled from the system wallpaper (Android
 *   8.1+) and run through materialkolor's `dynamicColorScheme(...)` for Material You-style
 *   derivation with our own [PaletteStyle] tuning. Typography: system default.
 * - **Material3 style + no wallpaper seed**: stock light/dark `colorScheme()`.
 * - **Any Terrazzo style**: that palette's seed run through materialkolor plus the Google Fonts
 *   pairing for the palette.
 * - **A card style that themes the app** ([HaUberTheme.appTheme]: LCD, Trinitron, …): the card
 *   style's own palette and typeface on every screen, in place of [style]; a single-mode card style
 *   (dark-only Trinitron) also decides light or dark in place of [darkMode].
 */
@Composable
fun TerrazzoTheme(
  style: ThemeStyle,
  darkMode: DarkModePref,
  cardStyle: HaUberTheme = HaUberTheme.Classic,
  content: @Composable () -> Unit,
) {
  val systemDark = isSystemInDarkTheme()
  val darkTheme =
    when (darkMode) {
      DarkModePref.Follow -> systemDark
      DarkModePref.Light -> false
      DarkModePref.Dark -> true
    }

  // Only the theme values branch: the content is composed at one call site, so switching between
  // an app-theming card style and the palette choice keeps the app's remembered state (the open
  // screen, navigation) rather than recomposing it from scratch.
  val appTheme = cardStyle.appThemeOrNull()
  val wallpaperSeed =
    if (appTheme == null && style == ThemeStyle.Material3) rememberWallpaperSeedColor() else null
  val dark = appTheme?.appDark(darkTheme) ?: darkTheme
  val colors =
    if (appTheme != null) {
      remember(appTheme, dark) { appTheme.palette(dark).toColorScheme(appTheme.inkFor(dark)) }
    } else {
      terrazzoColorScheme(style, darkTheme, seedOverride = wallpaperSeed)
    }
  val typography =
    terrazzoTypographyFor(style).let { base -> appTheme?.let(base::inCardStyle) ?: base }

  CompositionLocalProvider(
    LocalThemeStyle provides style,
    LocalIsDarkTheme provides dark,
    LocalCardStyle provides cardStyle,
  ) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
  }
}

/** Convenience: map a [ThemePref] value to the corresponding [ThemeStyle]. */
fun ThemePref.toThemeStyle(): ThemeStyle =
  when (this) {
    ThemePref.Material3 -> ThemeStyle.Material3
    ThemePref.TerrazzoHome -> ThemeStyle.TerrazzoHome
    ThemePref.TerrazzoMushroom -> ThemeStyle.TerrazzoMushroom
    ThemePref.TerrazzoMinimalist -> ThemeStyle.TerrazzoMinimalist
    ThemePref.TerrazzoKiosk -> ThemeStyle.TerrazzoKiosk
  }

/** The [HaUberTheme] a [CardStylePref] names. */
fun CardStylePref.toUberTheme(): HaUberTheme = HaUberTheme.byId(id)
