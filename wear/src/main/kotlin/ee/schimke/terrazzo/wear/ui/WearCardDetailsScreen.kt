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
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import ee.schimke.ha.model.HistoryPoint
import ee.schimke.terrazzo.wear.data.displayState
import ee.schimke.terrazzo.wear.sync.WearDirectAccess
import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * A card's details on the watch, as the phone's long press shows them: the card, its main entity's
 * last day of history ([WearHistoryChart]) and the entity's attributes. Opened from the full-screen
 * card ([WearCardScreen]).
 *
 * History is read from Home Assistant with the watch's own token when the screen opens
 * ([loadHistory], `WearSyncRepository.history`); without one, or out of reach, the chart says so
 * and the rest still shows.
 */
@Composable
internal fun WearCardDetailsScreen(
  summary: CardSummary,
  values: Map<String, EntityValue>,
  loadHistory: suspend (entityId: String) -> List<HistoryPoint>?,
  modifier: Modifier = Modifier,
  now: () -> Instant = Clock.System::now,
) {
  val entityId = remember(summary) { detailsEntityId(summary) }
  val value = entityId?.let { values[it] }
  val attributes = entityId?.let { detailsAttributes(it, value) }.orEmpty()
  WearListScreen(modifier = modifier) {
    item { ListHeader { Text(detailsTitle(summary, entityId, value)) } }
    item { WearRemoteCard(summary, values, fullScreen = true) }
    if (entityId != null) {
      item { HistorySection(entityId, value?.unit.orEmpty(), loadHistory, now) }
      items(attributes.size) { i -> AttributeRow(attributes[i].first, attributes[i].second) }
    }
  }
}

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

/** The entity a card's details are about: the one the phone named, else the card's `entity`. */
internal fun detailsEntityId(summary: CardSummary): String? =
  summary.primaryEntityId.ifBlank { null }
    ?: runCatching {
      (kotlinx.serialization.json.Json.parseToJsonElement(summary.rawJson).jsonObject["entity"]
          as? JsonPrimitive)
        ?.contentOrNull
    }
      .getOrNull()
      ?.ifBlank { null }

/** The details screen's title: the card's, else its entity's name, else the entity id. */
internal fun detailsTitle(summary: CardSummary, entityId: String?, value: EntityValue?): String =
  summary.title.takeIf { it.isNotBlank() && it != summary.type }
    ?: value?.friendlyName?.ifBlank { null }
    ?: entityId
    ?: "Card"

/** The entity's facts the details list, label first: its state as the card shows it, then more. */
internal fun detailsAttributes(entityId: String, value: EntityValue?): List<Pair<String, String>> =
  buildList {
    if (value == null) {
      add("State" to "Not synced yet")
      return@buildList
    }
    add("State" to value.displayState(entityId))
    if (value.deviceClass.isNotBlank()) add("Type" to value.deviceClass.replace('_', ' '))
    value.attributes.forEach { (key, text) ->
      if (text.isNotBlank()) add(key.replace('_', ' ').replaceFirstChar { it.uppercase() } to text)
    }
    add("Entity" to entityId)
  }
