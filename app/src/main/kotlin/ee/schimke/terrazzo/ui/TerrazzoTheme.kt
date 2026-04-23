package ee.schimke.terrazzo.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.rememberDynamicColorScheme
import ee.schimke.terrazzo.ui.theme.ColorSource
import ee.schimke.terrazzo.ui.theme.DarkMode
import ee.schimke.terrazzo.ui.theme.ThemeSettings
import ee.schimke.terrazzo.ui.theme.typographyFor

/**
 * HA-brand seed used by [ColorSource.Custom]. Derived from the Home
 * Assistant primary blue (`#18BCF2`); `materialkolor` spreads it into a
 * full tonal palette so pre-Android-12 devices and wall tablets that
 * want HA-identity get a proper M3 scheme instead of the grey fallback.
 */
private val HomeAssistantSeed = Color(0xFF18BCF2)

@Composable
fun TerrazzoTheme(
    settings: ThemeSettings = ThemeSettings(),
    content: @Composable () -> Unit,
) {
    val dark = when (settings.darkMode) {
        DarkMode.FollowSystem -> isSystemInDarkTheme()
        DarkMode.Light -> false
        DarkMode.Dark -> true
    }
    val colors = colorSchemeFor(settings.colorSource, dark)
    MaterialTheme(
        colorScheme = colors,
        typography = typographyFor(settings.typography),
        content = content,
    )
}

@Composable
private fun colorSchemeFor(source: ColorSource, dark: Boolean): ColorScheme = when (source) {
    ColorSource.MaterialDynamic -> {
        val ctx = LocalContext.current
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    }
    ColorSource.Custom -> rememberDynamicColorScheme(
        seedColor = HomeAssistantSeed,
        isDark = dark,
    )
}
