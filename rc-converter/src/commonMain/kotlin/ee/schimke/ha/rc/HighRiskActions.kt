package ee.schimke.ha.rc

import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.HaAction
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

  /** Whether running [action] on [snapshot]'s entities is high risk. */
  fun isHighRisk(action: HaAction, snapshot: HaSnapshot): Boolean {
    val (domain, entityId) =
      when (action) {
        is HaAction.Toggle -> action.entityId.substringBefore('.') to action.entityId
        is HaAction.CallService -> action.domain to action.entityId
        is HaAction.AlarmIntent -> "alarm_control_panel" to action.entityId
        is HaAction.AlarmPin -> "alarm_control_panel" to action.entityId
        is HaAction.AlarmKey -> "alarm_control_panel" to action.entityId
        is HaAction.MoreInfo,
        is HaAction.Navigate,
        is HaAction.Url,
        HaAction.None -> return false
      }
    // A service on another domain can still target one (`homeassistant.toggle` on a lock).
    val entityDomain = entityId?.substringBefore('.')
    if (domain in DOMAINS || entityDomain in DOMAINS) return true
    if (domain != "cover" && entityDomain != "cover") return false
    val deviceClass = entityId?.let {
      snapshot.states[it]?.attributes?.get("device_class") as? JsonPrimitive
    }
    // A cover whose class isn't known could be a garage door: ask rather than guess.
    return deviceClass?.contentOrNull?.let { it in COVER_DEVICE_CLASSES } ?: true
  }
}
