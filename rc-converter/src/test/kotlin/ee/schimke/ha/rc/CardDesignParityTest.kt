package ee.schimke.ha.rc

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.haThemeFor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Visual parity for the design path: a tile rendered from its local-library template, with slot
 * bindings pushed, must draw the same pixels as the same tile captured directly through its
 * converter. This is what makes "server first, library fallback" safe as the one rendering path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardDesignParityTest {

  @get:Rule val compose = createComposeRule()

  private val registry = defaultRegistry()
  private val style = ThemeStyle.TerrazzoHome

  private fun tile(entity: String, name: String? = null) =
    CardConfig(
      type = "tile",
      raw =
        buildJsonObject {
          put("type", "tile")
          put("entity", entity)
          name?.let { put("name", it) }
        },
    )

  private fun entity(id: String, state: String, vararg attrs: Pair<String, String>) =
    EntityState(id, state, JsonObject(attrs.associate { (k, v) -> k to JsonPrimitive(v) }))

  private fun card(type: String, block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
    CardConfig(
      type = type,
      raw =
        buildJsonObject {
          put("type", type)
          block()
        },
    )

  private val listStates =
    listOf(
      entity("light.kitchen", "on", "friendly_name" to "Kitchen"),
      entity(
        "sensor.temp",
        "21.5",
        "friendly_name" to "Living room",
        "device_class" to "temperature",
        "unit_of_measurement" to "°C",
      ),
      entity("binary_sensor.front_door", "off", "device_class" to "door"),
      entity("switch.fan", "on", "friendly_name" to "Fan"),
    )

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

  private fun assertParity(name: String, card: CardConfig, state: EntityState, dark: Boolean) =
    assertParity(name, card, listOf(state), dark)

  private fun assertParity(
    name: String,
    card: CardConfig,
    states: List<EntityState>,
    dark: Boolean,
    bitmapLoader: androidx.compose.remote.player.core.platform.BitmapLoader =
      androidx.compose.remote.player.core.platform.BitmapLoader.UNSUPPORTED,
    history: Map<String, List<ee.schimke.ha.model.HistoryPoint>> = emptyMap(),
    statistics: Map<String, List<ee.schimke.ha.model.StatisticPoint>> = emptyMap(),
  ) {
    val snapshot =
      HaSnapshot(
        states = states.associateBy { it.entityId },
        history = history,
        statistics = statistics,
      )
    val context = org.robolectric.RuntimeEnvironment.getApplication()
    val key = assertNotNull(CardDesignTemplates.keyFor(card, snapshot, style, dark, registry))
    val playback =
      CardDesignTemplates.playback(card, CardDesignLibrary.template(context, key, registry))
    val theme = haThemeFor(style, dark)

    var useTemplate by mutableStateOf(false)
    compose.setContent {
      Box(Modifier.width(360.dp).testTag("card")) {
        if (!useTemplate) {
          CachedCardPreview(
            cacheKey = "direct-$name",
            profile = androidXExperimentalWrap,
            card = card,
            snapshot = snapshot,
            bitmapLoader = bitmapLoader,
          ) {
            ProvideCardRegistry(registry) {
              ProvideHaTheme(theme) { RenderChild(card, snapshot, RemoteModifier.fillMaxWidth()) }
            }
          }
        } else {
          CachedCardPreview(
            cacheKey = "template-$name",
            profile = androidXExperimentalWrap,
            card = card,
            snapshot = snapshot,
            bitmapLoader = bitmapLoader,
            template = playback,
          ) {}
        }
      }
    }
    compose.waitForIdle()
    val direct = capture("direct-$name")
    compose.runOnIdle { useTemplate = true }
    compose.waitForIdle()
    val templated = capture("template-$name")

    assertEquals(direct.width, templated.width, "$name width")
    assertEquals(direct.height, templated.height, "$name height")
    var differing = 0
    val colors = mutableSetOf<androidx.compose.ui.graphics.Color>()
    for (y in 0 until direct.height) for (x in 0 until direct.width) {
      colors += direct[x, y]
      if (direct[x, y] != templated[x, y]) differing++
    }
    assertTrue(colors.size > 2, "$name: the direct capture must draw real content")
    assertEquals(0, differing, "$name: pixels differing between direct and template render")
  }

  private fun capture(name: String) =
    compose
      .onNodeWithTag("card")
      .captureToImage()
      .also { image ->
        val dir = File("build/card-design-parity").also { it.mkdirs() }
        dir.resolve("$name.png").outputStream().use {
          image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
      }
      .toPixelMap()

  @Test
  fun lightOn() =
    assertParity(
      "light-on",
      tile("light.kitchen", "Kitchen"),
      entity("light.kitchen", "on"),
      dark = false,
    )

  @Test
  fun lightOffDark() =
    assertParity(
      "light-off-dark",
      tile("light.hall"),
      entity("light.hall", "off", "friendly_name" to "Hall"),
      dark = true,
    )

  @Test
  fun switchOn() =
    assertParity(
      "switch-on",
      tile("switch.fan"),
      entity("switch.fan", "on", "friendly_name" to "Fan"),
      dark = false,
    )

  @Test
  fun temperatureSensor() =
    assertParity(
      "sensor-temperature",
      tile("sensor.temp"),
      entity(
        "sensor.temp",
        "21.5",
        "friendly_name" to "Living room",
        "device_class" to "temperature",
        "unit_of_measurement" to "°C",
      ),
      dark = false,
    )

  @Test
  fun doorOpen() =
    assertParity(
      "binary-sensor-door",
      tile("binary_sensor.front_door"),
      entity(
        "binary_sensor.front_door",
        "on",
        "friendly_name" to "Front door",
        "device_class" to "door",
      ),
      dark = false,
    )

  @Test
  fun entityRowLight() =
    assertParity(
      "entity-light",
      card("entity") { put("entity", "light.kitchen") },
      entity("light.kitchen", "on", "friendly_name" to "Kitchen"),
      dark = false,
    )

  @Test
  fun entityRowSensorDark() =
    assertParity(
      "entity-sensor-dark",
      card("entity") {
        put("entity", "sensor.temp")
        put("name", "Outside")
      },
      listStates[1],
      dark = true,
    )

  @Test
  fun buttonToggle() =
    assertParity(
      "button-switch",
      card("button") { put("entity", "switch.fan") },
      entity("switch.fan", "on", "friendly_name" to "Fan"),
      dark = false,
    )

  @Test
  fun buttonIconNoName() =
    assertParity(
      "button-icon-no-name",
      card("button") {
        put("entity", "light.kitchen")
        put("icon", "mdi:lamp")
        put("show_name", false)
      },
      entity("light.kitchen", "off", "friendly_name" to "Kitchen"),
      dark = false,
    )

  @Test
  fun headingWithoutTextHasNoDesign() {
    val heading = card("heading") { put("heading_style", "subtitle") }
    kotlin.test.assertNull(registry.get("heading")?.designProperties(heading, HaSnapshot()))
  }

  @Test
  fun headingTitle() =
    assertParity(
      "heading-title",
      card("heading") { put("heading", "Living room") },
      emptyList(),
      dark = false,
    )

  @Test
  fun headingSubtitleDark() =
    assertParity(
      "heading-subtitle-dark",
      card("heading") {
        put("heading", "Lights and switches")
        put("heading_style", "subtitle")
      },
      emptyList(),
      dark = true,
    )

  @Test
  fun glance() = assertParity("glance", listCard("glance", "At a glance"), listStates, dark = false)

  @Test
  fun glanceNoTitleDark() =
    assertParity("glance-no-title-dark", listCard("glance", null), listStates, dark = true)

  @Test
  fun entities() =
    assertParity(
      "entities",
      listCard("entities", "Downstairs, around the living room"),
      listStates,
      dark = false,
    )

  @Test
  fun alarmPanelArmedAway() =
    assertParity(
      "alarm-armed-away",
      card("alarm-panel") { put("entity", "alarm_control_panel.home") },
      entity("alarm_control_panel.home", "armed_away", "friendly_name" to "Home alarm"),
      dark = false,
    )

  @Test
  fun alarmPanelNoKeypadDark() =
    assertParity(
      "alarm-no-keypad-dark",
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
      dark = true,
    )

  private fun gauge(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
    card("gauge") {
      put("entity", "sensor.cpu")
      block()
    }

  private val cpu =
    entity("sensor.cpu", "64", "friendly_name" to "CPU", "unit_of_measurement" to "%")

  @Test
  fun gaugeWarningBand() =
    assertParity(
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
      dark = false,
    )

  @Test
  fun gaugeDescendingBandsCustomRangeDark() =
    assertParity(
      "gauge-descending-dark",
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
      dark = true,
    )

  @Test
  fun gaugeNoSeverityNoUnit() =
    assertParity(
      "gauge-plain",
      gauge(),
      entity("sensor.cpu", "37", "friendly_name" to "Load"),
      dark = false,
    )

  @Test
  fun gaugeUnavailable() =
    assertParity(
      "gauge-unavailable",
      gauge { put("severity", buildJsonObject { put("red", 10) }) },
      entity("sensor.cpu", "unavailable", "friendly_name" to "CPU", "unit_of_measurement" to "%"),
      dark = false,
    )

  @Test
  fun clockWithTitleAndDate() =
    assertParity(
      "clock-title-date",
      card("clock") { put("title", "Kitchen clock") },
      emptyList(),
      dark = false,
    )

  @Test
  fun clockZoned12hLargeDark() =
    assertParity(
      "clock-zoned-dark",
      card("clock") {
        put("time_zone", "Asia/Tokyo")
        put("time_format", "12")
        put("clock_size", "large")
        put("display", "time")
      },
      emptyList(),
      dark = true,
    )

  @Test
  fun iframePlaceholder() =
    assertParity(
      "iframe",
      card("iframe") { put("url", "https://example.com") },
      emptyList(),
      dark = false,
    )

  @Test
  fun gaugeInputNumber() =
    assertParity(
      "gauge-input-number",
      card("gauge") {
        put("entity", "input_number.volume")
        put("max", 10)
      },
      entity("input_number.volume", "7", "friendly_name" to "Volume"),
      dark = false,
    )

  @Test
  fun areaWithStatsAndActions() =
    assertParity(
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
      dark = false,
    )

  @Test
  fun areaNameOnlyDark() =
    assertParity("area-name-dark", card("area") { put("name", "Garage") }, emptyList(), dark = true)

  /** Serves a test image for exactly one URL, as a host's loader would for the real picture. */
  private fun loaderFor(url: String) =
    androidx.compose.remote.player.core.platform.BitmapLoader { requested ->
      if (requested != url) throw java.io.IOException("unexpected image $requested")
      val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
      android.graphics.Canvas(bitmap).apply {
        drawColor(android.graphics.Color.rgb(30, 90, 160))
        drawCircle(
          32f,
          24f,
          14f,
          android.graphics.Paint().apply { color = android.graphics.Color.YELLOW },
        )
      }
      java.io
        .ByteArrayOutputStream()
        .also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        .toByteArray()
        .inputStream()
    }

  @Test
  fun pictureEntityCamera() {
    val url = "/api/camera_proxy/camera.driveway?token=abc"
    assertParity(
      "picture-entity-camera",
      card("picture-entity") { put("entity", "camera.driveway") },
      listOf(
        entity(
          "camera.driveway",
          "recording",
          "friendly_name" to "Driveway",
          "entity_picture" to url,
        )
      ),
      dark = false,
      bitmapLoader = loaderFor(url),
    )
  }

  @Test
  fun pictureEntityNoPictureNoStateDark() =
    assertParity(
      "picture-entity-icon-dark",
      card("picture-entity") {
        put("entity", "light.kitchen")
        put("show_state", false)
      },
      entity("light.kitchen", "on", "friendly_name" to "Kitchen"),
      dark = true,
    )

  private fun series(vararg values: Number) = values.mapIndexed { i, v ->
    ee.schimke.ha.model.HistoryPoint(
      kotlin.time.Instant.fromEpochMilliseconds(i * 600_000L),
      v.toString(),
    )
  }

  @Test
  fun sensorWithHistory() =
    assertParity(
      "sensor-history",
      card("sensor") {
        put("entity", "sensor.temp")
        put("hours_to_show", 12)
      },
      listOf(listStates[1]),
      dark = false,
      history = mapOf("sensor.temp" to series(19.5, 20, 20.5, 21.8, 21.2, 22, 21.5, 20.9, 21.5)),
    )

  @Test
  fun sensorNoHistoryDark() =
    assertParity(
      "sensor-empty-dark",
      card("sensor") { put("entity", "sensor.temp") },
      listOf(listStates[1]),
      dark = true,
    )

  @Test
  fun historyGraph() =
    assertParity(
      "history-graph",
      card("history-graph") {
        put("title", "Climate")
        putJsonArray("entities") {
          add("sensor.temp")
          add("sensor.humidity")
          add("binary_sensor.front_door")
        }
      },
      listOf(
        listStates[1],
        entity(
          "sensor.humidity",
          "48",
          "friendly_name" to "Humidity",
          "device_class" to "humidity",
        ),
        listStates[2],
      ),
      dark = false,
      history =
        mapOf(
          "sensor.temp" to series(19, 21, 20, 23, 22),
          "sensor.humidity" to series(40, 45, 52, 48),
        ),
    )

  private fun stats(vararg means: Double) = means.mapIndexed { i, v ->
    ee.schimke.ha.model.StatisticPoint(
      kotlin.time.Instant.fromEpochMilliseconds(i * 3_600_000L),
      mean = v,
      min = v - 1,
      max = v + 1,
    )
  }

  @Test
  fun statisticWithUnitAndPeriod() =
    assertParity(
      "statistic",
      card("statistic") {
        put("entity", "sensor.temp")
        put("period", "day")
        put("stat_type", "max")
      },
      listOf(listStates[1]),
      dark = false,
      statistics = mapOf("sensor.temp" to stats(19.0, 22.5, 21.0)),
    )

  @Test
  fun statisticsGraphLine() =
    assertParity(
      "statistics-graph",
      card("statistics-graph") {
        put("title", "Temperature")
        putJsonArray("entities") { add("sensor.temp") }
      },
      listOf(listStates[1]),
      dark = true,
      statistics = mapOf("sensor.temp" to stats(18.0, 19.5, 21.0, 20.0, 22.0, 21.5)),
    )

  @Test
  fun thermostatCooling() =
    assertParity(
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
      dark = false,
    )

  @Test
  fun thermostatOffNoTargetDark() =
    assertParity(
      "thermostat-off-dark",
      card("thermostat") { put("entity", "climate.office") },
      entity("climate.office", "off", "friendly_name" to "Office", "current_temperature" to "18"),
      dark = true,
    )

  @Test
  fun humidifier() =
    assertParity(
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
      dark = false,
    )

  private fun stack(type: String, vararg children: CardConfig) =
    card(type) { put("cards", kotlinx.serialization.json.JsonArray(children.map { it.raw })) }

  @Test
  fun verticalStack() =
    assertParity(
      "vertical-stack",
      stack(
        "vertical-stack",
        card("heading") { put("heading", "Kitchen") },
        tile("light.kitchen"),
        listCard("glance", "Around"),
      ),
      listStates,
      dark = false,
    )

  @Test
  fun horizontalStackDark() =
    assertParity(
      "horizontal-stack-dark",
      stack(
        "horizontal-stack",
        card("button") { put("entity", "switch.fan") },
        card("button") {
          put("entity", "light.kitchen")
          put("show_name", false)
        },
      ),
      listStates,
      dark = true,
    )

  @Test
  fun gridOfMixedCards() =
    assertParity(
      "grid",
      stack(
        "grid",
        tile("sensor.temp"),
        card("entity") { put("entity", "binary_sensor.front_door") },
        card("gauge") { put("entity", "sensor.temp") },
      ),
      listStates,
      dark = false,
    )

  @Test
  fun nestedStacks() =
    assertParity(
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
      dark = false,
    )
}
