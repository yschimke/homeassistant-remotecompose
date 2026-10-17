@file:Suppress("RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
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
 * Previews for `custom:enhanced-shutter-card`. No HA reference capture yet, so the canvas is
 * tight-to-content — follows rule 3 in [CardPreviews] ("no ref = tight bounds").
 *
 * Also demonstrates the registry extension seam: the host builds
 * `defaultRegistry().withEnhancedShutter()` instead of the plain default, and the rest of the
 * rendering path is unchanged.
 */
private const val CARD_WIDTH_DP = 280
private const val CARD_HEIGHT_DP = 300

@Composable
private fun ShutterHost(theme: HaTheme, content: @Composable () -> Unit) {
  CapturingRemoteContentPreview(profile = androidXExperimental) {
    ProvideCardRegistry(defaultRegistry().withEnhancedShutter()) {
      ProvideHaTheme(theme) { content() }
    }
  }
}

@Preview(
  name = "shutter (light)",
  showBackground = false,
  widthDp = CARD_WIDTH_DP,
  heightDp = CARD_HEIGHT_DP,
)
@Composable
fun Shutter_Light() = ShutterHost(HaTheme.Light) { RenderChild(shutterCard(), shutterSnapshot()) }

@Preview(
  name = "shutter (dark)",
  showBackground = false,
  widthDp = CARD_WIDTH_DP,
  heightDp = CARD_HEIGHT_DP,
)
@Composable
fun Shutter_Dark() = ShutterHost(HaTheme.Dark) { RenderChild(shutterCard(), shutterSnapshot()) }

// ——— Garage door through the enhanced shutter card (demo Security dashboard) ———

// Name + door/buttons row (three 32-dp buttons) + state line + padding.
private const val GARAGE_WIDTH_DP = 280
private const val GARAGE_HEIGHT_DP = 176

/**
 * The demo's garage door card exactly as configured there: `button_*_hide_states` leave only the
 * button that does something (up when closed, stop while moving, down when open).
 */
@Preview(
  name = "shutter garage demo config",
  showBackground = false,
  widthDp = GARAGE_WIDTH_DP,
  heightDp = GARAGE_HEIGHT_DP,
)
@Composable
fun Shutter_GarageDemo_States(
  @PreviewParameter(GarageDoorControlStatesProvider::class) param: Pair<String, HaSnapshot>
) = ShutterHost(HaTheme.Light) { RenderChild(garageDemoCard(), param.second) }

/**
 * The same door without hide states: all three buttons stay, and the ones that would do nothing are
 * greyed (close on a closed door, open on an open one, stop at rest).
 */
@Preview(
  name = "shutter garage controls (light)",
  showBackground = false,
  widthDp = GARAGE_WIDTH_DP,
  heightDp = GARAGE_HEIGHT_DP,
)
@Composable
fun Shutter_GarageControls_Light(
  @PreviewParameter(GarageDoorControlStatesProvider::class) param: Pair<String, HaSnapshot>
) = ShutterHost(HaTheme.Light) { RenderChild(garagePlainCard(), param.second) }

@Preview(
  name = "shutter garage controls (dark)",
  showBackground = false,
  widthDp = GARAGE_WIDTH_DP,
  heightDp = GARAGE_HEIGHT_DP,
)
@Composable
fun Shutter_GarageControls_Dark(
  @PreviewParameter(GarageDoorControlStatesProvider::class) param: Pair<String, HaSnapshot>
) = ShutterHost(HaTheme.Dark) { RenderChild(garagePlainCard(), param.second) }

private fun garageDemoCard() =
  card(
    """{
        "type":"custom:enhanced-shutter-card",
        "entities":[{
            "entity":"cover.garage","name":"Garage Door",
            "button_up_hide_states":["open","opening","closing"],
            "button_stop_hide_states":["open","closed","partial_open"],
            "button_down_hide_states":["closed","opening","closing"]
        }]
    }"""
  )

private fun garagePlainCard() =
  card(
    """{
        "type":"custom:enhanced-shutter-card",
        "entities":[{"entity":"cover.garage","name":"Garage Door"}]
    }"""
  )

private fun shutterCard() =
  card(
    """{
        "type":"custom:enhanced-shutter-card",
        "title":"Shutters",
        "entities":[
            {"entity":"cover.bedroom","name":"Bedroom"},
            {"entity":"cover.kitchen","name":"Kitchen"}
        ]
    }"""
  )

private fun shutterSnapshot(): HaSnapshot =
  snapshot(
    "cover.bedroom" to
      EntityState(
        entityId = "cover.bedroom",
        state = "open",
        attributes = JsonObject(mapOf("current_position" to JsonPrimitive("70"))),
      ),
    "cover.kitchen" to
      EntityState(
        entityId = "cover.kitchen",
        state = "closed",
        attributes = JsonObject(mapOf("current_position" to JsonPrimitive("10"))),
      ),
  )
