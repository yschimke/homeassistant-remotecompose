package ee.schimke.ha.rc

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.HistoryPoint
import ee.schimke.ha.model.StatisticPoint
import java.io.File
import kotlin.test.Test
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every built-in card type, as the launcher widget draws it (the real path: a template captured at
 * the widget's size, baked with its values, or a direct capture for a card with no design) on each
 * launcher grid size from 1×1 to 4×3, on Android's `(73n − 16) × (118m − 16)` cells. The renders
 * land in `build/launcher-sweep/` for review, with `report.tsv` listing every finding.
 *
 * Each render is checked against the launcher rules in docs/architecture/adaptive-card-layouts.md:
 * content must not run off the cell's right or bottom edge (clipped), and a cell of two rows or
 * more must not hold its content against the top with the rest blank (pinned — "No dead space").
 * The test fails on any size that gives no widget and on any finding.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherGridSweepTest {
  @get:Rule val compose = createComposeRule()

  private val registry = CardDesignTemplates.defaultTemplateRegistry
  private val context = RuntimeEnvironment.getApplication()

  private fun entity(id: String, state: String, vararg attributes: Pair<String, Any>) =
    id to
      EntityState(
        id,
        state,
        JsonObject(
          attributes.associate { (k, v) ->
            k to
              when (v) {
                is JsonElement -> v
                is Number -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
              }
          }
        ),
      )

  private fun obj(vararg fields: Pair<String, Any>) =
    JsonObject(
      fields.associate { (k, v) ->
        k to (if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()))
      }
    )

  private val base = Instant.parse("2026-05-05T00:00:00Z")

  private fun hours(count: Int, value: (Int) -> Double) =
    (0 until count).map {
      HistoryPoint(base.plus(kotlin.time.Duration.parse("${it}h")), "${value(it)}")
    }

  private val snapshot =
    HaSnapshot(
      states =
        mapOf(
          entity("light.kitchen", "on", "friendly_name" to "Kitchen", "brightness" to 200),
          entity("light.office", "off", "friendly_name" to "Office"),
          entity(
            "climate.living_room",
            "heat",
            "friendly_name" to "Living Room",
            "current_temperature" to 21.4,
            "temperature" to 22.0,
            "min_temp" to 7.0,
            "max_temp" to 35.0,
            "hvac_action" to "heating",
          ),
          entity(
            "humidifier.bedroom",
            "on",
            "friendly_name" to "Bedroom",
            "current_humidity" to 45,
            "humidity" to 55,
            "min_humidity" to 20,
            "max_humidity" to 80,
            "action" to "humidifying",
          ),
          entity("alarm_control_panel.house", "disarmed", "friendly_name" to "House"),
          entity(
            "sensor.living_temperature",
            "21.5",
            "friendly_name" to "Living room",
            "unit_of_measurement" to "°C",
            "device_class" to "temperature",
          ),
          entity(
            "sensor.humidity",
            "48",
            "friendly_name" to "Humidity",
            "unit_of_measurement" to "%",
            "device_class" to "humidity",
          ),
          entity("switch.fan", "off", "friendly_name" to "Fan"),
          entity("lock.front_door", "locked", "friendly_name" to "Front door"),
          entity(
            "cover.blinds",
            "open",
            "friendly_name" to "Blinds",
            "device_class" to "blind",
          ),
          entity(
            "sensor.power",
            "640",
            "friendly_name" to "Power",
            "unit_of_measurement" to "W",
            "device_class" to "power",
          ),
          entity(
            "sensor.download",
            "776.0",
            "friendly_name" to "Download",
            "unit_of_measurement" to "Mbit/s",
          ),
          entity(
            "sensor.garage",
            "79.0",
            "friendly_name" to "Garage",
            "unit_of_measurement" to "Mbit/s",
          ),
          entity(
            "binary_sensor.front_door",
            "off",
            "friendly_name" to "Front door",
            "device_class" to "door",
          ),
          entity(
            "binary_sensor.garage_motion",
            "on",
            "friendly_name" to "Garage motion",
            "device_class" to "motion",
          ),
          entity(
            "weather.home",
            "partlycloudy",
            "friendly_name" to "Home",
            "temperature" to 11.2,
            "temperature_unit" to "°C",
            "humidity" to 72,
            "wind_speed" to 14,
            "wind_speed_unit" to "km/h",
            "forecast" to
              JsonArray(
                listOf(
                  obj(
                    "datetime" to "2026-05-07",
                    "condition" to "partlycloudy",
                    "temperature" to 13.4,
                    "templow" to 7.5,
                  ),
                  obj(
                    "datetime" to "2026-05-08",
                    "condition" to "rainy",
                    "temperature" to 11.9,
                    "templow" to 6.8,
                  ),
                  obj(
                    "datetime" to "2026-05-09",
                    "condition" to "cloudy",
                    "temperature" to 12.2,
                    "templow" to 8.1,
                  ),
                  obj(
                    "datetime" to "2026-05-10",
                    "condition" to "sunny",
                    "temperature" to 16.7,
                    "templow" to 9.0,
                  ),
                  obj(
                    "datetime" to "2026-05-11",
                    "condition" to "rainy",
                    "temperature" to 14.0,
                    "templow" to 7.3,
                  ),
                )
              ),
          ),
          entity(
            "media_player.speaker",
            "playing",
            "friendly_name" to "Office speaker",
            "media_title" to "The Anthem",
            "media_artist" to "Good Charlotte",
            "media_position" to 75.0,
            "media_duration" to 176.0,
          ),
          entity(
            "todo.shopping",
            "2",
            "friendly_name" to "Shopping list",
            "items" to
              JsonArray(
                listOf(
                  obj("summary" to "Salt", "status" to "needs_action"),
                  obj("summary" to "Butter", "status" to "needs_action"),
                  obj("summary" to "Milk", "status" to "completed"),
                )
              ),
          ),
          entity(
            "calendar.family",
            "off",
            "friendly_name" to "Family calendar",
            "events" to
              JsonArray(
                listOf(
                  obj("summary" to "Dentist appointment", "start" to "2026-05-08T09:30:00+01:00"),
                  obj("summary" to "Trash day", "start" to "2026-05-09"),
                  obj("summary" to "Anniversary", "start" to "2026-05-12"),
                )
              ),
          ),
          entity(
            "camera.driveway",
            "streaming",
            "friendly_name" to "Driveway",
            "entity_picture" to "https://example.invalid/driveway.jpg",
          ),
          entity(
            "person.alex",
            "home",
            "friendly_name" to "Alex",
            "latitude" to 51.5,
            "longitude" to -0.12,
          ),
        ),
      history =
        mapOf(
          "sensor.power" to
            hours(24) { (400 + 300 * kotlin.math.sin(it / 3.0)).toInt().toDouble() },
          "sensor.living_temperature" to hours(24) { 20.0 + 2 * kotlin.math.sin(it / 4.0) },
          "binary_sensor.front_door" to
            listOf(
              HistoryPoint(base.plus(kotlin.time.Duration.parse("2h")), "on"),
              HistoryPoint(base.plus(kotlin.time.Duration.parse("3h")), "off"),
            ),
          "binary_sensor.garage_motion" to
            listOf(HistoryPoint(base.plus(kotlin.time.Duration.parse("5h")), "on")),
        ),
      statistics =
        mapOf(
          "sensor.power" to
            (0 until 24).map {
              StatisticPoint(
                start = base.plus(kotlin.time.Duration.parse("${it}h")),
                mean = 500 + 400 * kotlin.math.sin(it / 3.0),
              )
            }
        ),
    )

  private fun card(json: String): CardConfig {
    val raw = Json.parseToJsonElement(json) as JsonObject
    return CardConfig(type = (raw["type"] as JsonPrimitive).content, raw = raw)
  }

  private val cards =
    linkedMapOf(
      "tile" to card("""{"type":"tile","entity":"light.kitchen"}"""),
      "button" to card("""{"type":"button","entity":"light.kitchen"}"""),
      "entity" to card("""{"type":"entity","entity":"sensor.living_temperature"}"""),
      "sensor" to card("""{"type":"sensor","entity":"sensor.living_temperature"}"""),
      "sensor-history" to card("""{"type":"sensor","entity":"sensor.power"}"""),
      "gauge" to card("""{"type":"gauge","entity":"sensor.living_temperature","min":0,"max":40}"""),
      "entities" to
        card(
          """{"type":"entities","title":"Home","entities":["light.kitchen","sensor.living_temperature","switch.fan"]}"""
        ),
      "glance" to
        card(
          """{"type":"glance","title":"Glance","entities":["light.kitchen","sensor.living_temperature","switch.fan"]}"""
        ),
      "heading" to card("""{"type":"heading","heading":"Kitchen","heading_style":"title"}"""),
      "statistic" to
        card(
          """{"type":"statistic","entity":"sensor.living_temperature","period":"day","stat_type":"mean"}"""
        ),
      "light" to card("""{"type":"light","entity":"light.kitchen"}"""),
      "thermostat" to card("""{"type":"thermostat","entity":"climate.living_room"}"""),
      "humidifier" to card("""{"type":"humidifier","entity":"humidifier.bedroom"}"""),
      "alarm-panel" to card("""{"type":"alarm-panel","entity":"alarm_control_panel.house"}"""),
      "media-control" to card("""{"type":"media-control","entity":"media_player.speaker"}"""),
      "weather-forecast" to
        card(
          """{"type":"weather-forecast","entity":"weather.home","show_current":true,"show_forecast":true}"""
        ),
      "markdown" to
        card(
          """{"type":"markdown","title":"Notes","content":"Welcome home.\nTemperature is normal."}"""
        ),
      // Mixed emphasis takes the flow-row path, which a launcher can't play (no FlowLayout).
      "markdown-inline" to
        card(
          """{"type":"markdown","title":"Networks","content":"**Download:** {{ states('sensor.download') }} Mbit/s · **Garage:** {{ states('sensor.garage') }} Mbit/s"}"""
        ),
      "history-graph" to
        card(
          """{"type":"history-graph","title":"Power","hours_to_show":24,"entities":["sensor.power","sensor.living_temperature"]}"""
        ),
      "statistics-graph" to
        card(
          """{"type":"statistics-graph","title":"Power","period":"hour","stat_types":["mean"],"entities":["sensor.power"]}"""
        ),
      "logbook" to
        card(
          """{"type":"logbook","title":"Recent activity","hours_to_show":24,"entities":["binary_sensor.front_door","binary_sensor.garage_motion"]}"""
        ),
      "todo-list" to
        card("""{"type":"todo-list","entity":"todo.shopping","title":"Shopping list"}"""),
      "calendar" to
        card(
          """{"type":"calendar","title":"Family calendar","initial_view":"listWeek","entities":["calendar.family"]}"""
        ),
      "clock" to card("""{"type":"clock","clock_size":"medium","time_format":"24"}"""),
      "area" to
        card(
          """{"type":"area","name":"Living room","entities":["sensor.living_temperature","sensor.humidity","light.kitchen","switch.fan","cover.blinds"]}"""
        ),
      "picture" to
        card("""{"type":"picture","image":"/local/floorplan.png","name":"Floor plan"}"""),
      "picture-entity" to
        card("""{"type":"picture-entity","entity":"camera.driveway","name":"Driveway"}"""),
      "picture-glance" to
        card(
          """{"type":"picture-glance","title":"Living room","image":"/local/lr.png","entities":["light.kitchen","light.office","cover.blinds","switch.fan"]}"""
        ),
      "picture-elements" to
        card(
          """{"type":"picture-elements","image":"/local/floorplan.png","elements":[{"type":"state-icon","entity":"light.kitchen","style":{"left":"22%","top":"30%"}},{"type":"state-label","entity":"light.kitchen","style":{"left":"30%","top":"55%"}}]}"""
        ),
      "map" to card("""{"type":"map","entities":["person.alex"]}"""),
      "vertical-stack" to
        card(
          """{"type":"vertical-stack","cards":[{"type":"tile","entity":"light.kitchen"},{"type":"tile","entity":"switch.fan"}]}"""
        ),
      "horizontal-stack" to
        card(
          """{"type":"horizontal-stack","cards":[{"type":"button","entity":"light.kitchen"},{"type":"button","entity":"switch.fan"}]}"""
        ),
      "grid" to
        card(
          """{"type":"grid","columns":2,"cards":[{"type":"button","entity":"light.kitchen"},{"type":"button","entity":"light.office"},{"type":"button","entity":"switch.fan"},{"type":"button","entity":"lock.front_door"}]}"""
        ),
      "conditional" to
        card(
          """{"type":"conditional","conditions":[{"entity":"light.kitchen","state":"on"}],"card":{"type":"tile","entity":"light.kitchen"}}"""
        ),
      "entity-filter" to
        card(
          """{"type":"entity-filter","state_filter":["on"],"entities":["light.kitchen","light.office","switch.fan"],"card":{"type":"glance","title":"On now"}}"""
        ),
    )

  /** Android's launcher grid: `n` columns × `m` rows. */
  private fun cell(n: Int, m: Int) = (73 * n - 16) to (118 * m - 16)

  private class Shown(val bytes: ByteArray, val width: Int, val height: Int)

  private var shown by mutableStateOf<Shown?>(null)
  private var contentSet = false

  private fun render(file: File, bytes: ByteArray, width: Int, height: Int): Bitmap {
    if (!contentSet) {
      compose.setContent {
        val current = shown ?: return@setContent
        Box(Modifier.size(current.width.dp, current.height.dp).testTag("widget")) {
          key(current) {
            HaRcComposePlayer(
              documentBytes = current.bytes,
              modifier = Modifier.size(current.width.dp, current.height.dp),
              fallback = { error("${file.name} must play") },
            )
          }
        }
      }
      contentSet = true
    }
    compose.runOnIdle { shown = Shown(bytes, width, height) }
    compose.waitForIdle()
    val image = compose.onNodeWithTag("widget").captureToImage().asAndroidBitmap()
    file.parentFile.mkdirs()
    file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return image
  }

  @Test
  fun everyCardOnEveryLauncherCell() {
    val out = File(System.getProperty("launcherSweepOut") ?: "build/launcher-sweep")
    val missing = mutableListOf<String>()
    val findings = mutableListOf<String>()
    val report = StringBuilder("card\tcell\ttop\tbottom\tleft\tright\tfindings\n")
    for ((name, card) in cards) for (m in 1..3) for (n in 1..4) {
      val (width, height) = cell(n, m)
      val baked =
        LauncherDesigns.render(
          context,
          card,
          snapshot,
          width,
          height,
          densityDpi = 160,
          dark = true,
          systemTheme = false,
          registry = registry,
        )
      // Cards with no launcher design capture directly, as TerrazzoWidgetProvider does.
      val bytes =
        baked?.bytes
          ?: runCatching {
            captureLauncherWidgetDocument(
                context,
                card,
                snapshot,
                registry,
                width,
                height,
                densityDpi = 160,
                captureProfile = widgetsProfile,
                dark = true,
                fetchImage = { null },
              )
              .bytes
          }
            .getOrNull()
      if (bytes == null) {
        missing += "$name ${n}x$m"
        continue
      }
      val image = render(out.resolve("$name-${n}x$m.png"), bytes, width, height)
      val layout = LauncherCellLayout.of(image)
      val problems = layout.findings(columns = n, rows = m)
      report.append(
        "$name\t${n}x$m\t${layout.top}\t${layout.bottom}\t${layout.left}\t${layout.right}\t"
      )
      report.append(problems.joinToString(",")).append('\n')
      problems.forEach { findings += "$name ${n}x$m: $it" }
    }
    out.mkdirs()
    out.resolve("report.tsv").writeText(report.toString())
    kotlin.test.assertEquals(emptyList(), missing, "sizes with no widget")
    kotlin.test.assertEquals(emptyList(), findings, "see ${out.resolve("report.tsv")}")
  }
}

/**
 * Where a rendered launcher widget's content sits in its cell, as the gaps (px) between the content
 * and the widget surface's inner edge, read off the pixels: the surface colour is the commonest
 * colour on a ring just inside its border, and content is anything far from it.
 */
internal class LauncherCellLayout(
  val width: Int,
  val height: Int,
  val top: Int,
  val bottom: Int,
  val left: Int,
  val right: Int,
  /** The content reaches the right edge on this many rows of text-sized height. */
  val rightEdgeRows: Int,
  val fullBleed: Boolean,
) {
  /**
   * The launcher rules a render breaks: `clipped` when content runs into the right edge (text cut
   * by the cell, not an inset row), `pinned` when a cell of two rows or more holds its content to
   * the top and leaves the bottom blank.
   */
  fun findings(columns: Int, rows: Int): List<String> = buildList {
    if (fullBleed) return@buildList
    if (rightEdgeRows >= CLIP_ROWS) add("clipped")
    if (rows >= 2 && bottom - top > height * PINNED_FRACTION) add("pinned")
  }

  companion object {
    /** Inset past the surface's 1 dp border and anti-aliased edge. */
    private const val INSET = 3

    /** Rows touching the right edge before it counts as clipped text: a glyph run, not a dot. */
    private const val CLIP_ROWS = 6

    /** Blank below the content exceeding blank above it by this share of the cell is "pinned". */
    private const val PINNED_FRACTION = 0.25

    private const val DISTANCE = 48

    fun of(image: Bitmap): LauncherCellLayout {
      val w = image.width
      val h = image.height
      val pixels = IntArray(w * h)
      image.getPixels(pixels, 0, w, 0, 0, w, h)
      val corner = 14
      val ring = buildList {
        for (x in corner until w - corner) {
          add(pixels[INSET * w + x])
          add(pixels[(h - 1 - INSET) * w + x])
        }
        for (y in corner until h - corner) {
          add(pixels[y * w + INSET])
          add(pixels[y * w + w - 1 - INSET])
        }
      }
      val counts = ring.groupingBy { it }.eachCount()
      val surface = counts.maxBy { it.value }.key
      val ringContent = ring.count { distance(it, surface) > DISTANCE }
      fun content(x: Int, y: Int) = distance(pixels[y * w + x], surface) > DISTANCE
      var top = -1
      var bottom = -1
      var left = w
      var right = -1
      for (y in INSET until h - INSET) {
        val xFrom = if (y < corner || y >= h - corner) corner else INSET
        for (x in xFrom until w - xFrom) {
          if (content(x, y)) {
            if (top < 0) top = y
            bottom = y
            if (x < left) left = x
            if (x > right) right = x
          }
        }
      }
      var edgeRows = 0
      for (y in corner until h - corner) {
        if ((w - INSET - 3 until w - INSET).any { content(it, y) }) edgeRows++
      }
      val empty = top < 0
      return LauncherCellLayout(
        width = w,
        height = h,
        top = if (empty) h else top - INSET,
        bottom = if (empty) h else h - 1 - INSET - bottom,
        left = if (empty) w else left - INSET,
        right = if (empty) w else w - 1 - INSET - right,
        rightEdgeRows = edgeRows,
        fullBleed = ringContent > ring.size / 3,
      )
    }

    private fun distance(a: Int, b: Int): Int {
      val dr = ((a shr 16) and 0xff) - ((b shr 16) and 0xff)
      val dg = ((a shr 8) and 0xff) - ((b shr 8) and 0xff)
      val db = (a and 0xff) - (b and 0xff)
      return kotlin.math.abs(dr) + kotlin.math.abs(dg) + kotlin.math.abs(db)
    }
  }
}
