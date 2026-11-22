package ee.schimke.ha.design

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cardEntityIds
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The entity whose device a card's design is picked for: the entity of its first design slot
 * ([CardDesignSlots.slotEntries]), which the design draws as the card's subject; for a card with no
 * slots, the first entity its config names ([cardEntityIds]). Only one entity counts, so a later
 * row's device never restyles the whole card, and an entity an action names (`tap_action.entity`)
 * never decides it. Null for a card that names no entity.
 */
fun cardMainEntity(card: CardConfig): String? =
  CardDesignSlots.slotEntries(card)?.firstOrNull()?.entityId ?: cardEntityIds(card).firstOrNull()

/**
 * What Home Assistant knows about the device of [card]'s main entity ([cardMainEntity]), from
 * [snapshot]'s registry: what a device-specific design is matched against ([DeviceMatch.pick],
 * `designOptions`). Null when the card names no entity or the registry doesn't list it (the user
 * can't read the device registry), and the card gets the designs for every device.
 *
 * Its [DeviceInfo.deviceClass] is the entity's `device_class` from its state, as Home Assistant
 * draws it: a design for a class (every garage door) matches on it.
 */
fun cardDevice(card: CardConfig, snapshot: HaSnapshot): DeviceInfo? =
  cardMainEntity(card)?.let { entityDevice(it, snapshot) }

/** [entityId]'s registry facts in [snapshot], with its state's `device_class`: see [cardDevice]. */
fun entityDevice(entityId: String, snapshot: HaSnapshot): DeviceInfo? {
  val registered = snapshot.devices[entityId]
  val deviceClass =
    (snapshot.states[entityId]?.attributes?.get("device_class") as? JsonPrimitive)?.contentOrNull
  // An entity the registry doesn't list (or a user who can't read it) still has its state's class.
  return when {
    deviceClass == null -> registered
    registered == null -> DeviceInfo(deviceClass = deviceClass)
    else -> registered.copy(deviceClass = deviceClass)
  }
}
