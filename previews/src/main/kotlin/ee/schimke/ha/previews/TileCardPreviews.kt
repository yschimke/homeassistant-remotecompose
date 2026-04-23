@file:Suppress("RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.remote.tooling.preview.RemotePreview
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.androidXExperimental
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.ProvideHaTheme

/**
 * Tile-only previews. Canvas sizing follows the same rule as
 * [CardPreviews] — see the "CANVAS SIZING RULE" block there. DO NOT
 * change these dimensions without updating both files together.
 *
 * Reference PNG is 492×112 px (captured at deviceScaleFactor=2 via
 * Puppeteer). Preview renders at density 2.625 (Robolectric default),
 * so canvas = 492/2.625 × 112/2.625 ≈ 187×43 dp.
 */
private const val TILE_WIDTH_DP = 187
private const val TILE_HEIGHT_DP = 43

@Composable
private fun TileHost(theme: HaTheme, content: @Composable () -> Unit) {
    RemotePreview(profile = androidXExperimental) {
        ProvideCardRegistry(defaultRegistry()) {
            ProvideHaTheme(theme) { content() }
        }
    }
}

@Preview(name = "tile — sensor temperature", showBackground = false, widthDp = TILE_WIDTH_DP, heightDp = TILE_HEIGHT_DP)
@Composable
fun Tile_TemperatureSensor() = TileHost(HaTheme.Light) {
    RenderChild(
        card = card("""{"type":"tile","entity":"sensor.living_room"}"""),
        snapshot = Fixtures.livingRoomTemp,
    )
}

@Preview(name = "tile — sensor temperature (dark)", showBackground = false, widthDp = TILE_WIDTH_DP, heightDp = TILE_HEIGHT_DP)
@Composable
fun Tile_TemperatureSensor_Dark() = TileHost(HaTheme.Dark) {
    RenderChild(
        card = card("""{"type":"tile","entity":"sensor.living_room"}"""),
        snapshot = Fixtures.livingRoomTemp,
    )
}

@Preview(name = "tile — light on", showBackground = false, widthDp = TILE_WIDTH_DP, heightDp = TILE_HEIGHT_DP)
@Composable
fun Tile_LightOn() = TileHost(HaTheme.Light) {
    RenderChild(
        card = card("""{"type":"tile","entity":"light.kitchen","color":"amber"}"""),
        snapshot = Fixtures.kitchenLight,
    )
}

@Preview(name = "tile — light on (dark)", showBackground = false, widthDp = TILE_WIDTH_DP, heightDp = TILE_HEIGHT_DP)
@Composable
fun Tile_LightOn_Dark() = TileHost(HaTheme.Dark) {
    RenderChild(
        card = card("""{"type":"tile","entity":"light.kitchen","color":"amber"}"""),
        snapshot = Fixtures.kitchenLight,
    )
}
