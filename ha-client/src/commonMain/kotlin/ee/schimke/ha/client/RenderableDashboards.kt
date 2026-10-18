package ee.schimke.ha.client

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Strategies need HA's JavaScript frontend; decoding them as empty views hides that limitation. */
class UnsupportedDashboardException :
  IllegalArgumentException(
    "This dashboard needs Home Assistant's frontend. Choose a Lovelace dashboard with explicit views."
  )

internal fun JsonObject.hasRenderableViews(): Boolean =
  this["views"] is JsonArray &&
    "strategy" !in this &&
    (this["views"] as JsonArray).all { it is JsonObject && "strategy" !in it }

/** Only offer configurations the native renderer can consume, including hidden/YAML dashboards. */
suspend fun HaClient.listRenderableDashboards(): List<DashboardSummary> =
  resolveRenderableDashboards(listDashboards(), ::fetchDashboardConfig)

internal suspend fun resolveRenderableDashboards(
  named: List<DashboardSummary>,
  fetch: suspend (String?) -> JsonObject,
): List<DashboardSummary> {
  // Older HA versions omit the default dashboard from dashboards/list. Probe it,
  // rather than synthesising a Home entry that may be a non-Lovelace built-in page.
  val candidates =
    if (named.any { it.urlPath == null || it.urlPath == "lovelace" }) named
    else listOf(DashboardSummary(null, "Overview (Lovelace)")) + named
  return candidates
    .distinctBy { it.urlPath }
    .filter { dashboard ->
      try {
        fetch(dashboard.urlPath).hasRenderableViews()
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        // One missing/forbidden dashboard must not hide the user's other choices.
        false
      }
    }
}
