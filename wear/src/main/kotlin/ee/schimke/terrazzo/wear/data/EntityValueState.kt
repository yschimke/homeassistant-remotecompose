package ee.schimke.terrazzo.wear.data

import ee.schimke.ha.model.EntityState
import ee.schimke.ha.rc.formatState
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * This synced value as the [EntityState] the shared formatter and card converters read. Only the
 * fields the wire format carries survive the round-trip — state, friendly_name,
 * unit_of_measurement, device_class, entity_picture, and the phone's display precision.
 */
internal fun EntityValue.toEntityState(entityId: String): EntityState {
  val attrs =
    buildMap<String, JsonPrimitive> {
      if (friendlyName.isNotEmpty()) put("friendly_name", JsonPrimitive(friendlyName))
      if (unit.isNotEmpty()) put("unit_of_measurement", JsonPrimitive(unit))
      if (deviceClass.isNotEmpty()) put("device_class", JsonPrimitive(deviceClass))
      if (entityPicture.isNotEmpty()) put("entity_picture", JsonPrimitive(entityPicture))
      // The phone's resolved precision, as the attribute `formatState` reads: text rows and the
      // slot widget's converters then round exactly as the phone does.
      displayPrecision?.let { put("display_precision", JsonPrimitive(it)) }
    }
  return EntityState(entityId = entityId, state = state, attributes = JsonObject(attrs))
}

/**
 * The display string for this value, from the same `formatState` the phone's cards use, so Wear
 * reads `On` / `21.5 °C` / `3 h ago` exactly as the phone does rather than the raw `on`.
 */
internal fun EntityValue.displayState(entityId: String): String =
  formatState(toEntityState(entityId))
