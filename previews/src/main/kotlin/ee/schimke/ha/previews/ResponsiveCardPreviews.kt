@file:Suppress("RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.components.HaTheme

/**
 * Responsive tolerance previews using realistic widths a dashboard commonly hits:
 * narrow phone column, standard mobile width, and tablet pane.
 */

@Preview(name = "entities responsive narrow", widthDp = 260, heightDp = 180, showBackground = false)
@Preview(name = "entities responsive standard", widthDp = 381, heightDp = 180, showBackground = false)
@Preview(name = "entities responsive wide", widthDp = 480, heightDp = 180, showBackground = false)
@Composable
fun Entities_Responsive_Light() = CardHost(HaTheme.Light) {
    RenderChild(
        card("""{"type":"entities","title":"Living Room and Kitchen Devices with Very Long Heading","entities":["sensor.living_room","light.kitchen","switch.coffee_maker"]}"""),
        Fixtures.mixed,
        RemoteModifier.fillMaxWidth(),
    )
}

@Preview(name = "glance responsive narrow", widthDp = 260, heightDp = 170, showBackground = false)
@Preview(name = "glance responsive standard", widthDp = 381, heightDp = 170, showBackground = false)
@Preview(name = "glance responsive wide", widthDp = 480, heightDp = 170, showBackground = false)
@Composable
fun Glance_Responsive_Dark() = CardHost(HaTheme.Dark) {
    RenderChild(
        card("""{"type":"glance","title":"Overview and Alerting Summary for Main Floor","entities":["sensor.living_room","light.kitchen","lock.front_door"]}"""),
        Fixtures.mixed,
        RemoteModifier.fillMaxWidth(),
    )
}

@Preview(name = "markdown responsive narrow", widthDp = 260, heightDp = 140, showBackground = false)
@Preview(name = "markdown responsive standard", widthDp = 381, heightDp = 140, showBackground = false)
@Composable
fun Markdown_Responsive_Light() = CardHost(HaTheme.Light) {
    RenderChild(
        card("""{"type":"markdown","title":"Notes for This Evening and Tomorrow","content":"Welcome home.\n- Dishwasher running\n- Back door locked"}"""),
        Fixtures.mixed,
        RemoteModifier.fillMaxWidth(),
    )
}

@Preview(name = "tile responsive narrow", widthDp = 150, heightDp = 48, showBackground = false)
@Preview(name = "tile responsive standard", widthDp = 187, heightDp = 48, showBackground = false)
@Preview(name = "tile responsive wide", widthDp = 260, heightDp = 48, showBackground = false)
@Composable
fun Tile_Responsive_Dark() = CardHost(HaTheme.Dark) {
    RenderChild(card("""{"type":"tile","entity":"sensor.living_room"}"""), Fixtures.mixed, RemoteModifier.fillMaxWidth())
}
