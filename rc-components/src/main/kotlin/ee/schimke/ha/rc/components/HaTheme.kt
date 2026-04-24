package ee.schimke.ha.rc.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Rendered-theme palette for Home Assistant cards. A [HaTheme] is a
 * concrete snapshot of colours; composables read it through
 * [LocalHaTheme] and emit plain [Color]s into the `.rc` document at
 * capture time.
 *
 * A theme switch **regenerates** the document — the colours are baked
 * at capture. This is deliberate (see project_rendering_strategy in
 * memory); alpha08 does not expose a stable `ColorTheme` DSL that would
 * let a single document carry both palettes.
 */
data class HaTheme(
    val cardBackground: Color,
    val dashboardBackground: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val divider: Color,
    val placeholderAccent: Color,
    val placeholderBackground: Color,
    val unknownAccent: Color,
    val isDark: Boolean,
) {
    companion object {
        // Values sampled from HA's default light theme for
        // `hui-tile-card` (HA 2026-04). Used as the `TerrazzoHome` light
        // palette and as the fallback when no style is resolved.
        val Light = HaTheme(
            cardBackground = Color(0xFFFFFFFF),
            dashboardBackground = Color(0xFFFAFAFA),
            primaryText = Color(0xFF141414),
            secondaryText = Color(0xFF8F8F8F),
            divider = Color(0xFFE0E0E0),
            placeholderAccent = Color(0xFFB26A00),
            placeholderBackground = Color(0xFFFFF4E5),
            unknownAccent = Color(0xFF757575),
            isDark = false,
        )
        // Values sampled from HA's default dark theme for
        // `hui-tile-card` (HA 2026-04).
        val Dark = HaTheme(
            cardBackground = Color(0xFF1C1C1C),
            dashboardBackground = Color(0xFF111111),
            primaryText = Color(0xFFE1E1E1),
            secondaryText = Color(0xFF878787),
            divider = Color(0xFF333333),
            placeholderAccent = Color(0xFFFFB74D),
            placeholderBackground = Color(0xFF2A2016),
            unknownAccent = Color(0xFF8A8F96),
            isDark = true,
        )
    }
}

/**
 * Derive an [HaTheme] for a given [style] and [darkTheme] flag. Each
 * Terrazzo palette maps the Material 3 `ColorScheme` (built from
 * [terrazzoColorScheme]) onto the HA-card role layer so cards render in
 * the selected palette automatically:
 *
 * - `surface` ↦ `cardBackground`
 * - `background` ↦ `dashboardBackground`
 * - `onSurface` ↦ `primaryText`
 * - `onSurfaceVariant` ↦ `secondaryText`
 * - `outline` ↦ `divider`
 * - `secondary` ↦ `placeholderAccent`
 * - `secondaryContainer` ↦ `placeholderBackground`
 *
 * [ThemeStyle.Material3] is special-cased: the M3 defaults have a
 * lilac primary that doesn't match any HA palette, so we fall back to
 * the HA-sampled Light/Dark snapshots rather than let `colorScheme()`
 * leak purple containers into the dashboard.
 */
fun haThemeFor(style: ThemeStyle, darkTheme: Boolean): HaTheme {
    if (style == ThemeStyle.Material3) {
        return if (darkTheme) HaTheme.Dark else HaTheme.Light
    }
    val m3 = terrazzoColorScheme(style, darkTheme)
    return HaTheme(
        cardBackground = m3.surface,
        dashboardBackground = m3.background,
        primaryText = m3.onSurface,
        secondaryText = m3.onSurfaceVariant,
        divider = m3.outline,
        placeholderAccent = m3.secondary,
        placeholderBackground = m3.secondaryContainer,
        unknownAccent = m3.onSurfaceVariant,
        isDark = darkTheme,
    )
}

val LocalHaTheme = staticCompositionLocalOf { HaTheme.Light }

@Composable
fun ProvideHaTheme(theme: HaTheme, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHaTheme provides theme, content = content)
}

@Composable
@ReadOnlyComposable
internal fun haTheme(): HaTheme = LocalHaTheme.current
