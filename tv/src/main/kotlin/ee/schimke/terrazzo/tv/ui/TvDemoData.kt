package ee.schimke.terrazzo.tv.ui

import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Lightweight, TV-only demo fixture. Mirrors the shape of the phone's `DemoData` (animated sensors
 * / lights / power) but stays inside this module — the TV doesn't pair with a phone, so demo mode
 * here is a local toggle that drives the kiosk preview off these values.
 *
 * Values drift with wall-clock time so the room sees motion instead of a frozen screen.
 */
object TvDemoData {

  data class Tile(val label: String, val value: String)

  fun tiles(nowMs: Long = System.currentTimeMillis()): List<Tile> {
    val tSec = nowMs / 1000.0
    val livingTemp = 21.0 + 2.0 * sin(tSec / 60.0)
    val humidity = (48.0 + 10.0 * sin(tSec / 20.0)).roundToInt()
    val power = (170.0 + 90.0 * sin(tSec / 7.0)).roundToInt()
    val lightsOn = (nowMs / 8_000L) % 2L == 0L
    val mediaTrack = MEDIA_QUEUE[(nowMs / 6_000L % MEDIA_QUEUE.size).toInt()]
    return listOf(
      Tile("Lights", if (lightsOn) "ON · 70%" else "OFF"),
      Tile("Climate", "%.1f °C".format(livingTemp)),
      Tile("Humidity", "$humidity%"),
      Tile("Power", "$power W"),
      Tile("Media", mediaTrack),
    )
  }

  /**
   * The same moment as [tiles] as Home Assistant state, for the kiosk's real cards: the living-room
   * light, thermostat and humidity sensor.
   */
  fun snapshot(nowMs: Long = System.currentTimeMillis()): HaSnapshot {
    val tSec = nowMs / 1000.0
    val livingTemp = 21.0 + 2.0 * sin(tSec / 60.0)
    val humidity = (48.0 + 10.0 * sin(tSec / 20.0)).roundToInt()
    val lightsOn = (nowMs / 8_000L) % 2L == 0L
    fun state(id: String, state: String, vararg attributes: Pair<String, Any>) =
      id to
        EntityState(
          entityId = id,
          state = state,
          attributes =
            JsonObject(
              attributes.associate { (k, v) ->
                k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString())
              }
            ),
        )
    return HaSnapshot(
      states =
        mapOf(
          state(
            LIGHT,
            if (lightsOn) "on" else "off",
            "friendly_name" to "Lights",
            "brightness" to 178,
          ),
          state(
            CLIMATE,
            "heat",
            "friendly_name" to "Climate",
            "current_temperature" to (livingTemp * 10).roundToInt() / 10.0,
            "temperature" to 22.0,
            "min_temp" to 7.0,
            "max_temp" to 35.0,
            "temperature_unit" to "°C",
            "hvac_action" to if (livingTemp < 22.0) "heating" else "idle",
            "target_temp_step" to 0.5,
          ),
          state(
            HUMIDITY,
            humidity.toString(),
            "friendly_name" to "Humidity",
            "unit_of_measurement" to "%",
            "device_class" to "humidity",
          ),
        )
    )
  }

  /**
   * The same entities with no data: every one `unavailable`, for the kiosk outside demo mode, so it
   * shows the cards without inventing readings.
   */
  fun placeholderSnapshot(): HaSnapshot =
    HaSnapshot(
      states =
        listOf(LIGHT to "Lights", CLIMATE to "Climate", HUMIDITY to "Humidity").associate {
          (id, name) ->
          id to
            EntityState(
              entityId = id,
              state = "unavailable",
              attributes = JsonObject(mapOf("friendly_name" to JsonPrimitive(name))),
            )
        }
    )

  const val LIGHT = "light.living_room"
  const val CLIMATE = "climate.living_room"
  const val HUMIDITY = "sensor.living_room_humidity"

  private val MEDIA_QUEUE =
    listOf("Spotify · Sketches", "Radio · Studio Brussel", "Podcast · Lex", "Spotify · Late Night")
}
