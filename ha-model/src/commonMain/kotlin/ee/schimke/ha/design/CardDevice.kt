package ee.schimke.ha.design

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cardEntityIds

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
 */
fun cardDevice(card: CardConfig, snapshot: HaSnapshot): DeviceInfo? =
  cardMainEntity(card)?.let { snapshot.devices[it] }
