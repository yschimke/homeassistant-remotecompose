package ee.schimke.terrazzo.previews

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import ee.schimke.terrazzo.core.session.DemoHaSession
import ee.schimke.terrazzo.dashboard.DashboardPickerScreen
import ee.schimke.terrazzo.dashboard.DashboardViewScreen
import ee.schimke.terrazzo.discovery.DiscoveryScreen
import ee.schimke.terrazzo.ui.TerrazzoTheme
import ee.schimke.terrazzo.ui.theme.ThemeSettings
import ee.schimke.terrazzo.ui.theme.TypographyChoice
import ee.schimke.terrazzo.widget.WidgetsScreen

/**
 * Phone-sized device previews, one per top-level screen. Rendered by
 * `:app:renderAllPreviews` so regressions (broken layouts, missing
 * theme tokens, a refactor that orphans a screen) show up in CI's
 * artifact bundle even before the emulator job runs.
 *
 * Every preview is wrapped in [TerrazzoTheme] so colour/typography
 * choices from [ThemeSettings] are exercised. Screens requiring a live
 * HA session use [DemoHaSession] — its `connect()` is a no-op and it
 * serves deterministic fake dashboards/state, which is exactly what a
 * screenshot harness wants.
 *
 * Dimensions are Pixel-6-ish (portrait) so a full screen is visible
 * without scrolling.
 */
private const val PHONE_WIDTH_DP = 412
private const val PHONE_HEIGHT_DP = 892

@Composable
private fun PhoneHost(
    settings: ThemeSettings = ThemeSettings(),
    content: @Composable () -> Unit,
) {
    TerrazzoTheme(settings = settings) {
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
    WidgetsScreen()
}

@Preview(name = "dashboard picker", showBackground = false, widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP)
@Composable
fun Screen_DashboardPicker() = PhoneHost {
    DashboardPickerScreen(session = DemoHaSession(), onDashboardPicked = {})
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
 * [TypographyChoice]. Produces four PNGs — Material default, Expressive,
 * Atkinson Hyperlegible, Lexend — so the PR can compare the typography
 * options side-by-side against the same dashboard content. One preview
 * per PNG keeps filenames readable in the artifact bundle.
 */
@Preview(name = "dashboard · theme", showBackground = false, widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP)
@Composable
fun Screen_DashboardView_Typography(
    @PreviewParameter(TypographyChoiceProvider::class) choice: TypographyChoice,
) = PhoneHost(settings = ThemeSettings(typography = choice)) {
    DashboardViewScreen(
        session = DemoHaSession(),
        urlPath = null,
        onCardLongPress = {},
    )
}

class TypographyChoiceProvider : PreviewParameterProvider<TypographyChoice> {
    override val values: Sequence<TypographyChoice> = TypographyChoice.entries.asSequence()
}
