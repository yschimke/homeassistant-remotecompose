package ee.schimke.terrazzo.wear.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HistoryPoint
import ee.schimke.terrazzo.wear.sync.WearDirectAccess
import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A card's details on the watch, as the phone's long press shows them: the card, and for each
 * entity it shows ([detailsEntityIds], the main one first) its last day of history
 * ([WearHistoryChart]) and its facts ([entityFacts]). Opened from the full-screen card
 * ([WearCardScreen]).
 *
 * History and live attributes are read from Home Assistant with the watch's own token when the
 * screen opens ([loadHistory], [loadStates]: `WearSyncRepository.history` / `liveStates`). Without
 * one, or out of reach, the charts say so and the facts fall back to what the phone syncs.
 */
@Composable
internal fun WearCardDetailsScreen(
  summary: CardSummary,
  values: WearValues,
  loadHistory: suspend (entityId: String) -> List<HistoryPoint>?,
  modifier: Modifier = Modifier,
  loadStates: suspend (ids: Set<String>) -> Map<String, EntityState>? = { null },
  now: () -> Instant = Clock.System::now,
) {
  val entityIds = remember(summary) { detailsEntityIds(summary) }
  val live by
    produceState<Map<String, EntityState>?>(null, entityIds) {
      value = loadStates(entityIds.toSet())
    }
  val openedAt = remember { now() }
  val main = entityIds.firstOrNull()
  WearListScreen(modifier = modifier) {
    item { ListHeader { Text(detailsTitle(summary, main, main?.let { values[it] })) } }
    item { WearRemoteCard(summary, values, fullScreen = true) }
    entityIds.forEach { id ->
      val value = values[id]
      val state = live?.get(id)
      if (entityIds.size > 1) {
        item(key = "name:$id") { ListSubHeader { Text(entityName(id, value, state)) } }
      }
      item(key = "history:$id") { HistorySection(id, value?.unit.orEmpty(), loadHistory, now) }
      val facts = entityFacts(id, value, state, openedAt)
      items(facts.size, key = { "fact:$id:$it" }) { i ->
        AttributeRow(facts[i].first, facts[i].second)
      }
    }
  }
}

/** [entityId]'s name: as the phone synced it, else as Home Assistant has it, else its id. */
internal fun entityName(entityId: String, value: EntityValue?, live: EntityState?): String =
  value?.friendlyName?.ifBlank { null }
    ?: (live?.attributes?.get("friendly_name") as? JsonPrimitive)?.contentOrNull?.ifBlank { null }
    ?: entityId

/** What the history read gave: still loading, the chart, or why there is none. */
private sealed interface HistoryLoad {
  data object Loading : HistoryLoad

  data class Loaded(val chart: HistoryChart) : HistoryLoad

  data object Unavailable : HistoryLoad
}

@Composable
private fun HistorySection(
  entityId: String,
  unit: String,
  loadHistory: suspend (String) -> List<HistoryPoint>?,
  now: () -> Instant,
) {
  val load by
    produceState<HistoryLoad>(HistoryLoad.Loading, entityId) {
      val end = now()
      val start = end - WearDirectAccess.HISTORY_HOURS.hours
      value =
        loadHistory(entityId)?.let { HistoryLoad.Loaded(historyChart(it, start, end)) }
          ?: HistoryLoad.Unavailable
    }
  Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
    Text("Last 24 hours", style = MaterialTheme.typography.titleSmall)
    when (val current = load) {
      HistoryLoad.Loading -> Muted("Loading…")
      HistoryLoad.Unavailable ->
        Muted("History needs Home Assistant access. Open the Terrazzo app on the phone.")
      is HistoryLoad.Loaded -> WearHistoryChart(current.chart, unit)
    }
  }
}

@Composable
private fun AttributeRow(label: String, text: String) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
    Text(
      label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(text, style = MaterialTheme.typography.bodyMedium)
  }
}

@Composable
private fun Muted(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

/** The details screen's title: the card's, else its main entity's name, else the entity id. */
internal fun detailsTitle(summary: CardSummary, entityId: String?, value: EntityValue?): String =
  summary.title.takeIf { it.isNotBlank() && it != summary.type }
    ?: value?.friendlyName?.ifBlank { null }
    ?: entityId
    ?: "Card"
