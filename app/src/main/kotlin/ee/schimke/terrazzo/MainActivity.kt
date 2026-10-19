package ee.schimke.terrazzo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ee.schimke.terrazzo.core.di.TerrazzoGraph
import ee.schimke.terrazzo.core.prefs.CardStylePref
import ee.schimke.terrazzo.core.prefs.DarkModePref
import ee.schimke.terrazzo.core.prefs.ThemePref
import ee.schimke.terrazzo.core.session.HaReauthRequiredException
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.di.AppGraph
import ee.schimke.terrazzo.ui.AndroidImeHost
import ee.schimke.terrazzo.ui.LocalIsDarkTheme
import ee.schimke.terrazzo.ui.TerrazzoTheme
import ee.schimke.terrazzo.ui.toThemeStyle
import ee.schimke.terrazzo.ui.toUberTheme
import ee.schimke.terrazzo.ui.updateSystemBars
import ee.schimke.terrazzo.widget.WidgetRefreshScheduler
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val graph = (application as TerrazzoApplication).graph

    // Debug-only test hatch: when the launching Intent carries
    // `terrazzo.test.demo_mode=true` we flip the persisted
    // demo-mode flag synchronously before the first composition,
    // so the auth flow short-circuits to a demo session and a
    // uiautomator test can land directly on the dashboard. Gated
    // on BuildConfig.DEBUG so production builds don't expose
    // the bypass even if the extra is present.
    //
    // Also clears the offline-cache instance pointer and the
    // last-viewed-dashboard pref so the test starts on HA's
    // default demo dashboard every time, not auto-resumed onto
    // a previous run's cached dashboard.
    if (BuildConfig.DEBUG && intent?.getBooleanExtra(EXTRA_TEST_DEMO_MODE, false) == true) {
      runBlocking {
        graph.preferencesStore.setDemoMode(true)
        graph.preferencesStore.clearLastViewedDashboard()
      }
      graph.offlineCache.clearLastInstance()
    }

    // Read the auto-launch dashboard pref synchronously so the
    // first composition can land directly on the right dashboard
    // (no picker flash). DataStore reads from a tiny preferences
    // file; the runBlocking cost is < 5 ms in practice — same
    // pattern as the test hatch above.
    val initialDashboard = runBlocking { graph.preferencesStore.lastViewedDashboardNow() }

    // The session lives in a ViewModel so rotation and other
    // configuration changes keep the live connection. `start` only
    // acts the first time: offline-first cold start builds a
    // cache-only session from the last instance pointer, so the first
    // composition paints immediately from cached dashboards and
    // snapshots — no spinner, no network roundtrip — then [resumeSession]
    // upgrades it to a live session once the access token is minted.
    val widgetScheduler = WidgetRefreshScheduler(applicationContext)
    val sessionHolder =
      ViewModelProvider.create(
          this,
          viewModelFactory {
            initializer {
              SessionHolder(
                closeScope =
                  ProcessLifecycleOwner.get().lifecycleScope +
                    graph.logStore.coroutineExceptionHandler
              )
            }
          },
        )[SessionHolder::class]
    sessionHolder.start(
      initial = { graph.offlineCache.lastInstance()?.let(graph.sessionFactory::createCachedOnly) },
      resume = { initial -> resumeSession(graph, widgetScheduler, sessionHolder, initial) },
    )

    // The runtime graph is always an [AppGraph] (constructed via
    // Metro in TerrazzoApplication). Surface its image stack so
    // dashboard / widget code reuses the singleton ImageLoader +
    // DiskCache instead of building per-screen instances pointing
    // at the same cache directory.
    val imageStack = (graph as AppGraph).haImageStack

    setContent {
      CompositionLocalProvider(
        LocalTerrazzoGraph provides graph,
        LocalHaImageStack provides imageStack,
      ) {
        val themePref by
          graph.preferencesStore.themeStyle.collectAsState(initial = ThemePref.TerrazzoHome)
        val darkPref by
          graph.preferencesStore.darkMode.collectAsState(initial = DarkModePref.Follow)
        val cardStylePref by
          graph.preferencesStore.cardStyle.collectAsState(initial = CardStylePref.Classic)
        TerrazzoTheme(
          style = themePref.toThemeStyle(),
          darkMode = darkPref,
          cardStyle = cardStylePref.toUberTheme(),
        ) {
          val darkTheme = LocalIsDarkTheme.current
          SideEffect { updateSystemBars(darkTheme) }
          AndroidImeHost {
            TerrazzoApp(initialDashboard = initialDashboard, sessionHolder = sessionHolder)
          }
        }
      }
    }
  }

  companion object {
    const val EXTRA_TEST_DEMO_MODE: String = "terrazzo.test.demo_mode"
  }
}

/**
 * Cold-start auto-resume, run once per [SessionHolder]. Top-level rather than an Activity member so
 * the coroutine, which outlives a rotation, doesn't hold on to the first Activity.
 *
 * The persisted demo-mode flag survives process restarts, so a user who opted into demo doesn't see
 * the login screen again on relaunch; it also re-arms the widget refresh chain in case the worker
 * queue was flushed (e.g. "Clear data" via system Settings). Otherwise a cache-only [initial]
 * session (from a prior login) is swapped for a live one, with an access token minted from the
 * vault's refresh token.
 */
private suspend fun resumeSession(
  graph: TerrazzoGraph,
  widgetScheduler: WidgetRefreshScheduler,
  holder: SessionHolder,
  initial: HaSession?,
) {
  if (graph.preferencesStore.demoModeNow()) {
    holder.session = graph.sessionFactory.createDemo()
    initial?.close()
    widgetScheduler.scheduleDemo()
  } else if (initial != null) {
    val baseUrl = initial.baseUrl
    val refreshToken = graph.tokenVault.get(baseUrl) ?: return
    runCatching { graph.authService.refreshAccessToken(baseUrl, refreshToken) }
      .onSuccess { tokens ->
        val access = tokens.accessToken
        // The user may have signed out or toggled demo while the
        // refresh was in flight; only swap if we're still on the
        // cache-only stub.
        if (access != null && holder.session === initial) {
          holder.session =
            graph.sessionFactory.create(baseUrl, access, tokens.expiresInSeconds?.seconds)
          initial.close()
        }
        tokens.refreshToken?.let { graph.tokenVault.put(baseUrl, it) }
      }
      .onFailure { error ->
        // HA refused the stored refresh token; TerrazzoApp signs out.
        if (error is HaReauthRequiredException) holder.refusedSession = initial
      }
  }
}
