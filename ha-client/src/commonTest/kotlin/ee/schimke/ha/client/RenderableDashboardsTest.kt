package ee.schimke.ha.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class RenderableDashboardsTest {
  private fun config(value: String) = Json.parseToJsonElement(value).jsonObject

  @Test
  fun pickerIncludesAnyExplicitLovelaceDashboardWithoutInventingHome() = runTest {
    val dashboards =
      listOf(
        DashboardSummary("wall-panel", "Wall panel"),
        DashboardSummary("hidden-yaml", "Home"),
        DashboardSummary("generated-home", "Generated home"),
        DashboardSummary("admin-only", "Private"),
      )
    val result =
      resolveRenderableDashboards(dashboards) { path ->
        when (path) {
          null,
          "generated-home" -> config("""{"strategy":{"type":"home"}}""")
          "admin-only" -> throw HaCommandException("lovelace/config", "unauthorized", "forbidden")
          else -> config("""{"views":[{"title":"Lights","cards":[]}]}""")
        }
      }
    assertEquals(listOf("wall-panel", "hidden-yaml"), result.map { it.urlPath })
  }

  @Test
  fun defaultIsOfferedOnlyWhenItHasExplicitViewsAndIsNotDuplicated() = runTest {
    val explicit = config("""{"views":[]}""")
    assertEquals(
      listOf(null),
      resolveRenderableDashboards(emptyList()) { explicit }.map { it.urlPath },
    )
    val named = listOf(DashboardSummary("lovelace", "Overview"))
    assertEquals(named, resolveRenderableDashboards(named) { explicit })
    assertTrue(
      resolveRenderableDashboards(emptyList()) {
          throw HaCommandException("lovelace/config", "config_not_found", "missing")
        }
        .isEmpty()
    )
  }

  @Test
  fun transportFailureAbortsBothEmptyAndPartiallyResolvedLists() = runTest {
    val named = listOf(DashboardSummary("climate", "Climate"), DashboardSummary("office", "Office"))
    for (failedPath in listOf(null, "office")) {
      val probed = mutableListOf<String?>()
      val failure = CommandNotSentException("socket disconnected during config probing")
      val thrown =
        assertFailsWith<CommandNotSentException> {
          resolveRenderableDashboards(named) { path ->
            probed += path
            if (path == failedPath) throw failure
            config("""{"views":[]}""")
          }
        }
      assertTrue(thrown === failure)
      assertEquals(failedPath, probed.last())
    }
  }

  @Test
  fun unexpectedConfigReadFailuresAreNotSilentlyClassifiedAsUnsupported() = runTest {
    assertFailsWith<IllegalStateException> {
      resolveRenderableDashboards(emptyList()) { error("WebSocket read failed") }
    }
  }

  @Test
  fun generatedOrNonLovelaceConfigsAreRejectedInsteadOfRenderingBlank() {
    assertTrue(config("""{"views":[{"type":"sections","sections":[]}]}""").hasRenderableViews())
    for (value in
      listOf(
        """{"strategy":{"type":"original-states"}}""",
        """{"views":[{"strategy":{"type":"home"}}]}""",
        """{"views":"not a view list"}""",
        """{"views":[null]}""",
        """{}""",
      )) assertFalse(config(value).hasRenderableViews(), value)
  }

  @Test
  fun cancellationIsNotMistakenForAnUnsupportedDashboard() = runTest {
    assertFailsWith<CancellationException> {
      resolveRenderableDashboards(emptyList()) { throw CancellationException() }
    }
  }
}
