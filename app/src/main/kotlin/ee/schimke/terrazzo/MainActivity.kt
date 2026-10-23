package ee.schimke.terrazzo

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
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
import ee.schimke.terrazzo.core.session.CachedHaSession
import ee.schimke.terrazzo.core.session.DemoHaSession
import ee.schimke.terrazzo.core.session.HaReauthRequiredException
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.dashboard.CardDesignPreload
import ee.schimke.terrazzo.dashboard.DESIGN_PRELOAD_WAIT_MS
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
    if (BuildConfig.TEST_HOOKS) StartupHangWatchdog.arm()
    enableEdgeToEdge()
    val graph = (application as TerrazzoApplication).graph

    // Debug-only test hatch: when the launching Intent carries
    // `terrazzo.test.demo_mode=true` we flip the persisted
    // demo-mode flag synchronously before the first composition,
    // so the auth flow short-circuits to a demo session and a
    // uiautomator test or `:app-benchmark` can land directly on
    // the dashboard. Gated on BuildConfig.TEST_HOOKS (debug and
    // the baseline profile / benchmark variants) so production
    // builds don't expose the bypass even if the extra is present.
    //
    // Also clears the offline-cache instance pointer and the
    // last-viewed-dashboard pref so the test starts on HA's
    // default demo dashboard every time, not auto-resumed onto
    // a previous run's cached dashboard. [EXTRA_TEST_EXPAND_SECTIONS]
    // opens every section (collapsed mode off); without it the
    // default, collapsed mode, is restored, so one run's choice
    // never leaks into the next.
    if (BuildConfig.TEST_HOOKS && intent?.getBooleanExtra(EXTRA_TEST_DEMO_MODE, false) == true) {
      val expandSections = intent.getBooleanExtra(EXTRA_TEST_EXPAND_SECTIONS, false)
      runBlocking {
        graph.preferencesStore.setDemoMode(true)
        graph.preferencesStore.clearLastViewedDashboard()
        graph.preferencesStore.setCollapsedMode(!expandSections)
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
    // Demo mode needs no network, so its session is ready for the first composition too; seeding
    // null there painted the sign-in screen until the resume below swapped the demo session in.
    sessionHolder.start(
      initial = {
        if (runBlocking { graph.preferencesStore.demoModeNow() }) graph.sessionFactory.createDemo()
        else graph.offlineCache.lastInstance()?.let(graph.sessionFactory::createCachedOnly)
      },
      resume = { initial -> resumeSession(graph, widgetScheduler, sessionHolder, initial) },
    )

    // The runtime graph is always an [AppGraph] (constructed via
    // Metro in TerrazzoApplication). Surface its image stack so
    // dashboard / widget code reuses the singleton ImageLoader +
    // DiskCache instead of building per-screen instances pointing
    // at the same cache directory.
    val imageStack = (graph as AppGraph).haImageStack

    holdFirstDrawForCardDesigns()

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

  /**
   * Keeps the system splash up until the cached card designs are in memory ([CardDesignPreload]),
   * so the first frame drawn is the dashboard with its cards rather than the dashboard's hold
   * spinner. Capped at [DESIGN_PRELOAD_WAIT_MS], after which the dashboard's own hold takes over.
   */
  private fun holdFirstDrawForCardDesigns() {
    val content = findViewById<View>(android.R.id.content)
    val since = SystemClock.uptimeMillis()
    content.viewTreeObserver.addOnPreDrawListener(
      object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
          val ready =
            CardDesignPreload.designs.value != null ||
              SystemClock.uptimeMillis() - since >= DESIGN_PRELOAD_WAIT_MS
          if (ready) content.viewTreeObserver.removeOnPreDrawListener(this)
          return ready
        }
      }
    )
  }

  companion object {
    const val EXTRA_TEST_DEMO_MODE: String = "terrazzo.test.demo_mode"

    /** With [EXTRA_TEST_DEMO_MODE]: every dashboard section open, not just the first. */
    const val EXTRA_TEST_EXPAND_SECTIONS: String = "terrazzo.test.expand_sections"
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
    // Already seeded with a demo session: keep it, since a new one would restart the dashboard.
    if (initial !is DemoHaSession) {
      holder.session = graph.sessionFactory.createDemo()
      initial?.close()
    }
    widgetScheduler.scheduleDemo()
  } else if (initial != null) {
    val baseUrl = initial.baseUrl
    val refreshToken = graph.tokenVault.get(baseUrl)
    if (refreshToken == null) {
      (initial as? CachedHaSession)?.markUnreachable()
      return
    }
    runCatching { graph.authService.refreshAccessToken(baseUrl, refreshToken) }
      .onSuccess { tokens ->
        val access = tokens.accessToken
        if (access == null) (initial as? CachedHaSession)?.markUnreachable()
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
        else (initial as? CachedHaSession)?.markUnreachable()
      }
  }
}
