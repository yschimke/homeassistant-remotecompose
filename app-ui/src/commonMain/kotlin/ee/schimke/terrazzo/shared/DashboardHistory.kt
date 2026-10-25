package ee.schimke.terrazzo.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import ee.schimke.ha.client.HaClient
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.HistoryPoint
import ee.schimke.ha.model.forCardHistoryWindow
import ee.schimke.ha.model.forecastRequests
import ee.schimke.ha.model.forecastsByEntity
import ee.schimke.ha.model.historyWindows
import ee.schimke.ha.model.withLiveHistoryTail
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/** How often history and forecasts are asked for again; live states move each line between. */
private val DATA_REFRESH = 5.minutes

/** How long the first fetch may hold the dashboard back before it shows without its data. */
private val FIRST_DATA_WAIT = 5.seconds

/** The history and forecasts a dashboard's data cards draw, as last fetched. */
internal data class DashboardData(
  val history: Map<String, List<HistoryPoint>> = emptyMap(),
  val forecasts: Map<String, JsonObject> = emptyMap(),
)

/**
 * The recorder history [dashboard]'s history graphs plot and the forecasts its weather cards show,
 * fetched with [client] and again every [DATA_REFRESH]. Null until the first fetch answers, fails
 * or takes longer than [FIRST_DATA_WAIT], so the dashboard can show those cards with data on their
 * first frame; empty for a dashboard without them.
 */
@Composable
internal fun rememberDashboardData(client: HaClient, dashboard: Dashboard?): DashboardData? {
  val requests =
    remember(dashboard) { dashboard?.let { historyWindows(it) to forecastRequests(it) } }
  val data by
    produceState<DashboardData?>(null, client, requests) {
      value = null
      val (windows, forecasts) = requests ?: return@produceState
      if (windows.isEmpty() && forecasts.isEmpty()) {
        value = DashboardData()
        return@produceState
      }
      suspend fun fetch(): DashboardData {
        val now = Clock.System.now()
        val history = buildMap {
          windows.entries.groupBy({ it.value }, { it.key }).forEach { (hours, ids) ->
            putAll(client.fetchHistory(ids, now - hours.hours, now))
          }
        }
        val byType = forecasts.mapValues { (type, ids) ->
          client.fetchForecasts(ids.toList(), type)
        }
        return DashboardData(history, forecastsByEntity(byType))
      }
      var first = true
      while (true) {
        try {
          val fetched = if (first) withTimeoutOrNull(FIRST_DATA_WAIT) { fetch() } else fetch()
          value = fetched ?: value ?: DashboardData()
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          // Keep what was shown; the next round asks again.
          if (value == null) value = DashboardData()
        }
        first = false
        delay(DATA_REFRESH)
      }
    }
  return data
}

/** [snapshot] with [data], each history series ending at its entity's live state. */
internal fun withDashboardData(snapshot: HaSnapshot, data: DashboardData?): HaSnapshot =
  if (data == null || (data.history.isEmpty() && data.forecasts.isEmpty())) snapshot
  else
    snapshot
      .copy(
        history = snapshot.history + data.history,
        forecasts = snapshot.forecasts + data.forecasts,
      )
      .withLiveHistoryTail()

/** [snapshot] for [card]: a history graph gets its own window of the dashboard's history. */
internal fun withCardHistoryWindow(
  card: CardConfig,
  snapshot: HaSnapshot,
  now: Instant = Clock.System.now(),
): HaSnapshot = snapshot.forCardHistoryWindow(card, now)
