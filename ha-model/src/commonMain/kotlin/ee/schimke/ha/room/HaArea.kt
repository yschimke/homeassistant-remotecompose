package ee.schimke.ha.room

import ee.schimke.ha.model.HaSnapshot
import kotlinx.serialization.Serializable

/**
 * One of Home Assistant's areas (`config/area_registry/list`): a room an entity or its device is
 * placed in. [aliases] are the other names voice assistants accept for it, which a room tracker's
 * state may use too.
 */
@Serializable
data class HaArea(
  val id: String,
  val name: String,
  val floorId: String? = null,
  val icon: String? = null,
  val aliases: List<String> = emptyList(),
) {
  /**
   * Whether [label] names this area: its id, its name or one of its aliases, ignoring case and
   * treating spaces, dashes and underscores alike (`Living Room`, `living_room`, `living-room`).
   */
  fun isNamed(label: String): Boolean {
    val wanted = label.normalisedAreaLabel()
    if (wanted.isEmpty()) return false
    return id.normalisedAreaLabel() == wanted ||
      name.normalisedAreaLabel() == wanted ||
      aliases.any { it.normalisedAreaLabel() == wanted }
  }
}

/** The area [entityId] is in, where the registries say: its own area, else its device's. */
fun HaSnapshot.areaOf(entityId: String): HaArea? = devices[entityId]?.areaId?.let { areas[it] }

/** The ids of the entities in [areaId], in snapshot order. */
fun HaSnapshot.entitiesIn(areaId: String): List<String> =
  states.keys.filter { devices[it]?.areaId == areaId }

/** The area [label] names ([HaArea.isNamed]), or null. */
fun HaSnapshot.areaNamed(label: String): HaArea? = areas.values.firstOrNull { it.isNamed(label) }

private fun String.normalisedAreaLabel(): String =
  trim()
    .lowercase()
    .replace('-', ' ')
    .replace('_', ' ')
    .split(' ')
    .filter { it.isNotEmpty() }
    .joinToString(" ")
