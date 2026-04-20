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
 * Fixture-driven previews per card type, doubled into `_Light` / `_Dark`
 * variants. Each `@Preview` sets up a `CardConfig` matching HA's YAML
 * shape and renders through the default registry — so we exercise the
 * full dispatch path (including child cards in stacks).
 *
 * Each preview wraps the card in a dashboard-ish surface so the card's
 * actual bounds are obvious vs. the empty canvas around it. Canvas
 * sizes are fitted to each card's natural content (+ 8 dp of chrome
 * padding) — if a card grows or shrinks the canvas must follow.
 *
 * TODO(theme): when `androidx.compose.remote.core.operations.ColorTheme`
 * gets a public creation-side DSL, collapse both variants into a single
 * `.rc` document whose player switches palette at playback. See
 * [ee.schimke.ha.rc.components.HaTheme].
 */
@Composable
private fun CardFrame(theme: HaTheme, content: @Composable () -> Unit) {
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

// ——— button ———

@Preview(name = "button — toggle light (light)", showBackground = true, widthDp = 136, heightDp = 152)
@Composable
fun Button_LightToggle_Light() = CardFrame(HaTheme.Light) {
    RenderChild(buttonCard(), Fixtures.kitchenLight)
}

@Preview(name = "button — toggle light (dark)", showBackground = true, widthDp = 136, heightDp = 152)
@Composable
fun Button_LightToggle_Dark() = CardFrame(HaTheme.Dark) {
    RenderChild(buttonCard(), Fixtures.kitchenLight)
}

private fun buttonCard() = card(
    """{"type":"button","entity":"light.kitchen","name":"Kitchen","show_name":true}""",
)

// ——— entity ———

@Preview(name = "entity — temperature (light)", showBackground = true, widthDp = 320, heightDp = 64)
@Composable
fun Entity_TemperatureSensor_Light() = CardFrame(HaTheme.Light) {
    RenderChild(entityCard(), Fixtures.livingRoomTemp)
}

@Preview(name = "entity — temperature (dark)", showBackground = true, widthDp = 320, heightDp = 64)
@Composable
fun Entity_TemperatureSensor_Dark() = CardFrame(HaTheme.Dark) {
    RenderChild(entityCard(), Fixtures.livingRoomTemp)
}

private fun entityCard() = card("""{"type":"entity","entity":"sensor.living_room"}""")

// ——— entities ———

@Preview(name = "entities — mixed list (light)", showBackground = true, widthDp = 360, heightDp = 184)
@Composable
fun Entities_MixedList_Light() = CardFrame(HaTheme.Light) {
    RenderChild(entitiesCard(), Fixtures.mixed)
}

@Preview(name = "entities — mixed list (dark)", showBackground = true, widthDp = 360, heightDp = 184)
@Composable
fun Entities_MixedList_Dark() = CardFrame(HaTheme.Dark) {
    RenderChild(entitiesCard(), Fixtures.mixed)
}

private fun entitiesCard() = card(
    """{"type":"entities","title":"Living Room","entities":["sensor.living_room","light.kitchen"]}""",
)

// ——— glance ———

@Preview(name = "glance — mixed (light)", showBackground = true, widthDp = 232, heightDp = 168)
@Composable
fun Glance_Mixed_Light() = CardFrame(HaTheme.Light) {
    RenderChild(glanceCard(), Fixtures.mixed)
}

@Preview(name = "glance — mixed (dark)", showBackground = true, widthDp = 232, heightDp = 168)
@Composable
fun Glance_Mixed_Dark() = CardFrame(HaTheme.Dark) {
    RenderChild(glanceCard(), Fixtures.mixed)
}

private fun glanceCard() = card(
    """{"type":"glance","title":"Overview","entities":["sensor.living_room","light.kitchen"]}""",
)

// ——— heading ———

@Preview(name = "heading — title (light)", showBackground = true, widthDp = 200, heightDp = 56)
@Composable
fun Heading_Title_Light() = CardFrame(HaTheme.Light) {
    RenderChild(headingCard(), Fixtures.mixed)
}

@Preview(name = "heading — title (dark)", showBackground = true, widthDp = 200, heightDp = 56)
@Composable
fun Heading_Title_Dark() = CardFrame(HaTheme.Dark) {
    RenderChild(headingCard(), Fixtures.mixed)
}

private fun headingCard() = card("""{"type":"heading","heading":"Downstairs"}""")

// ——— markdown ———

@Preview(name = "markdown — paragraph (light)", showBackground = true, widthDp = 320, heightDp = 128)
@Composable
fun Markdown_Paragraph_Light() = CardFrame(HaTheme.Light) {
    RenderChild(markdownCard(), Fixtures.mixed)
}

@Preview(name = "markdown — paragraph (dark)", showBackground = true, widthDp = 320, heightDp = 128)
@Composable
fun Markdown_Paragraph_Dark() = CardFrame(HaTheme.Dark) {
    RenderChild(markdownCard(), Fixtures.mixed)
}

private fun markdownCard() = card(
    """{"type":"markdown","title":"Notes","content":"Welcome home.\nTemperature is normal."}""",
)

// ——— vertical-stack ———

@Preview(name = "vertical-stack (light)", showBackground = true, widthDp = 232, heightDp = 168)
@Composable
fun VerticalStack_TwoTiles_Light() = CardFrame(HaTheme.Light) {
    RenderChild(verticalStackCard(), Fixtures.mixed)
}

@Preview(name = "vertical-stack (dark)", showBackground = true, widthDp = 232, heightDp = 168)
@Composable
fun VerticalStack_TwoTiles_Dark() = CardFrame(HaTheme.Dark) {
    RenderChild(verticalStackCard(), Fixtures.mixed)
}

private fun verticalStackCard() = card(
    """{"type":"vertical-stack","cards":[
        {"type":"tile","entity":"sensor.living_room"},
        {"type":"tile","entity":"light.kitchen"}
    ]}""",
)

// ——— horizontal-stack ———

@Preview(name = "horizontal-stack (light)", showBackground = true, widthDp = 300, heightDp = 168)
@Composable
fun HorizontalStack_TwoButtons_Light() = CardFrame(HaTheme.Light) {
    RenderChild(horizontalStackCard(), Fixtures.mixed)
}

@Preview(name = "horizontal-stack (dark)", showBackground = true, widthDp = 300, heightDp = 168)
@Composable
fun HorizontalStack_TwoButtons_Dark() = CardFrame(HaTheme.Dark) {
    RenderChild(horizontalStackCard(), Fixtures.mixed)
}

private fun horizontalStackCard() = card(
    """{"type":"horizontal-stack","cards":[
        {"type":"button","entity":"light.kitchen","name":"Kitchen"},
        {"type":"button","entity":"sensor.living_room","name":"Temp","icon":"mdi:thermometer"}
    ]}""",
)

// ——— grid ———

@Preview(name = "grid — 4 buttons (light)", showBackground = true, widthDp = 300, heightDp = 300)
@Composable
fun Grid_FourButtons_Light() = CardFrame(HaTheme.Light) {
    RenderChild(gridCard(), Fixtures.mixed)
}

@Preview(name = "grid — 4 buttons (dark)", showBackground = true, widthDp = 300, heightDp = 300)
@Composable
fun Grid_FourButtons_Dark() = CardFrame(HaTheme.Dark) {
    RenderChild(gridCard(), Fixtures.mixed)
}

private fun gridCard() = card(
    """{"type":"grid","cards":[
        {"type":"button","entity":"light.kitchen","name":"Kitchen"},
        {"type":"button","entity":"light.kitchen","name":"Office"},
        {"type":"button","entity":"sensor.living_room","name":"Temp","icon":"mdi:thermometer"},
        {"type":"button","entity":"sensor.living_room","name":"RH","icon":"mdi:water-percent"}
    ]}""",
)

// ——— unsupported placeholder ———

@Preview(name = "unsupported — gauge (light)", showBackground = true, widthDp = 160, heightDp = 128)
@Composable
fun Unsupported_Gauge_Light() = CardFrame(HaTheme.Light) {
    RenderChild(unsupportedCard(), Fixtures.mixed)
}

@Preview(name = "unsupported — gauge (dark)", showBackground = true, widthDp = 160, heightDp = 128)
@Composable
fun Unsupported_Gauge_Dark() = CardFrame(HaTheme.Dark) {
    RenderChild(unsupportedCard(), Fixtures.mixed)
}

private fun unsupportedCard() = card("""{"type":"gauge","entity":"sensor.living_room"}""")
