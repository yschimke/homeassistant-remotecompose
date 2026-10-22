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
import java.io.File
import kotlin.test.Test
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every card the sweep covers, as the launcher widget draws it (the real path: a template captured
 * at the widget's size, baked with its values) on each launcher grid size from 1×1 to 4×3, on
 * Android's `(73n − 16) × (118m − 16)` cells. The renders land in `build/launcher-sweep/` for
 * review; the test fails only when a size gives no widget at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherGridSweepTest {
  @get:Rule val compose = createComposeRule()

  private val registry = CardDesignTemplates.defaultTemplateRegistry
  private val context = RuntimeEnvironment.getApplication()

  private fun entity(id: String, state: String, vararg attributes: Pair<String, String>) =
    id to
      EntityState(id, state, JsonObject(attributes.associate { (k, v) -> k to JsonPrimitive(v) }))

  private val snapshot =
    HaSnapshot(
      states =
        mapOf(
          entity("light.kitchen", "on", "friendly_name" to "Kitchen"),
          entity(
            "sensor.living_temperature",
            "21.5",
            "friendly_name" to "Living room",
            "unit_of_measurement" to "°C",
            "device_class" to "temperature",
          ),
          entity("switch.fan", "off", "friendly_name" to "Fan"),
          entity("lock.front_door", "locked", "friendly_name" to "Front door"),
          entity(
            "sensor.power",
            "640",
            "friendly_name" to "Power",
            "unit_of_measurement" to "W",
            "device_class" to "power",
          ),
        ),
      history =
        mapOf(
          "sensor.power" to
            (0 until 24).map { hour ->
              HistoryPoint(
                Instant.fromEpochSeconds(1_760_000_000L + hour * 3600L),
                (400 + 300 * kotlin.math.sin(hour / 3.0)).toInt().toString(),
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
    )

  /** Android's launcher grid: `n` columns × `m` rows. */
  private fun cell(n: Int, m: Int) = (73 * n - 16) to (118 * m - 16)

  private class Shown(val bytes: ByteArray, val width: Int, val height: Int)

  private var shown by mutableStateOf<Shown?>(null)
  private var contentSet = false

  private fun render(file: File, bytes: ByteArray, width: Int, height: Int) {
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
  }

  @Test
  fun everyCardOnEveryLauncherCell() {
    val out = File(System.getProperty("launcherSweepOut") ?: "build/launcher-sweep")
    val missing = mutableListOf<String>()
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
      if (baked == null) {
        missing += "$name ${n}x$m"
        continue
      }
      render(out.resolve("$name-${n}x$m.png"), baked.bytes, width, height)
    }
    kotlin.test.assertEquals(emptyList(), missing)
  }
}
