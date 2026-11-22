package ee.schimke.ha.designserver

import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.design.HaSharedValues
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * Made-up Home Assistant data for an agent session's sample renders: values for a card draft's
 * bindings, and states for the entities a dashboard draft names. The server holds no home's data,
 * so these are never anyone's: they come from the library's own catalog examples where it has them
 * and are otherwise invented here, the same for the same input every time.
 */
object AgentSamples {
  /** A card or slot binding: `light.s0.state` is slot 0 (a light), its `state`. */
  private val SLOT_BINDING = Regex("^([a-z_]+)\\.s(\\d+)\\.(.+)$")

  /** A Home Assistant entity id: `light.kitchen`. */
  private val ENTITY_ID = Regex("^[a-z_]+\\.[a-z0-9_]+$")

  /** The binding examples the library's catalog shows, by binding name. */
  fun catalogExamples(library: DesignLibrary?, cardType: String?): Map<String, JsonPrimitive> {
    val entries = library?.catalog?.entries.orEmpty()
    // The session's own card type first, then every other design: the first example of a name
    // wins, so a tile session reads a tile's.
    val ordered =
      entries.filter { it.key.cardType == cardType } +
        entries.filter { it.key.cardType != cardType }
    val examples = LinkedHashMap<String, JsonPrimitive>()
    for (entry in ordered) {
      entry.example?.bindings?.forEach { (name, value) ->
        if (value !is JsonNull && name !in examples) examples[name] = value
      }
    }
    return examples
  }

  /**
   * A sample value for each of a card draft's `stateVariables`, by name, of its `valueType`: a
   * shared value at its daytime default ([HaSharedValues]), else the catalog's example in
   * [examples], else one made up for the binding (a realistic state for its slot's domain, a name
   * like "Kitchen light", 21.5, true).
   */
  fun cardValues(
    stateVariables: JsonObject,
    examples: Map<String, JsonPrimitive> = emptyMap(),
  ): Map<String, JsonPrimitive> {
    val invented = slotValues(stateVariables.keys)
    return stateVariables.entries.associate { (name, definition) ->
      val valueType =
        ((definition as? JsonObject)?.get("valueType") as? JsonPrimitive)?.contentOrNull ?: "string"
      val shared = HaSharedValues.valueOf(name)?.daytime?.let(::primitive)
      val value = shared ?: examples[name] ?: invented[name] ?: fallback(name, valueType)
      name to (coerce(value, valueType) ?: fallback(name, valueType))
    }
  }

  /**
   * Slot bindings for made-up entities, one per slot named in [names] (`light.s0` → a light), as
   * the app computes them ([CardDesignSlots.slotBindings]): its states formatted as a card shows
   * them.
   */
  private fun slotValues(names: Collection<String>): Map<String, JsonPrimitive> {
    val slots =
      names
        .mapNotNull { SLOT_BINDING.find(it)?.destructured }
        .associate { (domain, index, _) -> index.toInt() to domain }
    if (slots.isEmpty()) return emptyMap()
    val count = slots.keys.max() + 1
    if (count > 64) return emptyMap()
    // The first light is the kitchen's, the second the hall's, whatever slots come between.
    val seen = HashMap<String, Int>()
    val ids =
      List(count) { i ->
        val domain = slots[i] ?: "sensor"
        sampleEntityId(domain, seen.merge(domain, 1, Int::plus)!! - 1)
      }
    val card =
      CardConfig(
        type = "entities",
        raw =
          buildJsonObject {
            put("type", "entities")
            put("title", "Living room")
            put("entities", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
          },
      )
    val bindings =
      CardDesignSlots.slotBindings(card, HaSnapshot(states = snapshot(ids).states), TimeZone.UTC)
    return buildMap {
      bindings.strings.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
      bindings.booleans.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
      bindings.ints.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
      bindings.floats.forEach { (k, v) -> if (v.isFinite()) put(k, JsonPrimitive(v)) }
    }
  }

  /** A value of [valueType] for a binding nothing else knows. */
  private fun fallback(name: String, valueType: String): JsonPrimitive =
    when (valueType) {
      "bool" -> JsonPrimitive(true)
      "int" -> JsonPrimitive(1)
      "float" -> JsonPrimitive(21.5)
      else ->
        when {
          name == CardDesignSlots.CARD_TITLE || name.endsWith(".title") ->
            JsonPrimitive("Living room")
          name.endsWith("friendly_name") || name.endsWith(".name") -> JsonPrimitive("Kitchen light")
          name.endsWith(".state") -> JsonPrimitive("On")
          else -> JsonPrimitive("Sample")
        }
    }

  private fun coerce(value: JsonPrimitive, valueType: String): JsonPrimitive? =
    when (valueType) {
      "bool" ->
        value.booleanOrNull?.let(::JsonPrimitive)
          ?: value.doubleOrNull?.let { JsonPrimitive(it != 0.0) }
      "int" -> value.doubleOrNull?.takeIf { it.isFinite() }?.let { JsonPrimitive(it.toLong()) }
      "float" -> value.doubleOrNull?.takeIf { it.isFinite() }?.let(::JsonPrimitive)
      else -> JsonPrimitive(value.contentOrNull ?: return null)
    }

  private fun primitive(value: Any): JsonPrimitive =
    when (value) {
      is Boolean -> JsonPrimitive(value)
      is Number -> JsonPrimitive(value.toDouble())
      else -> JsonPrimitive(value.toString())
    }

  /** Every entity id [config] (a Lovelace dashboard or card) names, in the order it names them. */
  fun entityIds(config: JsonElement): Set<String> {
    val ids = LinkedHashSet<String>()
    fun visit(element: JsonElement, key: String?) {
      when (element) {
        is JsonObject -> element.forEach { (k, v) -> visit(v, k) }
        is JsonArray -> element.forEach { visit(it, key) }
        is JsonPrimitive ->
          if (
            element.isString &&
              key in ENTITY_KEYS &&
              ENTITY_ID.matches(element.content) &&
              ids.size < MAX_ENTITIES
          )
            ids += element.content
      }
    }
    visit(config, null)
    return ids
  }

  private val ENTITY_KEYS = setOf("entity", "entity_id", "entities", "camera_image")

  private const val MAX_ENTITIES = 500

  /** Made-up states for [entityIds], plus the sun every home shares ([HaSharedValues]). */
  fun snapshot(entityIds: Collection<String>): HaSnapshot =
    HaSnapshot(
      states = (entityIds + SUN).distinct().associateWith(::state),
    )

  private const val SUN = "sun.sun"

  /**
   * A made-up state for [entityId]: realistic for its domain (a light on at 70%, a temperature
   * sensor at 21.5 °C, a door open), its `device_class` guessed from its object id, and a friendly
   * name from it (`light.kitchen_ceiling` → "Kitchen ceiling").
   */
  fun state(entityId: String): EntityState {
    val domain = entityId.substringBefore('.')
    val objectId = entityId.substringAfter('.')
    val name =
      objectId
        .split('_')
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .replaceFirstChar { it.uppercase() }
    fun has(vararg words: String) = words.any { it in objectId }
    val attrs = LinkedHashMap<String, JsonElement>()
    attrs["friendly_name"] = JsonPrimitive(name.ifEmpty { domain })
    fun put(key: String, value: Any) {
      attrs[key] =
        when (value) {
          is JsonElement -> value
          is Boolean -> JsonPrimitive(value)
          is Number -> JsonPrimitive(value)
          is List<*> -> JsonArray(value.map { JsonPrimitive(it.toString()) })
          else -> JsonPrimitive(value.toString())
        }
    }
    val state =
      when (domain) {
        "light" -> {
          put("brightness", 180)
          put("color_mode", "brightness")
          put("supported_color_modes", listOf("brightness"))
          "on"
        }
        "switch",
        "input_boolean",
        "fan",
        "automation",
        "siren",
        "humidifier" -> {
          if (domain == "fan") put("percentage", 66)
          if (domain == "humidifier") put("humidity", 45)
          "on"
        }
        "sensor" -> sensor(objectId, ::put)
        "binary_sensor" -> {
          val deviceClass =
            when {
              has("door", "gate") -> "door"
              has("window") -> "window"
              has("garage") -> "garage_door"
              has("smoke") -> "smoke"
              has("leak", "water", "moisture") -> "moisture"
              has("presence", "occupancy") -> "occupancy"
              else -> "motion"
            }
          put("device_class", deviceClass)
          if (deviceClass in setOf("door", "window", "garage_door", "motion")) "on" else "off"
        }
        "cover" -> {
          put("current_position", 70)
          when {
            has("garage") -> put("device_class", "garage")
            has("blind") -> put("device_class", "blind")
            has("shutter") -> put("device_class", "shutter")
            else -> put("device_class", "curtain")
          }
          "open"
        }
        "climate" -> {
          put("current_temperature", 20.5)
          put("temperature", 21.5)
          put("min_temp", 7)
          put("max_temp", 30)
          put("hvac_modes", listOf("off", "heat", "cool", "auto"))
          put("hvac_action", "heating")
          "heat"
        }
        "water_heater" -> {
          put("current_temperature", 52)
          put("temperature", 55)
          "eco"
        }
        "lock" -> "locked"
        "media_player" -> {
          put("media_title", "Blue in Green")
          put("media_artist", "Miles Davis")
          put("volume_level", 0.4)
          "playing"
        }
        "person",
        "device_tracker" -> "home"
        "weather" -> {
          put("temperature", 18)
          put("temperature_unit", "°C")
          put("humidity", 60)
          put("wind_speed", 11)
          put("wind_speed_unit", "km/h")
          "sunny"
        }
        "sun" -> {
          put("elevation", 34.5)
          put("azimuth", 180.0)
          put("rising", false)
          "above_horizon"
        }
        "alarm_control_panel" -> "armed_home"
        "vacuum" -> {
          put("battery_level", 100)
          "docked"
        }
        "lawn_mower" -> "docked"
        "valve" -> "open"
        "input_number",
        "number" -> {
          put("min", 0)
          put("max", 100)
          put("step", 1)
          "50"
        }
        "input_select",
        "select" -> {
          put("options", listOf("Eco", "Comfort", "Away"))
          "Comfort"
        }
        "input_text",
        "text" -> "Hello"
        "counter" -> "3"
        "timer" -> "idle"
        "camera" -> "idle"
        "update" -> "off"
        "calendar" -> "off"
        "todo" -> "2"
        "zone" -> "1"
        "script" -> "off"
        "scene",
        "button",
        "input_button",
        "event",
        "image" -> SAMPLE_TIME
        else -> "on"
      }
    return EntityState(entityId, state, JsonObject(attrs))
  }

  private const val SAMPLE_TIME = "2026-01-01T08:00:00+00:00"

  /** A sensor's reading and unit by the `device_class` its object id suggests. */
  private fun sensor(objectId: String, put: (String, Any) -> Unit): String {
    fun has(vararg words: String) = words.any { it in objectId }
    val (deviceClass, unit, value) =
      when {
        has("humid") -> Triple("humidity", "%", "48")
        has("battery") -> Triple("battery", "%", "87")
        has("energy", "kwh", "consumption") -> Triple("energy", "kWh", "4.2")
        has("power", "watt", "load") -> Triple("power", "W", "312")
        has("lux", "illuminance", "light_level") -> Triple("illuminance", "lx", "320")
        has("pressure") -> Triple("pressure", "hPa", "1013")
        has("co2", "carbon_dioxide") -> Triple("carbon_dioxide", "ppm", "640")
        has("pm25", "pm2_5") -> Triple("pm25", "µg/m³", "8")
        has("voltage") -> Triple("voltage", "V", "230")
        has("current", "amps") -> Triple("current", "A", "1.4")
        has("speed", "wind") -> Triple("wind_speed", "km/h", "11")
        has("rain", "precip") -> Triple("precipitation", "mm", "0.4")
        has("gas") -> Triple("gas", "m³", "1.2")
        has("cpu", "usage", "percent") -> Triple(null, "%", "23")
        else -> Triple("temperature", "°C", "21.5")
      }
    deviceClass?.let { put("device_class", it) }
    put("unit_of_measurement", unit)
    put("state_class", "measurement")
    return value
  }

  /** An entity id for the [index]th made-up [domain] slot: the first light is the kitchen's. */
  private fun sampleEntityId(domain: String, index: Int): String {
    val names = SAMPLE_NAMES[domain] ?: listOf("living_room_$domain")
    val name = names[index % names.size]
    return "$domain.$name"
  }

  private val SAMPLE_NAMES: Map<String, List<String>> =
    mapOf(
      "light" to listOf("kitchen_light", "hall_light", "desk_lamp", "porch_light"),
      "switch" to listOf("coffee_machine", "fan_heater", "garden_lights"),
      "sensor" to listOf("living_room_temperature", "bathroom_humidity", "house_power"),
      "binary_sensor" to listOf("front_door", "hall_motion", "kitchen_window"),
      "cover" to listOf("garage_door", "bedroom_blind"),
      "climate" to listOf("hallway_thermostat"),
      "fan" to listOf("bedroom_fan"),
      "lock" to listOf("front_door_lock"),
      "media_player" to listOf("living_room_speaker"),
      "person" to listOf("alex", "sam"),
      "weather" to listOf("home"),
      "vacuum" to listOf("robot_vacuum"),
      "alarm_control_panel" to listOf("home_alarm"),
    )
}
