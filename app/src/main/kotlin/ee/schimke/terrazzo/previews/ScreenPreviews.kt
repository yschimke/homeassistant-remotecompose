package ee.schimke.terrazzo.previews

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import ee.schimke.ha.client.DashboardSummary
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.terrazzo.core.prefs.DarkModePref
import ee.schimke.terrazzo.core.session.DemoHaSession
import ee.schimke.terrazzo.dashboard.DashboardListState
import ee.schimke.terrazzo.dashboard.DashboardPickerScreen
import ee.schimke.terrazzo.dashboard.DashboardViewScreen
import ee.schimke.terrazzo.discovery.DiscoveryScreen
import ee.schimke.terrazzo.ui.TerrazzoTheme
import ee.schimke.terrazzo.widget.WidgetsScreen

/**
 * Phone-sized device previews, one per top-level screen. Rendered by
 * `:app:renderAllPreviews` so regressions (broken layouts, missing
 * theme tokens, a refactor that orphans a screen) show up in CI's
 * artifact bundle even before the emulator job runs.
 *
 * Every preview is wrapped in [TerrazzoTheme] so a [ThemeStyle] is
 * exercised. Screens requiring a live HA session use [DemoHaSession] —
 * its `connect()` is a no-op and it serves deterministic fake
 * dashboards/state, which is exactly what a screenshot harness wants.
 *
 * Dimensions are Pixel-6-ish (portrait) so a full screen is visible
 * without scrolling.
 */
private const val PHONE_WIDTH_DP = 412
private const val PHONE_HEIGHT_DP = 892

@Composable
private fun PhoneHost(
    style: ThemeStyle = ThemeStyle.TerrazzoHome,
    darkMode: DarkModePref = DarkModePref.Follow,
    content: @Composable () -> Unit,
) {
    TerrazzoTheme(style = style, darkMode = darkMode) {
        Box(Modifier.fillMaxSize()) { content() }
    }
}

@Preview(name = "discovery", showBackground = false, widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP)
@Composable
fun Screen_Discovery() = PhoneHost {
    DiscoveryScreen(onInstancePicked = {}, onDemoSelected = {})
}

@Preview(name = "widgets", showBackground = false, widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP)
@Composable
fun Screen_Widgets() = PhoneHost {
    WidgetsScreen(onBack = {})
}

@Preview(name = "dashboard picker", showBackground = false, widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP)
@Composable
fun Screen_DashboardPicker() = PhoneHost {
    DashboardPickerScreen(
        state = DashboardListState.Ready(
            dashboards = listOf(
                DashboardSummary(urlPath = null, title = "Home"),
                DashboardSummary(urlPath = "lovelace-mobile", title = "Living room"),
                DashboardSummary(urlPath = "lovelace-garage", title = "Garage"),
            ),
        ),
        onDashboardPicked = {},
    )
}

@Preview(name = "dashboard view", showBackground = false, widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP)
@Composable
fun Screen_DashboardView() = PhoneHost {
    DashboardViewScreen(
        session = DemoHaSession(),
        urlPath = null,
        onCardLongPress = {},
    )
}

/**
 * Parameterised fan-out of [DashboardViewScreen] over every
 * [ThemeStyle]. Produces one PNG per curated palette so the PR can
 * compare them side-by-side against the same dashboard content.
 */
@Preview(name = "dashboard · theme", showBackground = false, widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP)
@Composable
fun Screen_DashboardView_ThemeStyle(
    @PreviewParameter(ThemeStyleProvider::class) style: ThemeStyle,
) = PhoneHost(style = style) {
    DashboardViewScreen(
        session = DemoHaSession(),
        urlPath = null,
        onCardLongPress = {},
    )
}

class ThemeStyleProvider : PreviewParameterProvider<ThemeStyle> {
    override val values: Sequence<ThemeStyle> = ThemeStyle.entries.asSequence()
}
