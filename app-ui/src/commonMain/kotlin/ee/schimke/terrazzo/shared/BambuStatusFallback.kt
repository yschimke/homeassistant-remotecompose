package ee.schimke.terrazzo.shared

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.*
import ee.schimke.ha.rc.formatState
import kotlinx.serialization.json.*

internal fun isBambuStatus(card: CardConfig): Boolean =
  card.type in setOf("custom:ha-bambulab-print_status-card", "custom:ha_bambulab_print_status_card")

/** No global suffix guessing: only a configured prefix or the configured device's own entities. */
internal fun bambuStatusEntities(
  card: CardConfig,
  snapshot: HaSnapshot,
): List<Pair<String, EntityState>> {
  val device = (card.raw["printer"] as? JsonPrimitive)?.contentOrNull
  val prefix = (card.raw["entity_prefix"] as? JsonPrimitive)?.contentOrNull
  val readings =
    listOf(
      "print_status" to "Status",
      "current_stage" to "Stage",
      "print_progress" to "Progress",
      "current_layer" to "Current layer",
      "total_layer_count" to "Total layers",
      "remaining_time" to "Remaining",
    )
  return readings.mapNotNull { (suffix, label) ->
    val matches =
      snapshot.states.values.filter { entity ->
        if (prefix != null) entity.entityId == "sensor.${prefix}_$suffix"
        else
          device != null &&
            snapshot.devices[entity.entityId]?.deviceId == device &&
            entity.entityId.startsWith("sensor.") &&
            (snapshot.devices[entity.entityId]?.translationKey == suffix ||
              entity.entityId.endsWith("_$suffix"))
      }
    matches.singleOrNull()?.let { label to it }
  }
}

@Composable
internal fun BambuStatusFallback(
  card: CardConfig,
  snapshot: HaSnapshot,
  onMoreInfo: (String) -> Unit,
) {
  val readings = bambuStatusEntities(card, snapshot)
  OutlinedCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("Bambu Lab print status", style = MaterialTheme.typography.titleMedium)
      if (readings.isEmpty())
        Text("Printer readings could not be resolved from this card’s configured device.")
      readings.forEach { (label, entity) ->
        Text(
          "$label: ${formatState(entity, snapshot)}",
          Modifier.entityDetails(entity.entityId, onMoreInfo),
          style = MaterialTheme.typography.bodyMedium,
        )
      }
      Text(
        "Basic readings only. Open the original dashboard in Home Assistant for the custom visuals and controls.",
        style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}
