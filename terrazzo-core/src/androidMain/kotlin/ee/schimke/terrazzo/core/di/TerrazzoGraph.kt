package ee.schimke.terrazzo.core.di

import android.content.Context
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import ee.schimke.terrazzo.core.auth.HaAuthService
import ee.schimke.terrazzo.core.auth.TokenVault
import ee.schimke.terrazzo.core.cache.OfflineCache
import ee.schimke.terrazzo.core.logs.LogStore
import ee.schimke.terrazzo.core.mobileapp.MobileAppRegistrar
import ee.schimke.terrazzo.core.mobileapp.MobileAppStore
import ee.schimke.terrazzo.core.monitor.CardMonitor
import ee.schimke.terrazzo.core.network.HttpEngineFactory
import ee.schimke.terrazzo.core.network.LanConnectionPolicy
import ee.schimke.terrazzo.core.network.RemoteUrlStore
import ee.schimke.terrazzo.core.pin.PinStore
import ee.schimke.terrazzo.core.prefs.PreferencesStore
import ee.schimke.terrazzo.core.session.CachedHaSession
import ee.schimke.terrazzo.core.session.DemoHaSession
import ee.schimke.terrazzo.core.session.HaAccessTokens
import ee.schimke.terrazzo.core.session.HaReauthRequiredException
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.core.session.LiveHaSession
import ee.schimke.terrazzo.core.session.MintedAccessToken
import ee.schimke.terrazzo.core.session.OfflineOnlySession
import ee.schimke.terrazzo.core.session.SessionWriteMode
import ee.schimke.terrazzo.core.wearsync.WearSyncManager
import ee.schimke.terrazzo.core.widget.WidgetStore
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The app's dependency graph. Created once in [android.app.Application.onCreate] and handed down
 * through the Compose tree via a CompositionLocal.
 *
 * `HaSession` isn't a graph binding — it's constructed per-login via [HaSessionFactory], since each
 * session needs a live baseUrl + access token pair.
 */
interface TerrazzoGraph {
  val tokenVault: TokenVault
  val authService: HaAuthService
  val mobileAppStore: MobileAppStore
  val mobileAppRegistrar: MobileAppRegistrar
  val widgetStore: WidgetStore
  val pinStore: PinStore
  val preferencesStore: PreferencesStore
  val offlineCache: OfflineCache
  val sessionFactory: HaSessionFactory
  val cardMonitor: CardMonitor
  val wearSyncManager: WearSyncManager
  val lanConnectionPolicy: LanConnectionPolicy
  val remoteUrlStore: RemoteUrlStore
  val logStore: LogStore
  val sessionWriteMode: SessionWriteMode

  fun interface Factory {
    fun create(context: Context): TerrazzoGraph
  }
}

/**
 * Factory for [HaSession]. Pulled from the graph so Compose call sites don't `new` the class
 * directly.
 *
 * Live sessions are wrapped in [CachedHaSession] so the UI gets a cache fallback automatically —
 * every fetched dashboard / snapshot is mirrored to disk and read on cold start before the network
 * has a chance to answer. Demo sessions skip the wrapper since their data is already deterministic
 * and re-derived from `DemoData`.
 *
 * [createCachedOnly] returns a cache-backed session whose `delegate` is a no-op stub — used during
 * cold-start auto-resume when we don't yet have a valid access token (refresh in progress, or
 * refresh failed due to no network). The UI still paints from the on-disk cache; once the access
 * token is minted the session is replaced with a real [CachedHaSession] wrapping a [LiveHaSession].
 *
 * A live session re-mints its access token from the refresh token in [TokenVault] when it nears
 * expiry (HA access tokens live about 30 minutes) or HA answers `auth_invalid`, so reconnects keep
 * working for as long as the user stays signed in.
 */
@SingleIn(AppScope::class)
@Inject
class HaSessionFactory(
  private val cache: OfflineCache,
  private val httpEngineFactory: HttpEngineFactory,
  private val tokenVault: TokenVault,
  private val authService: HaAuthService,
) {
  /**
   * [lifetime] is how long [accessToken] lives, when the token response said; else HA's default.
   */
  fun create(baseUrl: String, accessToken: String, lifetime: Duration? = null): HaSession =
    CachedHaSession(
      LiveHaSession(
        baseUrl = baseUrl,
        tokens =
          HaAccessTokens(
            initialToken = accessToken,
            initialLifetime = lifetime,
            mint = { mintAccessToken(baseUrl) },
          ),
        engine = httpEngineFactory.engine,
      ),
      cache,
    )

  /**
   * A `grant_type=refresh_token` exchange with the refresh token stored at sign-in. Persists a
   * rotated refresh token; throws [HaReauthRequiredException] when there is none or HA refuses it.
   */
  private suspend fun mintAccessToken(baseUrl: String): MintedAccessToken {
    val refreshToken =
      tokenVault.get(baseUrl) ?: throw HaReauthRequiredException("No refresh token for $baseUrl")
    val tokens = authService.refreshAccessToken(baseUrl, refreshToken)
    tokens.refreshToken?.let { tokenVault.put(baseUrl, it) }
    val accessToken = tokens.accessToken ?: error("HA token refresh returned no access token")
    return MintedAccessToken(accessToken, tokens.expiresInSeconds?.seconds)
  }

  fun createCachedOnly(baseUrl: String): HaSession =
    CachedHaSession(OfflineOnlySession(baseUrl), cache)

  fun createDemo(): HaSession = DemoHaSession()
}
