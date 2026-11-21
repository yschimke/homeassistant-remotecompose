package ee.schimke.terrazzo.shared

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runComposeUiTest
import ee.schimke.ha.client.DashboardSummary
import ee.schimke.ha.client.HaConfig
import ee.schimke.ha.client.cache.OfflineCacheStorage
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.View
import java.nio.file.Files
import kotlin.test.Test

/**
 * The desktop app's offline-first start: with Home Assistant unreachable, a signed-in app draws the
 * dashboard it cached last time instead of waiting on the connection.
 */
@OptIn(ExperimentalTestApi::class)
class OfflineStartTest {
  // Nothing listens on port 1, so every connection attempt fails at once.
  private val unreachable = "http://127.0.0.1:1"

  private val cached =
    Dashboard(
      title = "Home",
      views = listOf(View(title = "Cached living room"), View(title = "Cached garden")),
    )

  @Test
  fun drawsTheCachedDashboardWhileHomeAssistantIsUnreachable() = runComposeUiTest {
    val store = OfflineCacheStorage(Files.createTempDirectory("offline-start").toFile())
    store.putDashboards(unreachable, listOf(DashboardSummary(urlPath = null, title = "Home")))
    store.putDashboard(unreachable, null, cached)
    setContent {
      TerrazzoMultiplatformApp(
        hostedConfig = HaConfig(unreachable, ""),
        hostedAccessToken = { "token" },
        offlineCache = store,
      )
    }
    waitUntil(timeoutMillis = 10_000) {
      onAllNodes(hasText("Cached garden")).fetchSemanticsNodes().isNotEmpty()
    }
  }

  @Test
  fun withoutACacheNothingOfTheDashboardIsDrawn() = runComposeUiTest {
    setContent {
      TerrazzoMultiplatformApp(
        hostedConfig = HaConfig(unreachable, ""),
        hostedAccessToken = { "token" },
      )
    }
    mainClock.advanceTimeBy(3_000)
    waitForIdle()
    check(onAllNodes(hasText("Cached garden")).fetchSemanticsNodes().isEmpty())
  }
}
