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
 * Fixture-driven previews per card type. Each `@Preview` sets up a
 * `CardConfig` matching HA's YAML shape and renders through the default
 * registry — so we exercise the full dispatch path (including child
 * cards in stacks).
 *
 * Each preview wraps the card in a gray dashboard-ish surface with 16 dp
 * padding, mirroring HA's Lovelace view chrome and making the card's
 * actual bounds obvious vs. the empty canvas around it.
 *
 * Sized each preview tight to the card's natural content — we can't use
 * `wrapContentSize` on the player itself (yet), so these heights are
 * chosen to match the card + 2×16 dp chrome padding.
 */
private val DASHBOARD_BG = Color(0xFFE5E7EB)

@Composable
private fun CardFrame(content: @Composable () -> Unit) {
    RemotePreview(profile = androidXExperimental) {
        ProvideCardRegistry(defaultRegistry()) {
            RemoteBox(
                modifier = RemoteModifier
                    .fillMaxSize()
                    .background(DASHBOARD_BG.rc)
                    .padding(16.rdp),
                contentAlignment = RemoteAlignment.TopCenter,
            ) {
                content()
            }
        }
    }
}

@Preview(name = "button — toggle light", showBackground = true, widthDp = 180, heightDp = 180)
@Composable
fun Button_LightToggle() = CardFrame {
    RenderChild(
        card = card("""{"type":"button","entity":"light.kitchen","name":"Kitchen","show_name":true}"""),
        snapshot = Fixtures.kitchenLight,
    )
}

@Preview(name = "entity — temperature sensor", showBackground = true, widthDp = 360, heightDp = 96)
@Composable
fun Entity_TemperatureSensor() = CardFrame {
    RenderChild(
        card = card("""{"type":"entity","entity":"sensor.living_room"}"""),
        snapshot = Fixtures.livingRoomTemp,
    )
}

@Preview(name = "entities — mixed list", showBackground = true, widthDp = 360, heightDp = 196)
@Composable
fun Entities_MixedList() = CardFrame {
    RenderChild(
        card = card(
            """{"type":"entities","title":"Living Room","entities":["sensor.living_room","light.kitchen"]}""",
        ),
        snapshot = Fixtures.mixed,
    )
}

@Preview(name = "glance — mixed", showBackground = true, widthDp = 360, heightDp = 192)
@Composable
fun Glance_Mixed() = CardFrame {
    RenderChild(
        card = card(
            """{"type":"glance","title":"Overview","entities":["sensor.living_room","light.kitchen"]}""",
        ),
        snapshot = Fixtures.mixed,
    )
}

@Preview(name = "heading — title", showBackground = true, widthDp = 360, heightDp = 80)
@Composable
fun Heading_Title() = CardFrame {
    RenderChild(
        card = card("""{"type":"heading","heading":"Downstairs"}"""),
        snapshot = Fixtures.mixed,
    )
}

@Preview(name = "markdown — paragraph", showBackground = true, widthDp = 360, heightDp = 152)
@Composable
fun Markdown_Paragraph() = CardFrame {
    RenderChild(
        card = card(
            """{"type":"markdown","title":"Notes","content":"Welcome home.\nTemperature is normal."}""",
        ),
        snapshot = Fixtures.mixed,
    )
}

@Preview(name = "vertical-stack", showBackground = true, widthDp = 360, heightDp = 232)
@Composable
fun VerticalStack_TwoTiles() = CardFrame {
    RenderChild(
        card = card(
            """{"type":"vertical-stack","cards":[
                {"type":"tile","entity":"sensor.living_room"},
                {"type":"tile","entity":"light.kitchen"}
            ]}""",
        ),
        snapshot = Fixtures.mixed,
    )
}

@Preview(name = "horizontal-stack", showBackground = true, widthDp = 480, heightDp = 188)
@Composable
fun HorizontalStack_TwoButtons() = CardFrame {
    RenderChild(
        card = card(
            """{"type":"horizontal-stack","cards":[
                {"type":"button","entity":"light.kitchen","name":"Kitchen"},
                {"type":"button","entity":"sensor.living_room","name":"Temp","icon":"mdi:thermometer"}
            ]}""",
        ),
        snapshot = Fixtures.mixed,
    )
}

@Preview(name = "grid — 4 buttons", showBackground = true, widthDp = 360, heightDp = 340)
@Composable
fun Grid_FourButtons() = CardFrame {
    RenderChild(
        card = card(
            """{"type":"grid","cards":[
                {"type":"button","entity":"light.kitchen","name":"Kitchen"},
                {"type":"button","entity":"light.kitchen","name":"Office"},
                {"type":"button","entity":"sensor.living_room","name":"Temp","icon":"mdi:thermometer"},
                {"type":"button","entity":"sensor.living_room","name":"RH","icon":"mdi:water-percent"}
            ]}""",
        ),
        snapshot = Fixtures.mixed,
    )
}

@Preview(name = "unsupported — gauge", showBackground = true, widthDp = 200, heightDp = 152)
@Composable
fun Unsupported_Gauge() = CardFrame {
    RenderChild(
        card = card("""{"type":"gauge","entity":"sensor.living_room"}"""),
        snapshot = Fixtures.mixed,
    )
}
