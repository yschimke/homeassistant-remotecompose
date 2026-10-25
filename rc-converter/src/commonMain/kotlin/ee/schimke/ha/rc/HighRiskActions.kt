package ee.schimke.ha.rc

import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.HaAction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Actions that open the house to someone: a lock, an alarm panel, a garage door, a gate or a door
 * cover. Surfaces that act without the app's own screen in front (a launcher widget, a watch
 * gesture) hold these to the phone's lock mode: while it is locked, the app asks before running
 * one.
 */
object HighRiskActions {

  /** Entity domains whose every write is high risk. */
  val DOMAINS: Set<String> = setOf("lock", "alarm_control_panel")

  /** `cover` device classes that open a way in. */
  val COVER_DEVICE_CLASSES: Set<String> = setOf("garage", "gate", "door")

  /**
   * Service-data keys that target something other than named entities: an area, a device, a floor
   * or a label can hold a lock, so a generic service aimed at one is high risk.
   */
  private val GROUP_TARGETS = setOf("area_id", "device_id", "floor_id", "label_id")

  /** Whether running [action] on [snapshot]'s entities is high risk. */
  fun isHighRisk(action: HaAction, snapshot: HaSnapshot): Boolean {
    val (domain, entityIds) =
      when (action) {
        is HaAction.Toggle -> action.entityId.substringBefore('.') to listOf(action.entityId)
        is HaAction.CallService -> {
          // Several entities, or an area or device, travel in the service data (parseHaAction).
          val targets = action.serviceData.entityIds()
          if (
            action.entityId == null &&
              targets.isEmpty() &&
              action.serviceData.keys.any { it in GROUP_TARGETS }
          ) {
            // No named entity to check: only a domain that can't reach a lock is safe.
            return action.domain in DOMAINS ||
              action.domain == "cover" ||
              action.domain in GENERIC_DOMAINS
          }
          action.domain to listOfNotNull(action.entityId) + targets
        }
        is HaAction.AlarmIntent -> "alarm_control_panel" to listOf(action.entityId)
        is HaAction.AlarmPin -> "alarm_control_panel" to listOf(action.entityId)
        is HaAction.AlarmKey -> "alarm_control_panel" to listOf(action.entityId)
        is HaAction.MoreInfo,
        is HaAction.Navigate,
        is HaAction.Url,
        HaAction.None -> return false
      }
    if (domain in DOMAINS) return true
    // A cover service that names no entity: which cover it opens isn't known.
    if (domain == "cover" && entityIds.isEmpty()) return true
    // A service on another domain can still target one (`homeassistant.toggle` on a lock).
    return entityIds.any { isHighRiskEntity(it, snapshot) }
  }

  private fun isHighRiskEntity(entityId: String, snapshot: HaSnapshot): Boolean {
    val domain = entityId.substringBefore('.')
    if (domain in DOMAINS) return true
    if (domain != "cover") return false
    val deviceClass = snapshot.states[entityId]?.attributes?.get("device_class") as? JsonPrimitive
    // A cover whose class isn't known could be a garage door: ask rather than guess.
    return deviceClass?.contentOrNull?.let { it in COVER_DEVICE_CLASSES } ?: true
  }

  /** Services that act on any domain's entities. */
  private val GENERIC_DOMAINS = setOf("homeassistant")

  /** The `entity_id` targets in service data: one id, a comma-separated list, or an array. */
  private fun JsonObject.entityIds(): List<String> =
    when (val ids = this["entity_id"]) {
        is JsonPrimitive -> ids.contentOrNull?.split(',').orEmpty()
        is JsonArray -> ids.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        else -> emptyList()
      }
      .map { it.trim() }
      .filter { it.isNotEmpty() }
}
