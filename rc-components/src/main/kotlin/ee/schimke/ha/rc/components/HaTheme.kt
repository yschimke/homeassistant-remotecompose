package ee.schimke.ha.rc.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Rendered-theme palette for Home Assistant cards. A [HaTheme] is a
 * concrete snapshot of colors; composables read it through [LocalHaTheme]
 * and emit plain [Color]s into the document at capture time.
 *
 * TODO(theme): move to `androidx.compose.remote.core.operations.ColorTheme`
 *   so a single `.rc` document carries both palettes and the player
 *   switches based on `playbackTheme`. The creation-side DSL for
 *   `ColorTheme` is not yet public as of alpha08, so for now we:
 *
 *   - capture the host app's current theme
 *   - render one document per theme
 *   - regenerate when the theme changes
 *
 *   See `androidx.compose.remote.core.operations.Theme.{LIGHT,DARK}`
 *   and `ColorTheme` for the eventual single-document path.
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
        // `hui-tile-card` (HA 2026-04).
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

val LocalHaTheme = staticCompositionLocalOf { HaTheme.Light }

@Composable
fun ProvideHaTheme(theme: HaTheme, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHaTheme provides theme, content = content)
}

@Composable
@ReadOnlyComposable
internal fun haTheme(): HaTheme = LocalHaTheme.current
