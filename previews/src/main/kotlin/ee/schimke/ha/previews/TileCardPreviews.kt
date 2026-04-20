package ee.schimke.ha.previews

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.ha.rc.cards.TileCardConverter

/**
 * Previews drive the pixel-parity loop: each fixture compiled by the
 * compose-preview plugin becomes a PNG under
 * `previews/build/compose-previews/renders/`. Compare against reference
 * PNGs captured from real HA (via hass-lovelace-screenshotter / Puppeteer).
 *
 * NOTE: once the RemoteCompose API for the wrapper scope stabilises, wrap
 * the converter's `Render` call in a RemoteCompose player composable so the
 * preview exercises the same byte stream that a production renderer would.
 */
@Preview(name = "tile — sensor temperature", showBackground = true, widthDp = 360, heightDp = 120)
@Composable
fun Tile_TemperatureSensor() {
    Box(Modifier.padding(16.dp)) {
        TileCardConverter().Render(
            card = card("""{"type":"tile","entity":"sensor.living_room_temperature"}"""),
            snapshot = Fixtures.livingRoomTemp,
        )
    }
}

@Preview(name = "tile — light on", showBackground = true, widthDp = 360, heightDp = 120)
@Composable
fun Tile_LightOn() {
    Box(Modifier.padding(16.dp)) {
        TileCardConverter().Render(
            card = card("""{"type":"tile","entity":"light.kitchen","color":"amber"}"""),
            snapshot = Fixtures.kitchenLight,
        )
    }
}
