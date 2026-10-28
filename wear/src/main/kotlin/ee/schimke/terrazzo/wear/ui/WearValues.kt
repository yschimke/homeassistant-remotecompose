package ee.schimke.terrazzo.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import kotlinx.coroutines.flow.StateFlow

/**
 * The watch's live entity values, as one object that never changes while the values in it do.
 *
 * Each entity is its own snapshot state, so a composable that reads `values["light.kitchen"]`
 * recomposes when that entity changes and for no other. Screens and lists take this object and read
 * nothing from it, so a streamed delta recomposes only the cards that show a changed value: with
 * the whole value map as a parameter, every delta recomposed the screen, the list and every visible
 * card.
 *
 * Main thread only: [update] and the reads in composition share the per-entity table. Hold one with
 * [rememberWearValues].
 */
@Stable
class WearValues(initial: Map<String, EntityValue> = emptyMap()) {
  /** The values as of the last [update]; seeds an entity's state when it is first read. */
  private var latest: Map<String, EntityValue> = initial
  private val entities = HashMap<String, MutableState<EntityValue?>>()
  private var all by mutableStateOf(initial)

  /**
   * [entityId]'s value, or null when the phone sent none. Recomposes the reader when it changes.
   */
  operator fun get(entityId: String): EntityValue? =
    entities.getOrPut(entityId) { mutableStateOf(latest[entityId]) }.value

  /** Every value. Recomposes the reader when any of them changes: prefer [forIds]. */
  fun all(): Map<String, EntityValue> = all

  /** The values of [entityIds] that exist, or [all] of them when [entityIds] is null. */
  fun forIds(entityIds: Set<String>?): Map<String, EntityValue> {
    if (entityIds == null) return all()
    val out = LinkedHashMap<String, EntityValue>(entityIds.size)
    for (id in entityIds) get(id)?.let { out[id] = it }
    return out
  }

  /** Replaces the values: only the entities whose value changed notify their readers. */
  fun update(values: Map<String, EntityValue>) {
    latest = values
    // Equal values are no-ops: these states use structural equality.
    for ((id, state) in entities) state.value = values[id]
    all = values
  }
}

/** A [WearValues] kept in step with [source], updated on the composition's (main) thread. */
@Composable
fun rememberWearValues(source: StateFlow<Map<String, EntityValue>>): WearValues {
  val values = remember(source) { WearValues(source.value) }
  LaunchedEffect(values) { source.collect(values::update) }
  return values
}
