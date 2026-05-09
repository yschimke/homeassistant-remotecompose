@file:Suppress("COMPOSE_APPLIER_CALL_MISMATCH", "RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth as uiFillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.tooling.preview.RemotePreview
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.androidXExperimental
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.haThemeFor
import ee.schimke.ha.rc.components.terrazzoColorScheme
import ee.schimke.ha.rc.components.terrazzoTypographyFor

/**
 * Palette showcases — one preview per (ThemeStyle, darkMode) pair. Each
 * renders, top-to-bottom:
 *
 *  1. A titled header using `MaterialTheme.typography.titleLarge` in
 *     `primary` — shows the chosen Google Font pairing.
 *  2. The Material 3 key-role swatches (primary / primaryContainer /
 *     secondary / tertiary / surfaceVariant / outline).
 *  3. The full M3 surface-elevation strip (`surface` →
 *     `surfaceContainerLowest` → `…Low` → `…` → `…High` → `…Highest`)
 *     so the elevation hierarchy `haThemeFor` reads from is visible at
 *     a glance — it's the lever that breaks the "everything is one
 *     colour" feel called out in the issue.
 *  4. A two-card stack rendered on the dashboard layer: an HA tile and
 *     an HA entities row, hosted via `RemotePreview` under
 *     `haThemeFor`. Because cards now sit on `surfaceContainerLow` and
 *     the dashboard sits on `surface`, the tinted lift is real, not
 *     mocked.
 */

// ——— Material 3 defaults ———

@Preview(name = "theme: Material3 (light)", widthDp = 360, heightDp = 480, showBackground = false)
@Composable
fun Theme_Material3_Light() = ThemeShowcase(ThemeStyle.Material3, darkTheme = false)

@Preview(name = "theme: Material3 (dark)", widthDp = 360, heightDp = 480, showBackground = false)
@Composable
fun Theme_Material3_Dark() = ThemeShowcase(ThemeStyle.Material3, darkTheme = true)

// ——— Terrazzo Home (HA blue + Roboto Flex/Inter) ———

@Preview(name = "theme: Home (light)", widthDp = 360, heightDp = 480, showBackground = false)
@Composable
fun Theme_Home_Light() = ThemeShowcase(ThemeStyle.TerrazzoHome, darkTheme = false)

@Preview(name = "theme: Home (dark)", widthDp = 360, heightDp = 480, showBackground = false)
@Composable
fun Theme_Home_Dark() = ThemeShowcase(ThemeStyle.TerrazzoHome, darkTheme = true)

// ——— Terrazzo Mushroom (salmon + Figtree) ———

@Preview(name = "theme: Mushroom (light)", widthDp = 360, heightDp = 480, showBackground = false)
@Composable
fun Theme_Mushroom_Light() = ThemeShowcase(ThemeStyle.TerrazzoMushroom, darkTheme = false)

@Preview(name = "theme: Mushroom (dark)", widthDp = 360, heightDp = 480, showBackground = false)
@Composable
fun Theme_Mushroom_Dark() = ThemeShowcase(ThemeStyle.TerrazzoMushroom, darkTheme = true)

// ——— Terrazzo Minimalist (slate + IBM Plex Sans) ———

@Preview(name = "theme: Minimalist (light)", widthDp = 360, heightDp = 480, showBackground = false)
@Composable
fun Theme_Minimalist_Light() = ThemeShowcase(ThemeStyle.TerrazzoMinimalist, darkTheme = false)

@Preview(name = "theme: Minimalist (dark)", widthDp = 360, heightDp = 480, showBackground = false)
@Composable
fun Theme_Minimalist_Dark() = ThemeShowcase(ThemeStyle.TerrazzoMinimalist, darkTheme = true)

// ——— Terrazzo Kiosk (teal + Atkinson Hyperlegible, dark-only context) ———

@Preview(name = "theme: Kiosk (light)", widthDp = 360, heightDp = 480, showBackground = false)
@Composable
fun Theme_Kiosk_Light() = ThemeShowcase(ThemeStyle.TerrazzoKiosk, darkTheme = false)

@Preview(name = "theme: Kiosk (dark)", widthDp = 360, heightDp = 480, showBackground = false)
@Composable
fun Theme_Kiosk_Dark() = ThemeShowcase(ThemeStyle.TerrazzoKiosk, darkTheme = true)

@Composable
private fun ThemeShowcase(style: ThemeStyle, darkTheme: Boolean) {
    val colors = terrazzoColorScheme(style, darkTheme)
    val typography = terrazzoTypographyFor(style)
    val haTheme: HaTheme = haThemeFor(style, darkTheme)

    MaterialTheme(colorScheme = colors, typography = typography) {
        // Page is `dashboardBackground` so the surface-stack labels read
        // against the same neutral the cards sit on.
        Surface(color = haTheme.dashboardBackground) {
            Column(
                modifier = Modifier.uiFillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column {
                    Text(
                        text = displayName(style),
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.primary,
                    )
                    Text(
                        text = "${if (darkTheme) "Dark" else "Light"} · " + tagline(style),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                }

                // Key M3 role swatches.
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Swatch("P", colors.primary, colors.onPrimary)
                    Swatch("PC", colors.primaryContainer, colors.onPrimaryContainer)
                    Swatch("S", colors.secondary, colors.onSecondary)
                    Swatch("T", colors.tertiary, colors.onTertiary)
                    Swatch("Sv", colors.surfaceVariant, colors.onSurfaceVariant)
                    Swatch("O", colors.outline, colors.onSurface)
                }

                // M3 surface elevation strip. Bands left-to-right are
                // `surface` plus the five `surfaceContainer*` tokens.
                // For elevated themes (Material3 / Mushroom / Kiosk) the
                // outline highlights `Cn` (section group) and `Hi`
                // (card) — the two tokens `haThemeFor` reads from for
                // those palettes. Flat themes (Home / Minimalist) ignore
                // these and use `surface` as cardBackground, so the
                // highlight is informational.
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SurfaceBand("surface", colors.surface, colors.onSurface)
                    SurfaceBand("Lo-", colors.surfaceContainerLowest, colors.onSurface)
                    SurfaceBand("Lo", colors.surfaceContainerLow, colors.onSurface)
                    SurfaceBand("Cn", colors.surfaceContainer, colors.onSurface, highlight = true)
                    SurfaceBand("Hi", colors.surfaceContainerHigh, colors.onSurface, highlight = true)
                    SurfaceBand("Hi+", colors.surfaceContainerHighest, colors.onSurface)
                }

                // Two HA cards stacked inside a section-group surface
                // (`sectionBackground`), itself sitting on the dashboard
                // layer (`dashboardBackground`). For Material3 / Mushroom
                // / Kiosk the three layers read as three distinct tints;
                // for Home / Minimalist `sectionBackground` equals
                // `dashboardBackground`, so the wrap reads as a no-op
                // and the flat HA / matt8707 look is preserved.
                Column(
                    modifier = Modifier
                        .uiFillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(haTheme.sectionBackground)
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .uiFillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(haTheme.cardBackground),
                    ) {
                        RemotePreview(profile = androidXExperimental) {
                            ProvideCardRegistry(defaultRegistry()) {
                                ProvideHaTheme(haTheme) {
                                    RenderChild(
                                        card("""{"type":"tile","entity":"sensor.living_room"}"""),
                                        Fixtures.livingRoomTemp,
                                        RemoteModifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .uiFillMaxWidth()
                            .height(96.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(haTheme.cardBackground),
                    ) {
                        RemotePreview(profile = androidXExperimental) {
                            ProvideCardRegistry(defaultRegistry()) {
                                ProvideHaTheme(haTheme) {
                                    RenderChild(
                                        card("""{"type":"tile","entity":"light.kitchen"}"""),
                                        Fixtures.kitchenLight,
                                        RemoteModifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Swatch(label: String, color: Color, onColor: Color) {
    Box(
        modifier = Modifier
            .size(width = 48.dp, height = 36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(color),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = onColor,
            modifier = Modifier.padding(4.dp),
        )
    }
}

@Composable
private fun RowScope.SurfaceBand(
    label: String,
    color: Color,
    onColor: Color,
    highlight: Boolean = false,
) {
    val shape = RoundedCornerShape(6.dp)
    val outline = MaterialTheme.colorScheme.outline
    val base = Modifier.weight(1f).height(32.dp).clip(shape).background(color)
    Box(
        modifier = if (highlight) base.border(1.dp, outline, shape) else base,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = onColor,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

private fun displayName(style: ThemeStyle): String = when (style) {
    ThemeStyle.Material3 -> "Material 3"
    ThemeStyle.TerrazzoHome -> "Home"
    ThemeStyle.TerrazzoMushroom -> "Mushroom"
    ThemeStyle.TerrazzoMinimalist -> "Minimalist"
    ThemeStyle.TerrazzoKiosk -> "Kiosk"
}

private fun tagline(style: ThemeStyle): String = when (style) {
    ThemeStyle.Material3 -> "defaults"
    ThemeStyle.TerrazzoHome -> "HA blue · Roboto Flex + Inter"
    ThemeStyle.TerrazzoMushroom -> "warm salmon · Figtree"
    ThemeStyle.TerrazzoMinimalist -> "neutral slate · IBM Plex Sans"
    ThemeStyle.TerrazzoKiosk -> "teal · Atkinson Hyperlegible"
}
