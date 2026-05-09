package ee.schimke.terrazzo.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.TimeSource
import androidx.wear.compose.material3.TimeText
import androidx.wear.tooling.preview.devices.WearDevices
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.DashboardData
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import ee.schimke.terrazzo.wearsync.proto.PinnedCard
import ee.schimke.terrazzo.wearsync.proto.PinnedCardSet
import ee.schimke.terrazzo.wearsync.proto.PinnedSection
import ee.schimke.terrazzo.wearsync.proto.PinnedSectionSet
import ee.schimke.terrazzo.wearsync.proto.WearSettings

/**
 * Wear @Preview fixtures. Render the new sync-driven home screen, the
 * dashboards browse list, and the demo-banner variant. Wear sizes are
 * 192 / 192 dp (small round) — well under TV's 1800 px constraint.
 */

private val PINNED_FIXTURE = PinnedCardSet(
    cards = listOf(
        PinnedCard(
            baseUrl = "demo://terrazzo",
            card = CardSummary(
                type = "tile",
                title = "Living Room",
                primaryEntityId = "sensor.living_room",
            ),
            cardKey = "k0",
            orderIndex = 0,
        ),
        PinnedCard(
            baseUrl = "demo://terrazzo",
            card = CardSummary(
                type = "tile",
                title = "Kitchen",
                primaryEntityId = "light.kitchen",
            ),
            cardKey = "k1",
            orderIndex = 2,
        ),
        PinnedCard(
            baseUrl = "demo://terrazzo",
            card = CardSummary(
                type = "tile",
                title = "Front door",
                primaryEntityId = "lock.front_door",
            ),
            cardKey = "k2",
            orderIndex = 3,
        ),
    ),
)

private val SECTIONS_FIXTURE = PinnedSectionSet(
    sections = listOf(
        PinnedSection(
            baseUrl = "demo://terrazzo",
            dashboardUrlPath = "",
            viewPath = "view-0",
            sectionIndex = 0,
            title = "Climate",
            cards = listOf(
                CardSummary(type = "tile", title = "Living Room", primaryEntityId = "sensor.living_room"),
                CardSummary(type = "tile", title = "Bedroom", primaryEntityId = "sensor.bedroom"),
            ),
            sectionKey = "s0",
            orderIndex = 1,
        ),
    ),
)

private val VALUES_FIXTURE = mapOf(
    "sensor.living_room" to EntityValue(
        state = "21.5",
        friendlyName = "Living Room",
        unit = "°C",
        deviceClass = "temperature",
    ),
    "light.kitchen" to EntityValue(state = "on", friendlyName = "Kitchen"),
    "lock.front_door" to EntityValue(state = "locked", friendlyName = "Front door"),
)

private val DASHBOARDS_FIXTURE = listOf(
    DashboardData(
        urlPath = "",
        title = "Home",
        cards = List(4) { CardSummary(type = "tile", title = "Card $it") },
    ),
    DashboardData(
        urlPath = "downstairs",
        title = "Downstairs",
        cards = List(3) { CardSummary(type = "tile", title = "Card $it") },
    ),
    DashboardData(
        urlPath = "office",
        title = "Office",
        cards = List(5) { CardSummary(type = "tile", title = "Card $it") },
    ),
)

@Preview(name = "Wear: top-level (live)", device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun WearTopLevelPreview_Live() {
    WearPreviewFrame {
        WearTopLevelScreen(
            settings = WearSettings(demoMode = false, baseUrl = "https://home.assistant.io"),
            pinned = PINNED_FIXTURE,
            sections = SECTIONS_FIXTURE,
            values = VALUES_FIXTURE,
            onOpenSection = {},
            onBrowseDashboards = {},
            onOpenSettings = {},
        )
    }
}

@Preview(name = "Wear: top-level (demo)", device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun WearTopLevelPreview_Demo() {
    WearPreviewFrame {
        WearTopLevelScreen(
            settings = WearSettings(demoMode = true, baseUrl = "demo://terrazzo"),
            pinned = PINNED_FIXTURE,
            sections = SECTIONS_FIXTURE,
            values = VALUES_FIXTURE,
            onOpenSection = {},
            onBrowseDashboards = {},
            onOpenSettings = {},
        )
    }
}

@Preview(name = "Wear: top-level (empty)", device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun WearTopLevelPreview_Empty() {
    WearPreviewFrame {
        WearTopLevelScreen(
            settings = WearSettings(demoMode = false),
            pinned = PinnedCardSet(),
            sections = PinnedSectionSet(),
            values = emptyMap(),
            onOpenSection = {},
            onBrowseDashboards = {},
            onOpenSettings = {},
        )
    }
}

@Preview(name = "Wear: section", device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun WearSectionPreview() {
    WearPreviewFrame {
        WearSectionScreen(
            section = SECTIONS_FIXTURE.sections.first(),
            values = VALUES_FIXTURE + mapOf(
                "sensor.bedroom" to EntityValue(state = "19.2", unit = "°C"),
            ),
            onBack = {},
        )
    }
}

@Preview(name = "Wear: dashboards", device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun WearDashboardsPreview() {
    WearPreviewFrame {
        WearDashboardsScreen(
            dashboards = DASHBOARDS_FIXTURE,
            onDashboardPicked = {},
            onBack = {},
        )
    }
}

@Preview(name = "Wear: dashboard", device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun WearDashboardPreview() {
    WearPreviewFrame {
        WearDashboardScreen(
            dashboard = DashboardData(
                urlPath = "",
                title = "Home",
                cards = listOf(
                    CardSummary(type = "tile", title = "Living Room", primaryEntityId = "sensor.living_room"),
                    CardSummary(type = "tile", title = "Kitchen", primaryEntityId = "light.kitchen"),
                    CardSummary(type = "tile", title = "Coffee maker", primaryEntityId = "switch.coffee_maker"),
                ),
            ),
            values = VALUES_FIXTURE + mapOf(
                "switch.coffee_maker" to EntityValue(state = "on", friendlyName = "Coffee maker"),
            ),
            onBack = {},
        )
    }
}

@Preview(name = "Wear: settings", device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun WearSettingsPreview() {
    WearPreviewFrame {
        WearSettingsScreen(
            selected = ThemeStyle.TerrazzoHome,
            settings = WearSettings(demoMode = true, baseUrl = "demo://terrazzo"),
            onSelectTheme = {},
            onBack = {},
        )
    }
}

/** Shared fixture: Wear-Material theme + ScreenScaffold so each preview reads like a real frame. */
@Composable
private fun WearPreviewFrame(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = terrazzoWearColorScheme(ThemeStyle.TerrazzoHome),
        typography = wearTypographyFor(ThemeStyle.TerrazzoHome),
    ) {
        AppScaffold(timeText = { TimeText(timeSource = PreviewTimeSource) }) {
            ScreenScaffold { content() }
        }
    }
}

// Frozen clock so previews are byte-stable across renders. The runtime
// scaffold in WearMainActivity still uses the real system clock.
private object PreviewTimeSource : TimeSource {
    @Composable override fun currentTime(): String = "10:08"
}
