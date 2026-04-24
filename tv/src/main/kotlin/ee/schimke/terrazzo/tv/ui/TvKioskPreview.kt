package ee.schimke.terrazzo.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ee.schimke.ha.rc.components.ThemeStyle

/**
 * A miniature kiosk dashboard that re-skins itself live as the picker
 * selection changes. Three "tiles" plus a swatch row exercise primary /
 * secondary / tertiary roles plus surfaceContainer*; this is the
 * fastest way to spot which palette reads well at TV viewing distance
 * before wiring the real HA cards in a follow-up.
 */
@Composable
fun TvKioskPreview(style: ThemeStyle, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(24.dp))
            .background(cs.surfaceContainerLow)
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Living room",
                style = MaterialTheme.typography.headlineMedium,
                color = cs.onSurface,
            )
            Text(
                text = "Preview · ${style.displayName}",
                style = MaterialTheme.typography.titleSmall,
                color = cs.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Tile(
                label = "Lights",
                value = "ON · 70%",
                container = cs.primaryContainer,
                onContainer = cs.onPrimaryContainer,
                modifier = Modifier.width(220.dp),
            )
            Tile(
                label = "Climate",
                value = "21.5 °C",
                container = cs.secondaryContainer,
                onContainer = cs.onSecondaryContainer,
                modifier = Modifier.width(220.dp),
            )
            Tile(
                label = "Media",
                value = "Spotify",
                container = cs.tertiaryContainer,
                onContainer = cs.onTertiaryContainer,
                modifier = Modifier.width(220.dp),
            )
        }

        SwatchRow()
    }
}

@Composable
private fun Tile(
    label: String,
    value: String,
    container: Color,
    onContainer: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(container)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = onContainer.copy(alpha = 0.8f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            color = onContainer,
        )
    }
}

@Composable
private fun SwatchRow() {
    val cs = MaterialTheme.colorScheme
    val swatches = listOf(
        "primary" to cs.primary,
        "secondary" to cs.secondary,
        "tertiary" to cs.tertiary,
        "surface" to cs.surface,
        "outline" to cs.outline,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Roles",
            style = MaterialTheme.typography.titleSmall,
            color = cs.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            swatches.forEach { (name, color) ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(color),
                    )
                    Text(
                        text = name,
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
