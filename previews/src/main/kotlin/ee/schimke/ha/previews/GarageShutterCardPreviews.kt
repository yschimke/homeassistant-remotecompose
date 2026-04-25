@file:Suppress("RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.remote.tooling.preview.RemotePreview
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.androidXExperimental
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.cards.shutter.withGarageShutter
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.ProvideHaTheme
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Previews for `custom:garage-shutter-card`.
 *
 * The card has no HA reference capture (HA's frontend doesn't ship a
 * native garage card — this is our thing), so the canvas is tight to
 * content per [CardPreviews] rule 3.
 *
 * **Animation strip** — [Garage_Animation_Light] / [Garage_Animation_Dark]
 * fan out via [GarageDoorPositionsProvider] across the five-frame
 * 0% / 25% / 50% / 75% / 100% open cycle. Reading the resulting PNGs in
 * order is how we verify the door rises smoothly: groove spacing
 * compresses, motion arrow stays centred, frame doesn't clip at any
 * intermediate height.
 *
 * **State strip** — [Garage_State_Light] / [Garage_State_Dark] fan out
 * via [GarageDoorStatesProvider] over closed / opening / open / closing
 * / unavailable so each branch of the converter's state-derivation
 * produces a frame.
 *
 * **Two-entity** — [Garage_TwoEntities_Light] / `_Dark` covers the
 * card-with-title + multi-row layout (one door open, one closed) the
 * shutter card preview already covers for windows.
 */
// Single-entity canvas: name (13) + viz/buttons row (108 — buttons
// stack three 32-dp circles + 6-dp gaps) + state (11) + spacing (8)
// + card padding (20) ≈ 160 dp; pad to 168 so the state label has
// breathing room and the bottom rounded corner reads cleanly.
private const val SINGLE_WIDTH_DP = 200
private const val SINGLE_HEIGHT_DP = 172

@Composable
private fun GarageHost(theme: HaTheme, content: @Composable () -> Unit) {
    RemotePreview(profile = androidXExperimental) {
        ProvideCardRegistry(defaultRegistry().withGarageShutter()) {
            ProvideHaTheme(theme) { content() }
        }
    }
}

// ——— Animation strip (position 0/25/50/75/100) ———

@Preview(
    name = "garage-anim (light)",
    showBackground = false,
    widthDp = SINGLE_WIDTH_DP,
    heightDp = SINGLE_HEIGHT_DP,
)
@Composable
fun Garage_Animation_Light(
    @PreviewParameter(GarageDoorPositionsProvider::class) param: Pair<String, HaSnapshot>,
) = GarageHost(HaTheme.Light) {
    RenderChild(garageSingleCard(), param.second)
}

@Preview(
    name = "garage-anim (dark)",
    showBackground = false,
    widthDp = SINGLE_WIDTH_DP,
    heightDp = SINGLE_HEIGHT_DP,
)
@Composable
fun Garage_Animation_Dark(
    @PreviewParameter(GarageDoorPositionsProvider::class) param: Pair<String, HaSnapshot>,
) = GarageHost(HaTheme.Dark) {
    RenderChild(garageSingleCard(), param.second)
}

// ——— State strip (closed/opening/open/closing/unavailable) ———

@Preview(
    name = "garage-state (light)",
    showBackground = false,
    widthDp = SINGLE_WIDTH_DP,
    heightDp = SINGLE_HEIGHT_DP,
)
@Composable
fun Garage_State_Light(
    @PreviewParameter(GarageDoorStatesProvider::class) param: Pair<String, HaSnapshot>,
) = GarageHost(HaTheme.Light) {
    RenderChild(garageSingleCard(), param.second)
}

@Preview(
    name = "garage-state (dark)",
    showBackground = false,
    widthDp = SINGLE_WIDTH_DP,
    heightDp = SINGLE_HEIGHT_DP,
)
@Composable
fun Garage_State_Dark(
    @PreviewParameter(GarageDoorStatesProvider::class) param: Pair<String, HaSnapshot>,
) = GarageHost(HaTheme.Dark) {
    RenderChild(garageSingleCard(), param.second)
}

// ——— Multi-entity card with title ———

@Preview(
    name = "garage two-entities (light)",
    showBackground = false,
    widthDp = 280,
    heightDp = 340,
)
@Composable
fun Garage_TwoEntities_Light() = GarageHost(HaTheme.Light) {
    RenderChild(garageDoubleCard(), garageDoubleSnapshot())
}

@Preview(
    name = "garage two-entities (dark)",
    showBackground = false,
    widthDp = 280,
    heightDp = 340,
)
@Composable
fun Garage_TwoEntities_Dark() = GarageHost(HaTheme.Dark) {
    RenderChild(garageDoubleCard(), garageDoubleSnapshot())
}

private fun garageSingleCard() = card(
    """{
        "type":"custom:garage-shutter-card",
        "entities":[{"entity":"cover.garage","name":"Garage"}]
    }""",
)

private fun garageDoubleCard() = card(
    """{
        "type":"custom:garage-shutter-card",
        "title":"Garages",
        "entities":[
            {"entity":"cover.garage_main","name":"Main"},
            {"entity":"cover.garage_workshop","name":"Workshop"}
        ]
    }""",
)

private fun garageDoubleSnapshot(): HaSnapshot = snapshot(
    "cover.garage_main" to ee.schimke.ha.model.EntityState(
        entityId = "cover.garage_main",
        state = "open",
        attributes = JsonObject(
            mapOf(
                "friendly_name" to JsonPrimitive("Main"),
                "device_class" to JsonPrimitive("garage"),
                "current_position" to JsonPrimitive("100"),
            ),
        ),
    ),
    "cover.garage_workshop" to ee.schimke.ha.model.EntityState(
        entityId = "cover.garage_workshop",
        state = "closed",
        attributes = JsonObject(
            mapOf(
                "friendly_name" to JsonPrimitive("Workshop"),
                "device_class" to JsonPrimitive("garage"),
                "current_position" to JsonPrimitive("0"),
            ),
        ),
    ),
)
