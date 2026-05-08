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
    /**
     * Background of a `type: sections` group container. Cards within the
     * section sit on [cardBackground] above this layer — three-layer
     * stack is `dashboardBackground` → `sectionBackground` → `cardBackground`.
     *
     * Set equal to [dashboardBackground] to opt out of the group surface
     * (a `Surface(color = sectionBackground)` wrap then renders as a
     * no-op). The flat themes — `TerrazzoHome` (HA blue) and
     * `TerrazzoMinimalist` (matt8707) — opt out, since their identity is
     * a single uniform surface; the elevated themes (`Material3`,
     * `TerrazzoMushroom`, `TerrazzoKiosk`) opt in.
     */
    val sectionBackground: Color,
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
            sectionBackground = Color(0xFFFAFAFA),
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
            sectionBackground = Color(0xFF111111),
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
 * Derive an [HaTheme] for a given [style] and [darkTheme] flag.
 *
 * The mapping branches by palette identity:
 *
 *  - **`Material3`, `TerrazzoMushroom`, `TerrazzoKiosk`** — the "rich"
 *    palettes. Map onto Material 3's surface-elevation tokens for a
 *    three-layer stack: the page sits on `surface`, sections group their
 *    cards on `surfaceContainer`, and cards themselves sit on
 *    `surfaceContainerHigh`. Picture/unsupported/completed accents come
 *    from `primary`/`primaryContainer` so the palette's hero colour
 *    carries through, and dividers use the softer `outlineVariant`
 *    decorative role.
 *
 *  - **`TerrazzoHome`, `TerrazzoMinimalist`** — the "flat" palettes.
 *    Their brief is a single uniform surface (HA's stock blue dashboard
 *    and the matt8707 minimalist look respectively), so they keep the
 *    pre-existing flat mapping: cards on `surface`, dashboard on
 *    `background`, divider on `outline`, accents from
 *    `secondary`/`secondaryContainer`. `sectionBackground` is set equal
 *    to `dashboardBackground` so the dashboard's section-group
 *    `Surface` wrap renders as a no-op for these two themes.
 */
fun haThemeFor(style: ThemeStyle, darkTheme: Boolean): HaTheme {
    val m3 = terrazzoColorScheme(style, darkTheme)
    return when (style) {
        ThemeStyle.TerrazzoHome,
        ThemeStyle.TerrazzoMinimalist ->
            HaTheme(
                cardBackground = m3.surface,
                dashboardBackground = m3.background,
                sectionBackground = m3.background,
                primaryText = m3.onSurface,
                secondaryText = m3.onSurfaceVariant,
                divider = m3.outline,
                placeholderAccent = m3.secondary,
                placeholderBackground = m3.secondaryContainer,
                unknownAccent = m3.onSurfaceVariant,
                isDark = darkTheme,
            )
        ThemeStyle.Material3,
        ThemeStyle.TerrazzoMushroom,
        ThemeStyle.TerrazzoKiosk ->
            HaTheme(
                cardBackground = m3.surfaceContainerHigh,
                dashboardBackground = m3.surface,
                sectionBackground = m3.surfaceContainer,
                primaryText = m3.onSurface,
                secondaryText = m3.onSurfaceVariant,
                divider = m3.outlineVariant,
                placeholderAccent = m3.primary,
                placeholderBackground = m3.primaryContainer,
                unknownAccent = m3.onSurfaceVariant,
                isDark = darkTheme,
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
