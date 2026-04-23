@file:Suppress("RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.remote.tooling.preview.RemotePreview
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.androidXExperimental
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.cards.shutter.withEnhancedShutter
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.ProvideHaTheme
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Previews for `custom:enhanced-shutter-card`. No HA reference capture
 * yet, so the canvas is tight-to-content — follows rule 3 in
 * [CardPreviews] ("no ref = tight bounds").
 *
 * Also demonstrates the registry extension seam: the host builds
 * `defaultRegistry().withEnhancedShutter()` instead of the plain
 * default, and the rest of the rendering path is unchanged.
 */
private const val CARD_WIDTH_DP = 280
private const val CARD_HEIGHT_DP = 300

@Composable
private fun ShutterHost(theme: HaTheme, content: @Composable () -> Unit) {
    RemotePreview(profile = androidXExperimental) {
        ProvideCardRegistry(defaultRegistry().withEnhancedShutter()) {
            ProvideHaTheme(theme) { content() }
        }
    }
}

@Preview(name = "shutter (light)", showBackground = false, widthDp = CARD_WIDTH_DP, heightDp = CARD_HEIGHT_DP)
@Composable
fun Shutter_Light() = ShutterHost(HaTheme.Light) {
    RenderChild(shutterCard(), shutterSnapshot())
}

@Preview(name = "shutter (dark)", showBackground = false, widthDp = CARD_WIDTH_DP, heightDp = CARD_HEIGHT_DP)
@Composable
fun Shutter_Dark() = ShutterHost(HaTheme.Dark) {
    RenderChild(shutterCard(), shutterSnapshot())
}

private fun shutterCard() = card(
    """{
        "type":"custom:enhanced-shutter-card",
        "title":"Shutters",
        "entities":[
            {"entity":"cover.bedroom","name":"Bedroom"},
            {"entity":"cover.kitchen","name":"Kitchen"}
        ]
    }""",
)

private fun shutterSnapshot(): HaSnapshot = snapshot(
    "cover.bedroom" to EntityState(
        entityId = "cover.bedroom",
        state = "open",
        attributes = JsonObject(mapOf("current_position" to JsonPrimitive("70"))),
    ),
    "cover.kitchen" to EntityState(
        entityId = "cover.kitchen",
        state = "closed",
        attributes = JsonObject(mapOf("current_position" to JsonPrimitive("10"))),
    ),
)
