package ee.schimke.terrazzo.room

import ee.schimke.ha.design.DeviceInfo
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaLiveBindings
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.room.DashboardImportance
import ee.schimke.ha.room.HaArea
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The house the Quest and glasses apps show until they can sign in to a Home Assistant: a living
 * room with a heater, a TV, a lamp and a fan, and a kitchen. Their previews draw the same house, so
 * a screenshot matches what the device shows.
 *
 * The phone app's demo (`DemoHaSession`) lives in `:terrazzo-core`, which needs API 35 and a
 * session; this one is a plain snapshot, toggled in memory, that any host can show.
 */
object DemoHouse {
  /** Kept as Quest's first demo id, so pins made before the move still match. */
  const val BASE_URL = "quest-demo://house"
  const val HEATER = "climate.living_room_heater"
  const val TV = "media_player.living_room_tv"
  const val LAMP = "light.living_room_lamp"
  const val FAN = "switch.living_room_fan"
  const val KITCHEN_LIGHTS = "light.kitchen"

  val livingRoom = HaArea("living_room", "Living Room", aliases = listOf("Lounge"))
  val kitchen = HaArea("kitchen", "Kitchen")

  val snapshot: HaSnapshot =
    HaSnapshot(
      states =
        listOf(
            state(HEATER, "heat", "Living Room Heater"),
            state(TV, "playing", "Living Room TV"),
            state(LAMP, "on", "Living Room Lamp"),
            state(FAN, "off", "Living Room Fan"),
            state(KITCHEN_LIGHTS, "off", "Kitchen Lights"),
          )
          .associateBy { it.entityId },
      devices =
        mapOf(
          HEATER to DeviceInfo(areaId = livingRoom.id),
          TV to DeviceInfo(areaId = livingRoom.id),
          LAMP to DeviceInfo(areaId = livingRoom.id),
          FAN to DeviceInfo(areaId = livingRoom.id),
          KITCHEN_LIGHTS to DeviceInfo(areaId = kitchen.id),
        ),
      areas = listOf(livingRoom, kitchen).associateBy { it.id },
    )

  /**
   * What the house's dashboards would show: the heater and TV up front, the living room as the room
   * they care about. Without it every room ties and the kitchen comes first by name.
   */
  val importance: DashboardImportance =
    DashboardImportance(
      entities = mapOf(HEATER to 3f, TV to 2f, LAMP to 1f),
      areas = mapOf(livingRoom.id to 1f),
    )

  /** [snapshot] with [entityId] switched the other way, as `homeassistant.toggle` would. */
  fun toggled(snapshot: HaSnapshot, entityId: String): HaSnapshot {
    val state = snapshot.states[entityId] ?: return snapshot
    val next =
      when {
        entityId.startsWith("climate.") -> if (state.state == "off") "heat" else "off"
        entityId.startsWith("media_player.") -> if (state.state == "off") "playing" else "off"
        HaLiveBindings.isOn(entityId, state.state) -> "off"
        else -> "on"
      }
    return snapshot.copy(states = snapshot.states + (entityId to state.copy(state = next)))
  }

  private fun state(id: String, state: String, name: String) =
    EntityState(
      entityId = id,
      state = state,
      attributes = buildJsonObject { put("friendly_name", name) },
    )
}

/** Domains a host turns on and off with `homeassistant.toggle`: a switch or a tap toggles them. */
val TOGGLE_DOMAINS: Set<String> =
  setOf("light", "switch", "fan", "input_boolean", "media_player", "humidifier", "climate")
