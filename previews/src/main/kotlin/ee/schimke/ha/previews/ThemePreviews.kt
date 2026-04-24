@file:Suppress("RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
 * renders:
 *
 *  1. A titled header panel using `MaterialTheme.typography.titleLarge`
 *     in `primary` — shows the chosen Google Font pairing.
 *  2. A sample of the Material 3 token swatches (primary, secondary,
 *     surface, surfaceVariant, outline) so the palette's derived scheme
 *     is legible at a glance.
 *  3. A real HA tile card rendered via `RemotePreview` + `haThemeFor`,
 *     so the HA-card palette that drives pinned widgets is included in
 *     the same frame.
 *
 * Canvas width is 360 dp (phone-compact Android width); height is tall
 * enough to fit all three bands with breathing room.
 */

// ——— Material 3 defaults ———

@Preview(name = "theme: Material3 (light)", widthDp = 360, heightDp = 340, showBackground = false)
@Composable
fun Theme_Material3_Light() = ThemeShowcase(ThemeStyle.Material3, darkTheme = false)

@Preview(name = "theme: Material3 (dark)", widthDp = 360, heightDp = 340, showBackground = false)
@Composable
fun Theme_Material3_Dark() = ThemeShowcase(ThemeStyle.Material3, darkTheme = true)

// ——— Terrazzo Home (HA blue + Roboto Flex/Inter) ———

@Preview(name = "theme: Home (light)", widthDp = 360, heightDp = 340, showBackground = false)
@Composable
fun Theme_Home_Light() = ThemeShowcase(ThemeStyle.TerrazzoHome, darkTheme = false)

@Preview(name = "theme: Home (dark)", widthDp = 360, heightDp = 340, showBackground = false)
@Composable
fun Theme_Home_Dark() = ThemeShowcase(ThemeStyle.TerrazzoHome, darkTheme = true)

// ——— Terrazzo Mushroom (salmon + Figtree) ———

@Preview(name = "theme: Mushroom (light)", widthDp = 360, heightDp = 340, showBackground = false)
@Composable
fun Theme_Mushroom_Light() = ThemeShowcase(ThemeStyle.TerrazzoMushroom, darkTheme = false)

@Preview(name = "theme: Mushroom (dark)", widthDp = 360, heightDp = 340, showBackground = false)
@Composable
fun Theme_Mushroom_Dark() = ThemeShowcase(ThemeStyle.TerrazzoMushroom, darkTheme = true)

// ——— Terrazzo Minimalist (slate + IBM Plex Sans) ———

@Preview(name = "theme: Minimalist (light)", widthDp = 360, heightDp = 340, showBackground = false)
@Composable
fun Theme_Minimalist_Light() = ThemeShowcase(ThemeStyle.TerrazzoMinimalist, darkTheme = false)

@Preview(name = "theme: Minimalist (dark)", widthDp = 360, heightDp = 340, showBackground = false)
@Composable
fun Theme_Minimalist_Dark() = ThemeShowcase(ThemeStyle.TerrazzoMinimalist, darkTheme = true)

// ——— Terrazzo Kiosk (teal + Atkinson Hyperlegible, dark-only context) ———

@Preview(name = "theme: Kiosk (light)", widthDp = 360, heightDp = 340, showBackground = false)
@Composable
fun Theme_Kiosk_Light() = ThemeShowcase(ThemeStyle.TerrazzoKiosk, darkTheme = false)

@Preview(name = "theme: Kiosk (dark)", widthDp = 360, heightDp = 340, showBackground = false)
@Composable
fun Theme_Kiosk_Dark() = ThemeShowcase(ThemeStyle.TerrazzoKiosk, darkTheme = true)

@Composable
private fun ThemeShowcase(style: ThemeStyle, darkTheme: Boolean) {
    val colors = terrazzoColorScheme(style, darkTheme)
    val typography = terrazzoTypographyFor(style)
    val haTheme: HaTheme = haThemeFor(style, darkTheme)

    MaterialTheme(colorScheme = colors, typography = typography) {
        Surface(color = colors.background) {
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

                // M3 swatch row
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Swatch("P", colors.primary, colors.onPrimary)
                    Swatch("PC", colors.primaryContainer, colors.onPrimaryContainer)
                    Swatch("S", colors.secondary, colors.onSecondary)
                    Swatch("T", colors.tertiary, colors.onTertiary)
                    Swatch("Su", colors.surfaceVariant, colors.onSurfaceVariant)
                    Swatch("O", colors.outline, colors.onSurface)
                }

                // Typography sample on a card
                Surface(
                    color = colors.surface,
                    contentColor = colors.onSurface,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.uiFillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Living room", style = MaterialTheme.typography.titleMedium)
                        Text("21.5°C · humidity 48%", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        Text("Updated 2 min ago", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                }

                // Real HA tile card under the derived HaTheme
                Box(
                    modifier = Modifier
                        .uiFillMaxWidth()
                        .height(48.dp)
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
