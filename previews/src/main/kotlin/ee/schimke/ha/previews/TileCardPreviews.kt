package ee.schimke.ha.previews

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
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
 * Tile-only previews. Separate from [CardPreviews] because the integration
 * `TileCardPixelDiffTest` locates them by their existing function names
 * and the committed reference PNGs are keyed to the tile card
 * specifically.
 *
 * Ideally previews would use `wrapContentSize()` so the rendered PNG
 * fits the card exactly. The current RemoteCompose player requires a
 * bounded size, so we pad the canvas and center the card instead.
 */
@Composable
private fun TileFrame(theme: HaTheme, content: @Composable () -> Unit) {
    RemotePreview(profile = androidXExperimental) {
        ProvideCardRegistry(defaultRegistry()) {
            ProvideHaTheme(theme) {
                RemoteBox(
                    modifier = RemoteModifier
                        .fillMaxSize()
                        .background(theme.dashboardBackground.rc)
                        .padding(8.rdp),
                    contentAlignment = RemoteAlignment.TopStart,
                ) {
                    content()
                }
            }
        }
    }
}

@Preview(name = "tile — sensor temperature", showBackground = true, widthDp = 232, heightDp = 88)
@Composable
fun Tile_TemperatureSensor() = TileFrame(HaTheme.Light) {
    RenderChild(
        card = card("""{"type":"tile","entity":"sensor.living_room"}"""),
        snapshot = Fixtures.livingRoomTemp,
    )
}

@Preview(name = "tile — sensor temperature (dark)", showBackground = true, widthDp = 232, heightDp = 88)
@Composable
fun Tile_TemperatureSensor_Dark() = TileFrame(HaTheme.Dark) {
    RenderChild(
        card = card("""{"type":"tile","entity":"sensor.living_room"}"""),
        snapshot = Fixtures.livingRoomTemp,
    )
}

@Preview(name = "tile — light on", showBackground = true, widthDp = 232, heightDp = 88)
@Composable
fun Tile_LightOn() = TileFrame(HaTheme.Light) {
    RenderChild(
        card = card("""{"type":"tile","entity":"light.kitchen","color":"amber"}"""),
        snapshot = Fixtures.kitchenLight,
    )
}

@Preview(name = "tile — light on (dark)", showBackground = true, widthDp = 232, heightDp = 88)
@Composable
fun Tile_LightOn_Dark() = TileFrame(HaTheme.Dark) {
    RenderChild(
        card = card("""{"type":"tile","entity":"light.kitchen","color":"amber"}"""),
        snapshot = Fixtures.kitchenLight,
    )
}
