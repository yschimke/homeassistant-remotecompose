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
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.ProvideHaTheme

/**
 * Previews for every card type, sized precisely to the card's natural
 * content. No chrome / no dashboard background around the card — the
 * PNG contains the card alone, same as the committed HA reference
 * captures.
 *
 * State-variant cards (light on/off/unavailable, cover closed/open/
 * opening, lock locked/unlocked/locking) fan out via
 * [PreviewParameterProvider] so one function produces one PNG per
 * state.
 */

@Composable
private fun CardHost(theme: HaTheme, content: @Composable () -> Unit) {
    RemotePreview(profile = androidXExperimental) {
        ProvideCardRegistry(defaultRegistry()) {
            ProvideHaTheme(theme) { content() }
        }
    }
}

// ——— button ———

@Preview(name = "button (light)", showBackground = false, widthDp = 136, heightDp = 93)
@Composable
fun Button_Light(
    @PreviewParameter(KitchenLightStatesProvider::class) param: Pair<String, HaSnapshot>,
) = CardHost(HaTheme.Light) {
    RenderChild(buttonCard(), param.second)
}

@Preview(name = "button (dark)", showBackground = false, widthDp = 136, heightDp = 93)
@Composable
fun Button_Dark(
    @PreviewParameter(KitchenLightStatesProvider::class) param: Pair<String, HaSnapshot>,
) = CardHost(HaTheme.Dark) {
    RenderChild(buttonCard(), param.second)
}

private fun buttonCard() = card(
    """{"type":"button","entity":"light.kitchen","name":"Kitchen","show_name":true}""",
)

// ——— entity ———

@Preview(name = "entity (light)", showBackground = false, widthDp = 328, heightDp = 18)
@Composable
fun Entity_Light() = CardHost(HaTheme.Light) {
    RenderChild(entityCard(), Fixtures.livingRoomTemp)
}

@Preview(name = "entity (dark)", showBackground = false, widthDp = 328, heightDp = 18)
@Composable
fun Entity_Dark() = CardHost(HaTheme.Dark) {
    RenderChild(entityCard(), Fixtures.livingRoomTemp)
}

private fun entityCard() = card("""{"type":"entity","entity":"sensor.living_room"}""")

// ——— entities ———

@Preview(name = "entities (light)", showBackground = false, widthDp = 328, heightDp = 156)
@Composable
fun Entities_Light() = CardHost(HaTheme.Light) {
    RenderChild(entitiesCard(), Fixtures.mixed)
}

@Preview(name = "entities (dark)", showBackground = false, widthDp = 328, heightDp = 156)
@Composable
fun Entities_Dark() = CardHost(HaTheme.Dark) {
    RenderChild(entitiesCard(), Fixtures.mixed)
}

// Matches `integration/config/ui-lovelace.yaml` view=entities.
private fun entitiesCard() = card(
    """{"type":"entities","title":"Living Room","entities":[
        "sensor.living_room",
        "light.kitchen",
        "switch.coffee_maker"
    ]}""",
)

// ——— glance ———

@Preview(name = "glance (light)", showBackground = false, widthDp = 260, heightDp = 120)
@Composable
fun Glance_Light() = CardHost(HaTheme.Light) {
    RenderChild(glanceCard(), Fixtures.mixed)
}

@Preview(name = "glance (dark)", showBackground = false, widthDp = 260, heightDp = 120)
@Composable
fun Glance_Dark() = CardHost(HaTheme.Dark) {
    RenderChild(glanceCard(), Fixtures.mixed)
}

// Matches `integration/config/ui-lovelace.yaml` view=glance.
private fun glanceCard() = card(
    """{"type":"glance","title":"Overview","entities":[
        "sensor.living_room",
        "light.kitchen",
        "lock.front_door"
    ]}""",
)

// ——— heading ———

@Preview(name = "heading (light)", showBackground = false, widthDp = 112, heightDp = 21)
@Composable
fun Heading_Light() = CardHost(HaTheme.Light) {
    RenderChild(headingCard(), Fixtures.mixed)
}

@Preview(name = "heading (dark)", showBackground = false, widthDp = 112, heightDp = 21)
@Composable
fun Heading_Dark() = CardHost(HaTheme.Dark) {
    RenderChild(headingCard(), Fixtures.mixed)
}

private fun headingCard() = card("""{"type":"heading","heading":"Downstairs"}""")

// ——— markdown ———

@Preview(name = "markdown (light)", showBackground = false, widthDp = 328, heightDp = 72)
@Composable
fun Markdown_Light() = CardHost(HaTheme.Light) {
    RenderChild(markdownCard(), Fixtures.mixed)
}

@Preview(name = "markdown (dark)", showBackground = false, widthDp = 328, heightDp = 72)
@Composable
fun Markdown_Dark() = CardHost(HaTheme.Dark) {
    RenderChild(markdownCard(), Fixtures.mixed)
}

private fun markdownCard() = card(
    """{"type":"markdown","title":"Notes","content":"Welcome home.\nTemperature is normal."}""",
)

// ——— vertical-stack ———

@Preview(name = "vertical-stack (light)", showBackground = false, widthDp = 232, heightDp = 100)
@Composable
fun VerticalStack_Light() = CardHost(HaTheme.Light) {
    RenderChild(verticalStackCard(), Fixtures.mixed)
}

@Preview(name = "vertical-stack (dark)", showBackground = false, widthDp = 232, heightDp = 100)
@Composable
fun VerticalStack_Dark() = CardHost(HaTheme.Dark) {
    RenderChild(verticalStackCard(), Fixtures.mixed)
}

private fun verticalStackCard() = card(
    """{"type":"vertical-stack","cards":[
        {"type":"tile","entity":"sensor.living_room"},
        {"type":"tile","entity":"light.kitchen"}
    ]}""",
)

// ——— horizontal-stack ———

@Preview(name = "horizontal-stack (light)", showBackground = false, widthDp = 320, heightDp = 93)
@Composable
fun HorizontalStack_Light() = CardHost(HaTheme.Light) {
    RenderChild(horizontalStackCard(), Fixtures.mixed)
}

@Preview(name = "horizontal-stack (dark)", showBackground = false, widthDp = 320, heightDp = 93)
@Composable
fun HorizontalStack_Dark() = CardHost(HaTheme.Dark) {
    RenderChild(horizontalStackCard(), Fixtures.mixed)
}

private fun horizontalStackCard() = card(
    """{"type":"horizontal-stack","cards":[
        {"type":"button","entity":"light.kitchen","name":"Kitchen"},
        {"type":"button","entity":"sensor.living_room","name":"Temp","icon":"mdi:thermometer"}
    ]}""",
)

// ——— grid ———

@Preview(name = "grid (light)", showBackground = false, widthDp = 300, heightDp = 240)
@Composable
fun Grid_Light() = CardHost(HaTheme.Light) {
    RenderChild(gridCard(), Fixtures.mixed)
}

@Preview(name = "grid (dark)", showBackground = false, widthDp = 300, heightDp = 240)
@Composable
fun Grid_Dark() = CardHost(HaTheme.Dark) {
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

@Preview(name = "unsupported (light)", showBackground = false, widthDp = 140, heightDp = 86)
@Composable
fun Unsupported_Light() = CardHost(HaTheme.Light) {
    RenderChild(unsupportedCard(), Fixtures.mixed)
}

@Preview(name = "unsupported (dark)", showBackground = false, widthDp = 140, heightDp = 86)
@Composable
fun Unsupported_Dark() = CardHost(HaTheme.Dark) {
    RenderChild(unsupportedCard(), Fixtures.mixed)
}

private fun unsupportedCard() = card("""{"type":"gauge","entity":"sensor.living_room"}""")

// ——— tile, state variants via PreviewParameter ———

@Preview(name = "tile light (light)", showBackground = false, widthDp = 328, heightDp = 43)
@Composable
fun Tile_Light_States(
    @PreviewParameter(KitchenLightStatesProvider::class) param: Pair<String, HaSnapshot>,
) = CardHost(HaTheme.Light) {
    RenderChild(card("""{"type":"tile","entity":"light.kitchen"}"""), param.second)
}

@Preview(name = "tile cover (light)", showBackground = false, widthDp = 328, heightDp = 43)
@Composable
fun Tile_Cover_States(
    @PreviewParameter(GarageCoverStatesProvider::class) param: Pair<String, HaSnapshot>,
) = CardHost(HaTheme.Light) {
    RenderChild(card("""{"type":"tile","entity":"cover.garage"}"""), param.second)
}

@Preview(name = "tile lock (light)", showBackground = false, widthDp = 328, heightDp = 43)
@Composable
fun Tile_Lock_States(
    @PreviewParameter(FrontDoorLockStatesProvider::class) param: Pair<String, HaSnapshot>,
) = CardHost(HaTheme.Light) {
    RenderChild(card("""{"type":"tile","entity":"lock.front_door"}"""), param.second)
}
