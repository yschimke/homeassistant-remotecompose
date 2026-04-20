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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.androidXExperimental
import ee.schimke.ha.rc.cards.defaultRegistry

/**
 * Tile-only previews. Separate from [CardPreviews] so the integration
 * `PixelDiffTest` can continue to locate them by their existing
 * `Tile_*` function names — the committed reference PNGs are named to
 * match.
 *
 * Ideally previews would use `wrapContentSize()` so the rendered PNG
 * fits the card exactly. The current RemoteCompose player requires a
 * bounded size, so we pad the canvas and center the card instead.
 */
private val DASHBOARD_BG = Color(0xFFE5E7EB)

@Composable
private fun TileFrame(content: @Composable () -> Unit) {
    RemotePreview(profile = androidXExperimental) {
        ProvideCardRegistry(defaultRegistry()) {
            RemoteBox(
                modifier = RemoteModifier
                    .fillMaxSize()
                    .background(DASHBOARD_BG.rc)
                    .padding(20.rdp),
                contentAlignment = RemoteAlignment.TopCenter,
            ) {
                content()
            }
        }
    }
}

@Preview(name = "tile — sensor temperature", showBackground = true, widthDp = 360, heightDp = 96)
@Composable
fun Tile_TemperatureSensor() = TileFrame {
    RenderChild(
        card = card("""{"type":"tile","entity":"sensor.living_room"}"""),
        snapshot = Fixtures.livingRoomTemp,
    )
}

@Preview(name = "tile — light on", showBackground = true, widthDp = 360, heightDp = 96)
@Composable
fun Tile_LightOn() = TileFrame {
    RenderChild(
        card = card("""{"type":"tile","entity":"light.kitchen","color":"amber"}"""),
        snapshot = Fixtures.kitchenLight,
    )
}
