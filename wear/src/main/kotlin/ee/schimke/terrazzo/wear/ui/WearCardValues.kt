package ee.schimke.terrazzo.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import ee.schimke.terrazzo.wearsync.proto.EntityValue

/**
 * The entities a card on the watch reads: every entity id its config names, found the way the phone
 * picks the values it sends the watch (`wearEntityIds` in `MobileWearSyncManager`), so a card's
 * slice holds everything the watch has for it. Null for a Bambu Lab card: on a dashboard the phone
 * resolves its printer's other sensors through the device registry, which the watch doesn't have,
 * so those cards keep every value.
 */
internal fun wearCardEntityIds(rawJson: String): Set<String>? =
  if (BAMBU_CARD_MARKER in rawJson) null
  else ENTITY_ID.findAll(rawJson).mapTo(HashSet()) { it.value }

/**
 * The values of the entities the card [rawJson] reads, and the same map instance while they don't
 * change. Reads only those entities from [values], so only their changes recompose the caller.
 */
@Composable
internal fun rememberCardValues(rawJson: String, values: WearValues): Map<String, EntityValue> {
  val entityIds = remember(rawJson) { wearCardEntityIds(rawJson) }
  val slice = values.forIds(entityIds)
  // `remember` compares its key with `==`: an equal slice returns the instance remembered first.
  return remember(slice) { slice }
}

/** Mirrors the phone's entity-id pattern for the values it sends the watch. */
private val ENTITY_ID = Regex("""\b[a-z_][a-z0-9_]*\.[a-z0-9_]+\b""")

/** In every Bambu Lab card type (`custom:ha-bambulab-…`), stacked or not. */
private const val BAMBU_CARD_MARKER = "ha-bambulab"
