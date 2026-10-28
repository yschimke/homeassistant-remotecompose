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

/** [values] narrowed to [entityIds], or all of them when [entityIds] is null. */
internal fun Map<String, EntityValue>.forCard(entityIds: Set<String>?): Map<String, EntityValue> =
  if (entityIds == null) this else filterKeys { it in entityIds }

/**
 * [values] narrowed to the entities the card [rawJson] reads, and the same map instance while those
 * entities' values don't change.
 *
 * Every streamed delta replaces the watch's whole value map, which recomposes the list. With the
 * whole map as its parameter, every visible card then re-ran its body for a value it doesn't show.
 * With its own slice, unchanged, a card's composable skips.
 */
@Composable
internal fun rememberCardValues(
  rawJson: String,
  values: Map<String, EntityValue>,
): Map<String, EntityValue> {
  val entityIds = remember(rawJson) { wearCardEntityIds(rawJson) }
  val slice = values.forCard(entityIds)
  // `remember` compares its key with `==`: an equal slice returns the instance remembered first.
  return remember(slice) { slice }
}

/** Mirrors the phone's entity-id pattern for the values it sends the watch. */
private val ENTITY_ID = Regex("""\b[a-z_][a-z0-9_]*\.[a-z0-9_]+\b""")

/** In every Bambu Lab card type (`custom:ha-bambulab-…`), stacked or not. */
private const val BAMBU_CARD_MARKER = "ha-bambulab"
