package ee.schimke.terrazzo.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.terrazzoColorScheme
import ee.schimke.ha.rc.components.terrazzoTypographyFor
import ee.schimke.terrazzo.core.prefs.DarkModePref
import ee.schimke.terrazzo.core.prefs.ThemePref

/**
 * The active [ThemeStyle] downstream of [TerrazzoTheme]. Screens that
 * render HA cards — which need an `HaTheme`, not a Material 3
 * `ColorScheme` — read this together with [LocalIsDarkTheme] to build
 * the right `HaTheme` via `haThemeFor(style, dark)`.
 */
val LocalThemeStyle = staticCompositionLocalOf { ThemeStyle.TerrazzoHome }

/**
 * Resolved dark-mode flag (the user's [DarkModePref] merged with
 * `isSystemInDarkTheme()`). Exposed separately so it's available in
 * non-Material contexts (e.g. a `RemotePreview` host that doesn't read
 * `MaterialTheme.colorScheme`).
 */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

/**
 * App-level Material 3 theme. Reads the user-selected [ThemeStyle] and
 * [DarkModePref] from the store (wired via [TerrazzoThemeController]
 * one level up) and composes:
 *
 * - **Material3 style + Android 12+**: dynamic colour scheme from the
 *   system wallpaper (Material You). Typography: system default.
 * - **Material3 style + older**: stock light/dark `colorScheme()`.
 * - **Any Terrazzo style**: hand-picked palette from [terrazzoColorScheme]
 *   plus the Google Fonts pairing for that palette.
 */
@Composable
fun TerrazzoTheme(
    style: ThemeStyle,
    darkMode: DarkModePref,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (darkMode) {
        DarkModePref.Follow -> systemDark
        DarkModePref.Light -> false
        DarkModePref.Dark -> true
    }

    val colors = when {
        style == ThemeStyle.Material3 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> terrazzoColorScheme(style, darkTheme)
    }

    CompositionLocalProvider(
        LocalThemeStyle provides style,
        LocalIsDarkTheme provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = terrazzoTypographyFor(style),
            content = content,
        )
    }
}

/** Convenience: map a [ThemePref] value to the corresponding [ThemeStyle]. */
fun ThemePref.toThemeStyle(): ThemeStyle = when (this) {
    ThemePref.Material3 -> ThemeStyle.Material3
    ThemePref.TerrazzoHome -> ThemeStyle.TerrazzoHome
    ThemePref.TerrazzoMushroom -> ThemeStyle.TerrazzoMushroom
    ThemePref.TerrazzoMinimalist -> ThemeStyle.TerrazzoMinimalist
    ThemePref.TerrazzoKiosk -> ThemeStyle.TerrazzoKiosk
}
