package ee.schimke.ha.rc.components

import androidx.compose.ui.graphics.Color

/**
 * The top-level theme toggle. `Material3` is the defaults-only fallback
 * (stock `colorScheme()` + system font, promoted to dynamic color on
 * Android 12+ where available). Every other entry is a curated Terrazzo
 * palette — a named seed colour + a Google-Fonts pairing chosen for a
 * specific HA context.
 *
 * Palette colours are not "seeds" in the materialkolor sense — we don't
 * run a full tonal derivation, we just hand-pick the handful of roles
 * that carry the identity ([TerrazzoPalette]).
 */
enum class ThemeStyle(
    val displayName: String,
    val tagline: String,
    val isMaterial3Default: Boolean,
) {
    /** Stock Material 3 with dynamic colour on Android 12+ and the system font. */
    Material3(
        displayName = "Material 3",
        tagline = "Defaults, dynamic colour on Android 12+",
        isMaterial3Default = true,
    ),

    /** Home Assistant's default blue — recognisable to anyone who's seen an HA dashboard. */
    TerrazzoHome(
        displayName = "Home",
        tagline = "Home Assistant blue · Roboto Flex + Inter",
        isMaterial3Default = false,
    ),

    /** Warm, rounded, organic — the Mushroom card community feel. */
    TerrazzoMushroom(
        displayName = "Mushroom",
        tagline = "Warm salmon · Figtree",
        isMaterial3Default = false,
    ),

    /** Monochrome, data-dense — the matt8707 / minimalist-dashboard style. */
    TerrazzoMinimalist(
        displayName = "Minimalist",
        tagline = "Neutral slate · IBM Plex Sans",
        isMaterial3Default = false,
    ),

    /**
     * Wall-mounted panels and TV dashboards: max legibility at ≥1m.
     * Uses Atkinson Hyperlegible (designed by the Braille Institute for
     * low-vision distance reading) and a high-contrast teal seed.
     */
    TerrazzoKiosk(
        displayName = "Kiosk",
        tagline = "High-contrast teal · Atkinson Hyperlegible",
        isMaterial3Default = false,
    ),
}

/**
 * Canonical seed colours. Kept here rather than baked into the enum so
 * the lookup is uniform for the default-Material3 case (no seed →
 * dynamic colour or the M3 defaults). Only the Terrazzo entries have a
 * seed.
 */
internal val ThemeStyle.seedColor: Color?
    get() = when (this) {
        ThemeStyle.Material3 -> null
        ThemeStyle.TerrazzoHome -> Color(0xFF03A9F4)       // HA default blue (frontend/ha-style.ts)
        ThemeStyle.TerrazzoMushroom -> Color(0xFFE89F71)   // Warm ochre salmon
        ThemeStyle.TerrazzoMinimalist -> Color(0xFF3F4A5C) // Slate — the only palette without a hue
        ThemeStyle.TerrazzoKiosk -> Color(0xFF00897B)      // Teal 600, WCAG-AA contrast on dark
    }
