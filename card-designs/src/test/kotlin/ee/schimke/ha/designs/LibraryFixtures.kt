package ee.schimke.ha.designs

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.HistoryPoint
import ee.schimke.ha.model.StatisticPoint
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** A curated card for the design library: its generation properties give one design shape. */
class LibraryFixture(val name: String, val card: CardConfig, val snapshot: HaSnapshot)

/**
 * One fixture per design shape worth publishing, ported from rc-converter's CardDesignParityTest.
 *
 * Names are the parity cases' names without their `-dark` suffix: a fixture is themeless (the
 * generator renders each one for every theme, light and dark). The parity test's bitmap loader for
 * `picture-entity-camera` is not ported — the generator has no image loader — but its card and
 * states are.
 */
object LibraryFixtures {
  val all: List<LibraryFixture> =
    listOf(
      fixture("light-on", tile("light.kitchen", "Kitchen"), entity("light.kitchen", "on")),
      fixture(
        "light-off",
        tile("light.hall"),
        entity("light.hall", "off", "friendly_name" to "Hall"),
      ),
      fixture(
        "switch-on",
        tile("switch.fan"),
        entity("switch.fan", "on", "friendly_name" to "Fan"),
      ),
      fixture("sensor-temperature", tile("sensor.temp"), temp),
      fixture(
        "binary-sensor-door",
        tile("binary_sensor.front_door"),
        entity(
          "binary_sensor.front_door",
          "on",
          "friendly_name" to "Front door",
          "device_class" to "door",
        ),
      ),
      fixture(
        "entity-light",
        card("entity") { put("entity", "light.kitchen") },
        entity("light.kitchen", "on", "friendly_name" to "Kitchen"),
      ),
      fixture(
        "entity-sensor",
        card("entity") {
          put("entity", "sensor.temp")
          put("name", "Outside")
        },
        temp,
      ),
      fixture(
        "button-switch",
        card("button") { put("entity", "switch.fan") },
        entity("switch.fan", "on", "friendly_name" to "Fan"),
      ),
      fixture(
        "button-icon-no-name",
        card("button") {
          put("entity", "light.kitchen")
          put("icon", "mdi:lamp")
          put("show_name", false)
        },
        entity("light.kitchen", "off", "friendly_name" to "Kitchen"),
      ),
      fixture("heading-title", card("heading") { put("heading", "Living room") }, emptyList()),
      fixture(
        "heading-subtitle",
        card("heading") {
          put("heading", "Lights and switches")
          put("heading_style", "subtitle")
        },
        emptyList(),
      ),
      fixture("glance", listCard("glance", "At a glance"), listStates),
      fixture("glance-no-title", listCard("glance", null), listStates),
      fixture("entities", listCard("entities", "Downstairs, around the living room"), listStates),
      fixture(
        "alarm-armed-away",
        card("alarm-panel") { put("entity", "alarm_control_panel.home") },
        entity("alarm_control_panel.home", "armed_away", "friendly_name" to "Home alarm"),
      ),
      fixture(
        "alarm-no-keypad",
        card("alarm-panel") {
          put("entity", "alarm_control_panel.home")
          put("name", "House")
          put("show_keypad", false)
          putJsonArray("states") {
            add("arm_home")
            add("arm_night")
            add("arm_away")
          }
        },
        entity("alarm_control_panel.home", "triggered"),
      ),
      fixture(
        "gauge-warning",
        gauge {
          put(
            "severity",
            buildJsonObject {
              put("green", 0)
              put("yellow", 50)
              put("red", 80)
            },
          )
        },
        cpu,
      ),
      fixture(
        "gauge-descending",
        gauge {
          put("min", 20)
          put("max", 90)
          put("name", "Processor")
          put(
            "severity",
            buildJsonObject {
              put("green", 60)
              put("yellow", 30)
              put("red", 0)
            },
          )
        },
        cpu,
      ),
      fixture("gauge-plain", gauge(), entity("sensor.cpu", "37", "friendly_name" to "Load")),
      fixture(
        "gauge-unavailable",
        gauge { put("severity", buildJsonObject { put("red", 10) }) },
        entity("sensor.cpu", "unavailable", "friendly_name" to "CPU", "unit_of_measurement" to "%"),
      ),
      fixture("clock-title-date", card("clock") { put("title", "Kitchen clock") }, emptyList()),
      fixture(
        "clock-zoned",
        card("clock") {
          put("time_zone", "Asia/Tokyo")
          put("time_format", "12")
          put("clock_size", "large")
          put("display", "time")
        },
        emptyList(),
      ),
      fixture("iframe", card("iframe") { put("url", "https://example.com") }, emptyList()),
      fixture(
        "gauge-input-number",
        card("gauge") {
          put("entity", "input_number.volume")
          put("max", 10)
        },
        entity("input_number.volume", "7", "friendly_name" to "Volume"),
      ),
      fixture(
        "area",
        card("area") {
          put("area", "living_room")
          putJsonArray("entities") {
            add("sensor.temp")
            add("light.kitchen")
            add("binary_sensor.front_door")
            add("switch.fan")
          }
        },
        listStates,
      ),
      fixture("area-name", card("area") { put("name", "Garage") }, emptyList()),
      fixture(
        "picture-entity-camera",
        card("picture-entity") { put("entity", "camera.driveway") },
        entity(
          "camera.driveway",
          "recording",
          "friendly_name" to "Driveway",
          "entity_picture" to "/api/camera_proxy/camera.driveway?token=abc",
        ),
      ),
      fixture(
        "picture-entity-icon",
        card("picture-entity") {
          put("entity", "light.kitchen")
          put("show_state", false)
        },
        entity("light.kitchen", "on", "friendly_name" to "Kitchen"),
      ),
      fixture(
        "sensor-history",
        card("sensor") {
          put("entity", "sensor.temp")
          put("hours_to_show", 12)
        },
        listOf(temp),
        history = mapOf("sensor.temp" to series(19.5, 20, 20.5, 21.8, 21.2, 22, 21.5, 20.9, 21.5)),
      ),
      fixture("sensor-empty", card("sensor") { put("entity", "sensor.temp") }, temp),
      fixture(
        "history-graph",
        card("history-graph") {
          put("title", "Climate")
          putJsonArray("entities") {
            add("sensor.temp")
            add("sensor.humidity")
            add("binary_sensor.front_door")
          }
        },
        listOf(temp, humidity, door),
        history =
          mapOf(
            "sensor.temp" to series(19, 21, 20, 23, 22),
            "sensor.humidity" to series(40, 45, 52, 48),
          ),
      ),
      fixture(
        "statistic",
        card("statistic") {
          put("entity", "sensor.temp")
          put("period", "day")
          put("stat_type", "max")
        },
        listOf(temp),
        statistics = mapOf("sensor.temp" to stats(19.0, 22.5, 21.0)),
      ),
      fixture(
        "statistics-graph",
        card("statistics-graph") {
          put("title", "Temperature")
          putJsonArray("entities") { add("sensor.temp") }
        },
        listOf(temp),
        statistics = mapOf("sensor.temp" to stats(18.0, 19.5, 21.0, 20.0, 22.0, 21.5)),
      ),
      fixture(
        "thermostat-cool",
        card("thermostat") { put("entity", "climate.office") },
        entity(
          "climate.office",
          "cool",
          "friendly_name" to "Office",
          "current_temperature" to "24.5",
          "temperature" to "22",
          "hvac_action" to "cooling",
        ),
      ),
      fixture(
        "thermostat-off",
        card("thermostat") { put("entity", "climate.office") },
        entity("climate.office", "off", "friendly_name" to "Office", "current_temperature" to "18"),
      ),
      fixture(
        "humidifier",
        card("humidifier") { put("entity", "humidifier.bedroom") },
        entity(
          "humidifier.bedroom",
          "on",
          "friendly_name" to "Bedroom",
          "current_humidity" to "38",
          "humidity" to "45",
          "action" to "humidifying",
        ),
      ),
      fixture(
        "vertical-stack",
        stack(
          "vertical-stack",
          card("heading") { put("heading", "Kitchen") },
          tile("light.kitchen"),
          listCard("glance", "Around"),
        ),
        listStates,
      ),
      fixture(
        "horizontal-stack",
        stack(
          "horizontal-stack",
          card("button") { put("entity", "switch.fan") },
          card("button") {
            put("entity", "light.kitchen")
            put("show_name", false)
          },
        ),
        listStates,
      ),
      fixture(
        "grid",
        stack(
          "grid",
          tile("sensor.temp"),
          card("entity") { put("entity", "binary_sensor.front_door") },
          card("gauge") { put("entity", "sensor.temp") },
        ),
        listStates,
      ),
      fixture(
        "nested-stack",
        stack(
          "vertical-stack",
          card("heading") { put("heading", "Upstairs") },
          stack("horizontal-stack", tile("light.kitchen"), tile("switch.fan")),
          card("heading") {
            put("heading", "Downstairs")
            put("heading_style", "subtitle")
          },
        ),
        listStates,
      ),
      fixture(
        "conditional",
        card("conditional") {
          putJsonArray("conditions") {
            addJsonObject {
              put("entity", "light.kitchen")
              put("state", "on")
            }
          }
          put("card", tile("switch.fan").raw)
        },
        listStates,
      ),
      fixture(
        "entity-filter",
        card("entity-filter") {
          putJsonArray("entities") {
            add("light.kitchen")
            add("switch.fan")
            add("sensor.temp")
          }
          putJsonArray("state_filter") { add("on") }
          put("card", buildJsonObject { put("title", "On now") })
        },
        listStates,
      ),
      fixture(
        "picture",
        card("picture") {
          put("image", "/local/garden.jpg")
          put("name", "Garden")
        },
        emptyList(),
      ),
      fixture(
        "picture-glance",
        card("picture-glance") {
          put("title", "Living room")
          put("image", "/local/living.jpg")
          putJsonArray("entities") {
            add("light.kitchen")
            add("switch.fan")
            add("binary_sensor.front_door")
          }
        },
        listStates,
      ),
      fixture(
        "markdown",
        card("markdown") {
          put("title", "House notes")
          put(
            "content",
            "## Climate\n\nIt is {{ states('sensor.temp') }} inside.\n\n- Kitchen light: {{ states('light.kitchen') }}\n- Fan: {{ states('switch.fan') }}\n\n---\n\nHave a nice day.",
          )
        },
        listStates,
      ),
      fixture(
        "markdown-jinja",
        card("markdown") {
          put(
            "content",
            "{% if is_state('light.kitchen', 'on') %}# Lights on{% else %}# Lights off{% endif %}\n\nTemperature {{ states('sensor.temp') | float | round(0) }}",
          )
        },
        listStates,
      ),
      fixture(
        "logbook",
        card("logbook") {
          put("title", "Activity")
          putJsonArray("entities") {
            add("light.kitchen")
            add("binary_sensor.front_door")
          }
        },
        listStates,
      ),
      fixture(
        "calendar",
        card("calendar") {
          putJsonArray("entities") {
            add("calendar.family")
            add("calendar.work")
          }
        },
        listOf(
          EntityState(
            "calendar.family",
            "on",
            buildJsonObject {
              put("friendly_name", "Family")
              put(
                "events",
                events("Dentist" to "2026-10-12T09:30:00", "Football" to "2026-10-13"),
              )
            },
          ),
          EntityState(
            "calendar.work",
            "off",
            buildJsonObject { put("events", events("Standup" to "2026-10-12T10:00:00")) },
          ),
        ),
      ),
      fixture(
        "todo",
        card("todo-list") { put("entity", "todo.shopping") },
        EntityState(
          "todo.shopping",
          "2",
          buildJsonObject {
            put("friendly_name", "Shopping")
            put(
              "items",
              JsonArray(
                listOf(
                  todoItem("Milk", "needs_action"),
                  todoItem("Bread", "needs_action"),
                  todoItem("Eggs", "completed"),
                )
              ),
            )
          },
        ),
      ),
      fixture(
        "weather",
        card("weather-forecast") { put("entity", "weather.home") },
        EntityState(
          "weather.home",
          "partlycloudy",
          buildJsonObject {
            put("friendly_name", "Home")
            put("temperature", "17.5")
            put("temperature_unit", "°C")
            put("humidity", "62")
            put("wind_speed", "11")
            put("wind_speed_unit", "km/h")
            put(
              "forecast",
              JsonArray(
                listOf("sunny" to "2026-10-12", "rainy" to "2026-10-13", "cloudy" to "2026-10-14")
                  .mapIndexed { i, (cond, dt) ->
                    buildJsonObject {
                      put("condition", cond)
                      put("temperature", "${18 + i}")
                      put("templow", "${9 + i}")
                      put("datetime", "${dt}T00:00:00")
                    }
                  }
              ),
            )
          },
        ),
      ),
      fixture(
        "light-dial-on",
        card("light") { put("entity", "light.kitchen") },
        entity("light.kitchen", "on", "friendly_name" to "Kitchen", "brightness" to "200"),
      ),
      fixture(
        "light-dial-off",
        card("light") { put("entity", "light.kitchen") },
        entity("light.kitchen", "off", "friendly_name" to "Kitchen"),
      ),
      fixture(
        "media-playing",
        card("media-control") { put("entity", "media_player.lounge") },
        entity(
          "media_player.lounge",
          "playing",
          "friendly_name" to "Lounge",
          "media_title" to "Blue in Green",
          "media_artist" to "Miles Davis",
          "media_position" to "95",
          "media_duration" to "337",
        ),
      ),
      fixture(
        "media-paused",
        card("media-control") { put("entity", "media_player.lounge") },
        entity(
          "media_player.lounge",
          "paused",
          "friendly_name" to "Lounge",
          "media_title" to "Podcast",
        ),
      ),
      fixture(
        "map",
        card("map") {
          put("title", "Family")
          putJsonArray("entities") {
            add("person.alex")
            add("device_tracker.car")
          }
        },
        listOf(
          entity(
            "person.alex",
            "home",
            "friendly_name" to "Alex",
            "latitude" to "59.43",
            "longitude" to "24.75",
          ),
          entity("device_tracker.car", "away", "friendly_name" to "Car"),
        ),
      ),
      fixture(
        "picture-elements",
        card("picture-elements") {
          put("image", "/local/floorplan.png")
          putJsonArray("elements") {
            addJsonObject {
              put("type", "state-icon")
              put("entity", "light.kitchen")
              put("style", position("20%", "30%"))
            }
            addJsonObject {
              put("type", "state-label")
              put("entity", "sensor.temp")
              put("style", position("55%", "40%"))
            }
            addJsonObject {
              put("type", "service-button")
              put("title", "All off")
              put("service", "light.turn_off")
            }
          }
        },
        listStates,
      ),
      fixture(
        "statistics-bars",
        card("statistics-graph") {
          put("title", "Energy")
          put("chart_type", "bar")
          putJsonArray("entities") { add("sensor.temp") }
        },
        listOf(temp),
        statistics = mapOf("sensor.temp" to stats(3.0, 5.5, 2.0, 7.25, 4.0)),
      ),
      fixture(
        "statistics-stacked",
        card("statistics-graph") {
          put("chart_type", "bar")
          put("stack", true)
          putJsonArray("entities") {
            add("sensor.temp")
            add("sensor.humidity")
          }
        },
        listOf(temp, humidity),
        statistics =
          mapOf(
            "sensor.temp" to stats(3.0, 5.5, 2.0, 7.0),
            "sensor.humidity" to stats(1.0, -2.0, 4.0),
          ),
      ),
      fixture(
        "bambu-ams",
        card("custom:ha-bambulab-ams-card") { put("entity_prefix", "x1c") },
        printer,
      ),
      fixture(
        "bambu-spool",
        card("custom:ha-bambulab-spool-card") {
          put("entity_prefix", "x1c")
          put("slot", 2)
        },
        printer,
      ),
      fixture(
        "bambu-print-status",
        card("custom:ha-bambulab-print_status-card") { put("entity_prefix", "x1c") },
        printer,
      ),
      fixture(
        "bambu-print-control",
        card("custom:ha-bambulab-print_control-card") { put("entity_prefix", "x1c") },
        printer,
      ),
      fixture(
        "custom-card",
        card("custom:mushroom-light-card") { put("entity", "light.kitchen") },
        listStates,
      ),
    )
}

private fun fixture(name: String, card: CardConfig, state: EntityState) =
  fixture(name, card, listOf(state))

private fun fixture(
  name: String,
  card: CardConfig,
  states: List<EntityState>,
  history: Map<String, List<HistoryPoint>> = emptyMap(),
  statistics: Map<String, List<StatisticPoint>> = emptyMap(),
) =
  LibraryFixture(
    name,
    card,
    HaSnapshot(
      states = states.associateBy { it.entityId },
      history = history,
      statistics = statistics,
    ),
  )

private fun card(type: String, block: JsonObjectBuilder.() -> Unit) =
  CardConfig(
    type = type,
    raw =
      buildJsonObject {
        put("type", type)
        block()
      },
  )

private fun tile(entity: String, name: String? = null) =
  card("tile") {
    put("entity", entity)
    name?.let { put("name", it) }
  }

private fun gauge(block: JsonObjectBuilder.() -> Unit = {}) =
  card("gauge") {
    put("entity", "sensor.cpu")
    block()
  }

private fun stack(type: String, vararg children: CardConfig) =
  card(type) { put("cards", JsonArray(children.map { it.raw })) }

private fun listCard(type: String, title: String?) =
  card(type) {
    title?.let { put("title", it) }
    putJsonArray("entities") {
      add("light.kitchen")
      addJsonObject {
        put("entity", "sensor.temp")
        put("name", "Temperature")
      }
      addJsonObject {
        put("entity", "binary_sensor.front_door")
        put("name", "Front door")
        put("tap_action", buildJsonObject { put("action", "more-info") })
      }
      add("switch.fan")
    }
  }

private fun entity(id: String, state: String, vararg attrs: Pair<String, String>) =
  EntityState(id, state, JsonObject(attrs.associate { (k, v) -> k to JsonPrimitive(v) }))

private fun series(vararg values: Number) = values.mapIndexed { i, v ->
  HistoryPoint(Instant.fromEpochMilliseconds(i * 600_000L), v.toString())
}

private fun stats(vararg means: Double) = means.mapIndexed { i, v ->
  StatisticPoint(
    Instant.fromEpochMilliseconds(i * 3_600_000L),
    mean = v,
    min = v - 1,
    max = v + 1,
  )
}

private fun events(vararg e: Pair<String, String>) =
  JsonArray(
    e.map { (summary, start) ->
      buildJsonObject {
        put("summary", summary)
        put("start", start)
      }
    }
  )

private fun todoItem(summary: String, status: String) = buildJsonObject {
  put("summary", summary)
  put("status", status)
}

private fun position(left: String, top: String) = buildJsonObject {
  put("left", left)
  put("top", top)
}

private val temp =
  entity(
    "sensor.temp",
    "21.5",
    "friendly_name" to "Living room",
    "device_class" to "temperature",
    "unit_of_measurement" to "°C",
  )

private val door = entity("binary_sensor.front_door", "off", "device_class" to "door")

private val humidity =
  entity("sensor.humidity", "48", "friendly_name" to "Humidity", "device_class" to "humidity")

private val listStates =
  listOf(
    entity("light.kitchen", "on", "friendly_name" to "Kitchen"),
    temp,
    door,
    entity("switch.fan", "on", "friendly_name" to "Fan"),
  )

private val cpu = entity("sensor.cpu", "64", "friendly_name" to "CPU", "unit_of_measurement" to "%")

private val printer =
  listOf(
    entity(
      "sensor.x1c_ams_1_tray_1",
      "Bambu PLA Basic",
      "color" to "#F2C200FF",
      "remain" to "80",
      "active" to "true",
    ),
    entity(
      "sensor.x1c_ams_1_tray_2",
      "Generic PETG",
      "color" to "#1E5AA8FF",
      "remain" to "35",
      "active" to "false",
    ),
    entity("sensor.x1c_ams_1_tray_3", "unknown", "remain_enabled" to "false", "active" to "false"),
    entity("sensor.x1c_print_progress", "42", "friendly_name" to "X1C Print progress"),
    entity("sensor.x1c_current_stage", "printing"),
    entity("sensor.x1c_current_layer", "120"),
    entity("sensor.x1c_total_layer_count", "300"),
    entity("sensor.x1c_remaining_time", "95"),
    entity("sensor.x1c_nozzle_temperature", "219.5", "unit_of_measurement" to "°C"),
    entity("sensor.x1c_target_nozzle_temperature", "220"),
    entity("button.x1c_pause_printing", "unknown"),
    entity("button.x1c_stop_printing", "unknown"),
  )
