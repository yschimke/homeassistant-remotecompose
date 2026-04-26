package ee.schimke.terrazzo.core.di

import android.content.Context
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.Inject
import ee.schimke.terrazzo.core.auth.HaAuthService
import ee.schimke.terrazzo.core.auth.TokenVault
import ee.schimke.terrazzo.core.cache.OfflineCache
import ee.schimke.terrazzo.core.prefs.PreferencesStore
import ee.schimke.terrazzo.core.session.CachedHaSession
import ee.schimke.terrazzo.core.session.DemoHaSession
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.core.session.LiveHaSession
import ee.schimke.terrazzo.core.session.OfflineOnlySession
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
    val offlineCache: OfflineCache
    val sessionFactory: HaSessionFactory

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides context: Context): TerrazzoGraph
    }
}

/**
 * Factory for [HaSession]. Pulled from the graph so Compose call sites
 * don't `new` the class directly.
 *
 * Live sessions are wrapped in [CachedHaSession] so the UI gets a cache
 * fallback automatically — every fetched dashboard / snapshot is mirrored
 * to disk and read on cold start before the network has a chance to
 * answer. Demo sessions skip the wrapper since their data is already
 * deterministic and re-derived from `DemoData`.
 *
 * [createCachedOnly] returns a cache-backed session whose `delegate` is
 * a no-op stub — used during cold-start auto-resume when we don't yet
 * have a valid access token (refresh in progress, or refresh failed
 * due to no network). The UI still paints from the on-disk cache; once
 * the access token is minted the session is replaced with a real
 * [CachedHaSession] wrapping a [LiveHaSession].
 */
@SingleIn(AppScope::class)
@Inject
class HaSessionFactory(private val cache: OfflineCache) {
    fun create(baseUrl: String, accessToken: String): HaSession =
        CachedHaSession(LiveHaSession(baseUrl = baseUrl, accessToken = accessToken), cache)

    fun createCachedOnly(baseUrl: String): HaSession =
        CachedHaSession(OfflineOnlySession(baseUrl), cache)

    fun createDemo(): HaSession = DemoHaSession()
}
