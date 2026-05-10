package ee.schimke.terrazzo.previews

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.terrazzo.LocalTerrazzoGraph
import ee.schimke.terrazzo.TerrazzoApp
import ee.schimke.terrazzo.core.auth.HaAuthService
import ee.schimke.terrazzo.core.auth.TokenVault
import ee.schimke.terrazzo.core.cache.OfflineCache
import ee.schimke.terrazzo.core.di.HaSessionFactory
import ee.schimke.terrazzo.core.di.TerrazzoGraph
import ee.schimke.terrazzo.core.monitor.CardMonitor
import ee.schimke.terrazzo.core.pin.PinStore
import ee.schimke.terrazzo.core.pin.WearWidgetSlotsStore
import ee.schimke.terrazzo.core.prefs.DarkModePref
import ee.schimke.terrazzo.core.prefs.PreferencesStore
import ee.schimke.terrazzo.core.widget.WidgetStore
import ee.schimke.terrazzo.discovery.DiscoveryScreen
import ee.schimke.terrazzo.ui.TerrazzoTheme

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
            override val pinStore: PinStore = PinStore(context)
            override val wearWidgetSlotsStore: WearWidgetSlotsStore = WearWidgetSlotsStore(context)
            override val widgetStore: WidgetStore
                get() = error("widgetStore not wired in previews")
            override val tokenVault: TokenVault
                get() = error("tokenVault not wired in previews")
            override val authService: HaAuthService
                get() = error("authService not wired in previews")
            override val sessionFactory: HaSessionFactory
                get() = error("sessionFactory not wired in previews")
            override val cardMonitor: CardMonitor
                get() = object : CardMonitor {
                    override val isEnabled: Boolean = false
                    override fun start(card: CardConfig, durationMinutes: Int) {}
                }
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
    TerrazzoTheme(style = style, darkMode = darkMode) {
        CompositionLocalProvider(LocalTerrazzoGraph provides graph) {
            Scaffold { padding ->
                content()
            }
        }
    }
}

@Preview(name = "Discovery (light)", group = "Screens", showBackground = true)
@Composable
fun PreviewDiscovery() {
    PhoneHost {
        DiscoveryScreen(onInstancePicked = {}, onDemoSelected = {})
    }
}

@Preview(name = "Discovery (dark)", group = "Screens", showBackground = true)
@Composable
fun PreviewDiscoveryDark() {
    PhoneHost(darkMode = DarkModePref.Dark) {
        DiscoveryScreen(onInstancePicked = {}, onDemoSelected = {})
    }
}

@Preview(name = "App Root (empty)", group = "Screens", showBackground = true)
@Composable
fun PreviewAppRoot() {
    PhoneHost {
        TerrazzoApp()
    }
}
