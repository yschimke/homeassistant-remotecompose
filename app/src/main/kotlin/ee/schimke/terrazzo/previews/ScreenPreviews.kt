package ee.schimke.terrazzo.previews

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import ee.schimke.ha.client.DashboardSummary
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.terrazzo.LocalTerrazzoGraph
import ee.schimke.terrazzo.core.auth.HaAuthService
import ee.schimke.terrazzo.core.auth.TokenVault
import ee.schimke.terrazzo.core.cache.OfflineCache
import ee.schimke.terrazzo.core.di.HaSessionFactory
import ee.schimke.terrazzo.core.di.TerrazzoGraph
import ee.schimke.terrazzo.core.prefs.DarkModePref
import ee.schimke.terrazzo.core.prefs.PreferencesStore
import ee.schimke.terrazzo.core.session.DemoHaSession
import ee.schimke.terrazzo.core.widget.WidgetStore
import ee.schimke.terrazzo.dashboard.DashboardListState
import ee.schimke.terrazzo.dashboard.DashboardPickerScreen
import ee.schimke.terrazzo.dashboard.DashboardViewScreen
import ee.schimke.terrazzo.discovery.DiscoveryScreen
import ee.schimke.terrazzo.ui.TerrazzoTheme
import ee.schimke.terrazzo.widget.WidgetsScreen

/**
 * Phone-sized device previews, one per top-level screen, plus a set of
 * Play-Store-targeted previews used by the listing graphics pipeline.
 *
 * Rendered by `:app:renderAllPreviews` so regressions (broken layouts,
 * missing theme tokens, a refactor that orphans a screen) show up in
 * CI's artifact bundle even before the emulator job runs.
 *
 * Every preview is wrapped in [TerrazzoTheme] so a [ThemeStyle] is
 * exercised. Screens that read [LocalTerrazzoGraph] (DashboardViewScreen
 * touches `offlineCache` and `preferencesStore`) get a minimal preview
 * graph wired up below — the unused bindings throw on access.
 *
 * Default phone dimensions match Pixel 6 portrait so a full screen is
 * visible without scrolling. The Play-Store-targeted previews override
 * those via `@Preview(device = ...)` to land on Pixel 8a / 7-inch /
 * 10-inch tablet specs instead, with `dpi` chosen to keep the rendered
 * PNG under the 1800-pixel agent capture limit while preserving the
 * device's aspect ratio.
 */
private const val PHONE_WIDTH_DP = 412
private const val PHONE_HEIGHT_DP = 892

// Play-Store device specs. dpi is tuned per-device so the rendered PNG
// stays under 1800 px in either dimension while preserving the real
// device aspect ratio.
//   Pixel 8a    1080×2400 native (9:20)  — 405dp×900dp @ 320dpi → 810×1800
//   7-inch tab   600dp×960dp     (5:8)   — 600dp×960dp @ 240dpi → 900×1440
//   10-inch tab 800dp×1280dp     (5:8)   — 800dp×1280dp @ 224dpi → 1120×1792
private const val PIXEL_8A_DEVICE = "spec:width=405dp,height=900dp,dpi=320"
private const val TABLET_7_DEVICE = "spec:width=600dp,height=960dp,dpi=240"
private const val TABLET_10_DEVICE = "spec:width=800dp,height=1280dp,dpi=224"

// Pin the demo-session clock so the snapshot's sine-wave sensor values
// (temperatures, humidity, power, lamp toggles, battery drain) render
// identically on every run. Defaulting to `System::currentTimeMillis`
// would re-seed the demo data each render and produce false preview
// diffs on every PR.
private const val DEMO_CLOCK_MS = 0L
private fun demoSession() = DemoHaSession(clock = { DEMO_CLOCK_MS })

/**
 * Minimal [TerrazzoGraph] for previews. Provides real instances of
 * [OfflineCache] and [PreferencesStore] (both are pure context-backed
 * file/DataStore wrappers, so they cost nothing in a Robolectric
 * harness) and throws on the rest — none of the previewed screens read
 * those bindings.
 */
@Composable
private fun rememberPreviewGraph(): TerrazzoGraph {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        object : TerrazzoGraph {
            override val offlineCache: OfflineCache = OfflineCache(context)
            override val preferencesStore: PreferencesStore = PreferencesStore(context)
            override val widgetStore: WidgetStore
                get() = error("widgetStore not wired in previews")
            override val tokenVault: TokenVault
                get() = error("tokenVault not wired in previews")
            override val authService: HaAuthService
                get() = error("authService not wired in previews")
            override val sessionFactory: HaSessionFactory
                get() = error("sessionFactory not wired in previews")
        }
    }
}

@Composable
private fun PhoneHost(
    style: ThemeStyle = ThemeStyle.TerrazzoHome,
    darkMode: DarkModePref = DarkModePref.Follow,
    content: @Composable () -> Unit,
) {
    val graph = rememberPreviewGraph()
    CompositionLocalProvider(LocalTerrazzoGraph provides graph) {
        TerrazzoTheme(style = style, darkMode = darkMode) {
            // Paint the Material 3 surface as the page background — the
            // dashboard cards sit on top, and without an explicit fill
            // the preview's transparent background shows through and
            // makes dark-mode cards look stranded on white.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) { content() }
        }
    }
}

// --- Original screen previews -------------------------------------------

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
        session = demoSession(),
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
        session = demoSession(),
        urlPath = null,
        onCardLongPress = {},
    )
}

class ThemeStyleProvider : PreviewParameterProvider<ThemeStyle> {
    override val values: Sequence<ThemeStyle> = ThemeStyle.entries.asSequence()
}

// --- Play Store listing graphics ----------------------------------------
//
// The PNGs these emit are copied into
// `app/src/main/play/listings/en-GB/graphics/{phone,seven-inch,ten-inch}-screenshots/`
// by `scripts/render-play-screenshots.sh`. Filenames there encode the
// listing slot (01, 02, …) and the upload order, so the function names
// below are picked to sort cleanly.

/** Phone slot 1 — light dashboard (the "home screen"). */
@Preview(name = "play · phone · home (light)", showBackground = false, device = PIXEL_8A_DEVICE)
@Composable
fun Play_Phone_01_HomeLight() = PhoneHost(darkMode = DarkModePref.Light) {
    DashboardViewScreen(session = demoSession(), urlPath = null, onCardLongPress = {})
}

/** Phone slot 2 — dark dashboard (the "home screen"). */
@Preview(name = "play · phone · home (dark)", showBackground = false, device = PIXEL_8A_DEVICE)
@Composable
fun Play_Phone_02_HomeDark() = PhoneHost(darkMode = DarkModePref.Dark) {
    DashboardViewScreen(session = demoSession(), urlPath = null, onCardLongPress = {})
}

/** Phone slot 3 — discovery / first-launch flow. */
@Preview(name = "play · phone · discovery", showBackground = false, device = PIXEL_8A_DEVICE)
@Composable
fun Play_Phone_03_Discovery() = PhoneHost(darkMode = DarkModePref.Light) {
    DiscoveryScreen(onInstancePicked = {}, onDemoSelected = {})
}

/** Phone slot 4 — multi-dashboard picker. */
@Preview(name = "play · phone · picker", showBackground = false, device = PIXEL_8A_DEVICE)
@Composable
fun Play_Phone_04_Picker() = PhoneHost(darkMode = DarkModePref.Light) {
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

/** Phone slot 5 — installed widgets. */
@Preview(name = "play · phone · widgets", showBackground = false, device = PIXEL_8A_DEVICE)
@Composable
fun Play_Phone_05_Widgets() = PhoneHost(darkMode = DarkModePref.Light) {
    WidgetsScreen(onBack = {})
}

/** 7-inch tablet slot 1 — home (dashboard view). */
@Preview(name = "play · 7-inch · home", showBackground = false, device = TABLET_7_DEVICE)
@Composable
fun Play_Tablet7_01_Home() = PhoneHost(darkMode = DarkModePref.Light) {
    DashboardViewScreen(session = demoSession(), urlPath = null, onCardLongPress = {})
}

/** 10-inch tablet slot 1 — home (dashboard view). */
@Preview(name = "play · 10-inch · home", showBackground = false, device = TABLET_10_DEVICE)
@Composable
fun Play_Tablet10_01_Home() = PhoneHost(darkMode = DarkModePref.Light) {
    DashboardViewScreen(session = demoSession(), urlPath = null, onCardLongPress = {})
}
