package ee.schimke.terrazzo.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.TimeText
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.terrazzo.wearsync.proto.CardDoc
import ee.schimke.terrazzo.wearsync.proto.CardRef
import ee.schimke.terrazzo.wearsync.proto.DashboardData
import ee.schimke.terrazzo.wearsync.proto.PinnedCardRef
import ee.schimke.terrazzo.wearsync.proto.PinnedCardSet
import ee.schimke.terrazzo.wearsync.proto.WearSettings

/**
 * Wear @Preview fixtures. We can't bake real `.rc` documents at preview
 * time (the converter pipeline runs phone-side), so the previews show
 * the watch with no card docs in [WearSyncRepository.cardDocs] yet —
 * which is exactly what wear sees on first launch before phone catches
 * up. Each ref renders as a [WearCardSkeleton], so the previews still
 * exercise the layout, banner, and ref-resolution paths.
 */

private val PINNED_FIXTURE = PinnedCardSet(
    cards = listOf(
        PinnedCardRef(
            id = "pin_1",
            baseUrl = "demo://terrazzo",
            title = "Living Room",
            primaryEntityId = "sensor.living_room",
            type = "tile",
        ),
        PinnedCardRef(
            id = "pin_2",
            baseUrl = "demo://terrazzo",
            title = "Kitchen",
            primaryEntityId = "light.kitchen",
            type = "tile",
        ),
        PinnedCardRef(
            id = "pin_3",
            baseUrl = "demo://terrazzo",
            title = "Front door",
            primaryEntityId = "lock.front_door",
            type = "tile",
        ),
    ),
)

private val DASHBOARDS_FIXTURE = listOf(
    DashboardData(
        urlPath = "",
        title = "Home",
        cards = List(4) { CardRef(id = "dash_default_$it", type = "tile", title = "Card $it") },
    ),
    DashboardData(
        urlPath = "downstairs",
        title = "Downstairs",
        cards = List(3) { CardRef(id = "dash_downstairs_$it", type = "tile", title = "Card $it") },
    ),
    DashboardData(
        urlPath = "office",
        title = "Office",
        cards = List(5) { CardRef(id = "dash_office_$it", type = "tile", title = "Card $it") },
    ),
)

@Preview(name = "Wear: home (live)", device = Devices.WEAR_OS_SMALL_ROUND, showSystemUi = true)
@Composable
fun WearHomePreview_Live() {
    WearPreviewFrame {
        WearHomeScreen(
            settings = WearSettings(
                demoMode = false,
                baseUrl = "https://home.assistant.io",
                themeStyle = ThemeStyle.TerrazzoHome.name,
            ),
            pinned = PINNED_FIXTURE,
            cardDocs = emptyMap(),
            onBrowseDashboards = {},
            onOpenAbout = {},
        )
    }
}

@Preview(name = "Wear: home (demo)", device = Devices.WEAR_OS_SMALL_ROUND, showSystemUi = true)
@Composable
fun WearHomePreview_Demo() {
    WearPreviewFrame {
        WearHomeScreen(
            settings = WearSettings(
                demoMode = true,
                baseUrl = "demo://terrazzo",
                themeStyle = ThemeStyle.TerrazzoHome.name,
            ),
            pinned = PINNED_FIXTURE,
            cardDocs = emptyMap(),
            onBrowseDashboards = {},
            onOpenAbout = {},
        )
    }
}

@Preview(name = "Wear: home (empty)", device = Devices.WEAR_OS_SMALL_ROUND, showSystemUi = true)
@Composable
fun WearHomePreview_Empty() {
    WearPreviewFrame {
        WearHomeScreen(
            settings = WearSettings(themeStyle = ThemeStyle.TerrazzoHome.name),
            pinned = PinnedCardSet(),
            cardDocs = emptyMap(),
            onBrowseDashboards = {},
            onOpenAbout = {},
        )
    }
}

@Preview(name = "Wear: dashboards", device = Devices.WEAR_OS_SMALL_ROUND, showSystemUi = true)
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

@Preview(name = "Wear: dashboard", device = Devices.WEAR_OS_SMALL_ROUND, showSystemUi = true)
@Composable
fun WearDashboardPreview() {
    WearPreviewFrame {
        WearDashboardScreen(
            dashboard = DashboardData(
                urlPath = "",
                title = "Home",
                cards = listOf(
                    CardRef(id = "dash_default_0", type = "tile", title = "Living Room", primaryEntityId = "sensor.living_room"),
                    CardRef(id = "dash_default_1", type = "tile", title = "Kitchen", primaryEntityId = "light.kitchen"),
                    CardRef(id = "dash_default_2", type = "tile", title = "Coffee maker", primaryEntityId = "switch.coffee_maker"),
                ),
            ),
            cardDocs = emptyMap<String, CardDoc>(),
            onBack = {},
        )
    }
}

@Preview(name = "Wear: about", device = Devices.WEAR_OS_SMALL_ROUND, showSystemUi = true)
@Composable
fun WearAboutPreview() {
    WearPreviewFrame {
        WearAboutScreen(
            settings = WearSettings(
                demoMode = true,
                baseUrl = "demo://terrazzo",
                themeStyle = ThemeStyle.TerrazzoHome.name,
            ),
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
        AppScaffold(timeText = { TimeText() }) {
            ScreenScaffold { content() }
        }
    }
}
