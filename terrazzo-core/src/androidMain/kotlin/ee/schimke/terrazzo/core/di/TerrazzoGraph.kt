package ee.schimke.terrazzo.core.di

import android.content.Context
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.Inject
import ee.schimke.terrazzo.core.auth.HaAuthService
import ee.schimke.terrazzo.core.auth.TokenVault
import ee.schimke.terrazzo.core.prefs.PreferencesStore
import ee.schimke.terrazzo.core.session.DemoHaSession
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.core.session.LiveHaSession
import ee.schimke.terrazzo.core.widget.WidgetStore

/**
 * The app's dependency graph. Created once in [android.app.Application.onCreate]
 * and handed down through the Compose tree via a CompositionLocal.
 *
 * `HaSession` isn't a graph binding — it's constructed per-login via
 * [HaSessionFactory], since each session needs a live baseUrl + access
 * token pair.
 */
@DependencyGraph(scope = AppScope::class)
interface TerrazzoGraph {
    val tokenVault: TokenVault
    val authService: HaAuthService
    val widgetStore: WidgetStore
    val preferencesStore: PreferencesStore
    val sessionFactory: HaSessionFactory

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides context: Context): TerrazzoGraph
    }
}

/**
 * Factory for [HaSession]. Pulled from the graph so Compose call sites
 * don't `new` the class directly.
 */
@SingleIn(AppScope::class)
@Inject
class HaSessionFactory {
    fun create(baseUrl: String, accessToken: String): HaSession =
        LiveHaSession(baseUrl = baseUrl, accessToken = accessToken)

    fun createDemo(): HaSession = DemoHaSession()
}
