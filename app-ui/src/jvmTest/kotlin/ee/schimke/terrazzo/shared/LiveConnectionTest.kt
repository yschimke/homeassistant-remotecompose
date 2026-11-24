package ee.schimke.terrazzo.shared

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import ee.schimke.ha.client.HaConfig
import ee.schimke.ha.client.cache.OfflineCacheStorage
import ee.schimke.ha.client.cache.OfflineCacheStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shared app against a [FakeHomeAssistant]: what it asks, and what it shows. */
@OptIn(ExperimentalTestApi::class)
class LiveConnectionTest {
  private val installed =
    mapOf(
      "terrazzo/info" to
        """{"protocol":1,"min_client":1,"version":"0.1.99",
          "capabilities":{"design_server":{"available":false}}}""",
    )

  @Test
  fun settingsShowsTheIntegrationTheHandshakeFound() = runComposeUiTest {
    FakeHomeAssistant(installed).use { ha ->
      setContent {
        TerrazzoMultiplatformApp(
          hostedConfig = HaConfig(ha.baseUrl, ""),
          hostedAccessToken = { "t" },
        )
      }
      waitUntil(timeoutMillis = 10_000) {
        onAllNodes(hasText("Live living room")).fetchSemanticsNodes().isNotEmpty()
      }
      onNodeWithContentDescription("Settings").performClick()
      waitUntil(timeoutMillis = 10_000) {
        onAllNodes(hasText("Installed, version 0.1.99.")).fetchSemanticsNodes().isNotEmpty()
      }
      // Left out for this user: neither the agent nor edit sessions were asked.
      check("terrazzo/agent/info" !in ha.sent) { ha.sent.toString() }
      check("terrazzo/edit/list" !in ha.sent) { ha.sent.toString() }
    }
  }

  @Test
  fun theScreenBeingLeftIsGoneFromSemanticsWhileItFadesOut() = runComposeUiTest {
    FakeHomeAssistant(installed).use { ha ->
      setContent {
        TerrazzoMultiplatformApp(
          hostedConfig = HaConfig(ha.baseUrl, ""),
          hostedAccessToken = { "t" },
        )
      }
      waitUntil(timeoutMillis = 10_000) {
        onAllNodes(hasText("Live living room")).fetchSemanticsNodes().isNotEmpty()
      }
      // Stop the clock part way through the fade: both screens are composed, but only the one
      // arriving may be read, or a screen reader (and a UI test) meets two of every control.
      mainClock.autoAdvance = false
      onNodeWithContentDescription("Settings").performClick()
      mainClock.advanceTimeByFrame()
      mainClock.advanceTimeByFrame()
      assertEquals(0, onAllNodes(hasText("Live living room")).fetchSemanticsNodes().size)
      mainClock.autoAdvance = true
    }
  }

  @Test
  fun settingsOffersToInstallAMissingIntegration() = runComposeUiTest {
    // Core answers manifest/get for an integration that isn't there with not_found.
    FakeHomeAssistant(emptyMap(), errors = mapOf("manifest/get" to "not_found")).use { ha ->
      setContent {
        TerrazzoMultiplatformApp(
          hostedConfig = HaConfig(ha.baseUrl, ""),
          hostedAccessToken = { "t" },
        )
      }
      waitUntil(timeoutMillis = 10_000) {
        onAllNodes(hasText("Live living room")).fetchSemanticsNodes().isNotEmpty()
      }
      onNodeWithContentDescription("Settings").performClick()
      waitUntil(timeoutMillis = 10_000) {
        onAllNodes(hasText("Install")).fetchSemanticsNodes().isNotEmpty()
      }
      // Absent: neither Terrazzo command was tried.
      check(ha.sent.none { it.startsWith("terrazzo/") && it != "terrazzo/info" }) {
        ha.sent.toString()
      }
    }
  }

  @Test
  fun aCacheThatCannotBeWrittenNeverDropsTheConnection() = runComposeUiTest {
    val failing = FailingWrites(OfflineCacheStorage(Files.createTempDirectory("full").toFile()))
    FakeHomeAssistant(installed).use { ha ->
      setContent {
        TerrazzoMultiplatformApp(
          hostedConfig = HaConfig(ha.baseUrl, ""),
          hostedAccessToken = { "t" },
          offlineCache = failing,
        )
      }
      waitUntil(timeoutMillis = 10_000) {
        onAllNodes(hasText("Live living room")).fetchSemanticsNodes().isNotEmpty()
      }
      // The dashboard list, the dashboard and the states were each written, and each write failed.
      waitUntil(timeoutMillis = 10_000) { failing.failed.get() >= 2 }
      waitForIdle()
      assertEquals(1, ha.connections.get(), "reconnected after a failed cache write")
      check(onAllNodes(hasText("Connection lost. Retrying…")).fetchSemanticsNodes().isEmpty())
    }
  }

  /** A cache on a full disk: reads work, every write throws. */
  private class FailingWrites(private val store: OfflineCacheStore) : OfflineCacheStore by store {
    val failed = java.util.concurrent.atomic.AtomicInteger()

    private fun fail(): Nothing {
      failed.incrementAndGet()
      throw java.io.IOException("No space left on device")
    }

    override fun putDashboards(
      baseUrl: String,
      list: List<ee.schimke.ha.client.DashboardSummary>,
    ) = fail()

    override fun putDashboard(
      baseUrl: String,
      urlPath: String?,
      dashboard: ee.schimke.ha.model.Dashboard,
    ) = fail()

    override fun putSnapshot(
      baseUrl: String,
      urlPath: String?,
      snapshot: ee.schimke.ha.model.HaSnapshot,
    ) = fail()

    override fun setLastInstance(baseUrl: String) = fail()
  }
}
