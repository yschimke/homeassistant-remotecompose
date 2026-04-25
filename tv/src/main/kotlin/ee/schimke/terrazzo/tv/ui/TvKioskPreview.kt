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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ee.schimke.ha.rc.components.ThemeStyle
import kotlinx.coroutines.delay

/**
 * A miniature kiosk dashboard that re-skins itself live as the picker
 * selection changes. When [demoMode] is on the tile values come from
 * [TvDemoData] and tick on a 2 s cadence so the room sees motion; when
 * off, the tiles show a static "live data placeholder" until full HA
 * wiring lands.
 */
@Composable
fun TvKioskPreview(style: ThemeStyle, demoMode: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(demoMode) {
        if (!demoMode) return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(2_000)
        }
    }

    val tiles = if (demoMode) TvDemoData.tiles(nowMs) else PLACEHOLDER_TILES
    val containers = listOf(
        cs.primaryContainer to cs.onPrimaryContainer,
        cs.secondaryContainer to cs.onSecondaryContainer,
        cs.tertiaryContainer to cs.onTertiaryContainer,
    )

    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(24.dp))
            .background(cs.surfaceContainerLow)
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Living room",
                    style = MaterialTheme.typography.headlineMedium,
                    color = cs.onSurface,
                )
                if (demoMode) {
                    androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(cs.tertiary.copy(alpha = 0.18f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "DEMO",
                            style = MaterialTheme.typography.labelMedium,
                            color = cs.tertiary,
                        )
                    }
                }
            }
            Text(
                text = "Preview · ${style.displayName}" + if (demoMode) " · animated" else "",
                style = MaterialTheme.typography.titleSmall,
                color = cs.onSurfaceVariant,
            )
        }

        // Take the first three tiles for the headline row; they exercise
        // the primary / secondary / tertiary roles. Anything beyond
        // sits in a secondary band below.
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            tiles.take(3).forEachIndexed { i, tile ->
                val (container, onContainer) = containers[i % containers.size]
                Tile(
                    label = tile.label,
                    value = tile.value,
                    container = container,
                    onContainer = onContainer,
                    modifier = Modifier.width(220.dp),
                )
            }
        }
        if (tiles.size > 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                tiles.drop(3).forEach { tile ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(cs.surfaceContainerHigh)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Column {
                            Text(
                                text = tile.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = cs.onSurfaceVariant,
                            )
                            Text(
                                text = tile.value,
                                style = MaterialTheme.typography.bodyLarge,
                                color = cs.onSurface,
                            )
                        }
                    }
                }
            }
        }

        SwatchRow()
    }
}

private data class PlaceholderTile(val label: String, val value: String)

private val PLACEHOLDER_TILES = listOf(
    TvDemoData.Tile("Lights", "—"),
    TvDemoData.Tile("Climate", "—"),
    TvDemoData.Tile("Media", "—"),
)

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
