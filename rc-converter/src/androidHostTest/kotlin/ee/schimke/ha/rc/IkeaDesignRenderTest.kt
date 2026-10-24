package ee.schimke.ha.rc

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
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
import ee.schimke.ha.rc.components.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual app-player evidence, including same-document data pushes and published slot actions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w600dp-h1600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IkeaDesignRenderTest {
  @get:Rule val compose = createComposeRule()
  private val style = ThemeStyle.TerrazzoHome

  private data class Frame(
    val card: CardConfig,
    val snapshot: HaSnapshot,
    val playback: CardTemplatePlayback?,
    val theme: HaUberTheme = HaUberTheme.Ikea,
    val dark: Boolean = false,
    val width: Int = 360,
    val fixed: Boolean = false,
  )

  private var frame by mutableStateOf<Frame?>(null)

  private fun card(raw: String): CardConfig {
    val json = Json.parseToJsonElement(raw) as JsonObject
    return CardConfig((json.getValue("type") as JsonPrimitive).content, json)
  }

  private fun snapshot(id: String, value: String, vararg attrs: Pair<String, String>) =
    HaSnapshot(
      states =
        mapOf(
          id to
            EntityState(
              id,
              value,
              JsonObject(attrs.associate { it.first to JsonPrimitive(it.second) }),
            )
        )
    )

  private fun template(
    card: CardConfig,
    snapshot: HaSnapshot,
    dark: Boolean = false,
    theme: HaUberTheme = HaUberTheme.Ikea,
  ): CardTemplatePlayback {
    val key =
      assertNotNull(CardDesignTemplates.keyFor(card, snapshot, style, dark, cardStyle = theme))
    val built = CardDesignTemplates.capture(RuntimeEnvironment.getApplication(), key)
    return CardDesignTemplates.playback(card, built.bytes)
  }

  private fun start() {
    compose.setContent {
      val f = frame ?: return@setContent
      Box(
        Modifier.width(f.width.dp)
          .then(if (f.fixed) Modifier.height(80.dp) else Modifier)
          .background(f.theme.palette(f.dark).dashboardBackground)
          .testTag("card")
      ) {
        key(f.playback ?: listOf(f.card, f.width, f.fixed, f.theme.id, f.dark)) {
          CachedCardPreview(
            cacheKey = f.playback ?: listOf(f.card, f.width, f.fixed, f.theme.id, f.dark),
            profile = androidXExperimentalWrap,
            card = f.card,
            snapshot = f.snapshot,
            template = f.playback,
          ) {
            ProvideHaUberTheme(f.theme, f.dark) {
              ProvideCardSizeMode(if (f.fixed) CardSizeMode.Fixed else CardSizeMode.Wrap) {
                ProvideCardRegistry(ee.schimke.ha.rc.cards.defaultRegistry()) {
                  RenderChild(f.card, f.snapshot, RemoteModifier.fillMaxWidth())
                }
              }
            }
          }
        }
      }
    }
  }

  private fun capture(name: String, next: Frame): Bitmap {
    compose.runOnIdle { frame = next }
    compose.waitForIdle()
    val image = compose.onNodeWithTag("card").captureToImage().asAndroidBitmap()
    assertTrue(image.width <= 1800 && image.height <= 1800)
    System.getProperty("lovelaceRenderEvidence")?.let { out ->
      File(out).mkdirs()
      next.playback?.let { playback ->
        LauncherDesigns.bakeValues(playback.bytes, playback, next.snapshot)?.let { bytes ->
          File(out, "$name.rc").writeBytes(bytes)
        }
      }
      File(out, "$name.png")
        .also { it.parentFile?.mkdirs() }
        .outputStream()
        .use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    return image
  }

  private fun differs(a: Bitmap, b: Bitmap): Int {
    assertEquals(a.width, b.width)
    assertEquals(a.height, b.height)
    return (0 until a.height).sumOf { y ->
      (0 until a.width).count { x -> a.getPixel(x, y) != b.getPixel(x, y) }
    }
  }

  @Test
  fun remoteBindsBrightnessAndKeepsExplicitPowerActions() {
    start()
    val light = card("""{"type":"light","entity":"light.reading","name":"Reading corner"}""")
    fun lightState(brightness: String, value: String = "on") =
      snapshot(
        "light.reading",
        value,
        "friendly_name" to "Reading corner",
        "brightness" to brightness,
      )
    val initial = lightState("128")
    for (dark in listOf(false, true)) {
      val mode = if (dark) "dark" else "light"
      val playback = template(light, initial, dark)
      val a = capture("light-$mode-50", Frame(light, initial, playback, dark = dark))
      val b = capture("light-$mode-90", Frame(light, lightState("230"), playback, dark = dark))
      assertTrue(differs(a, b) > 20, "brightness updates in the same published document")
      val off = lightState("128", "off")
      capture("light-$mode-off", Frame(light, off, template(light, off, dark), dark = dark))
      // A template's explicit On and Off must never fall back to its default Toggle action.
      for (service in listOf("turn_on", "turn_off")) {
        assertEquals(
          HaAction.CallService("light", service, "light.reading"),
          playback.resolveTap(HaAction.CallService("light", service, "light.s0"), initial),
        )
      }
      val up =
        playback.resolveTap(
          HaAction.CallService(
            "light",
            "turn_on",
            "light.s0",
            JsonObject(mapOf("brightness" to JsonPrimitive(153))),
          ),
          lightState("230"),
        )
      assertEquals(
        HaAction.CallService(
          "light",
          "turn_on",
          "light.reading",
          JsonObject(mapOf("brightness" to JsonPrimitive(255))),
        ),
        up,
      )
      val before = template(light, initial, dark, HaUberTheme.Classic)
      capture("before-light-$mode", Frame(light, initial, before, HaUberTheme.Classic, dark))
    }
    val keyLight =
      CardDesignTemplates.keyFor(light, initial, style, false, cardStyle = HaUberTheme.Ikea)
    val keyDark =
      CardDesignTemplates.keyFor(light, initial, style, true, cardStyle = HaUberTheme.Ikea)
    assertNotEquals(keyLight, keyDark)
    assertEquals(listOf(false, true), CardDesignTemplates.modesOf(HaUberTheme.Ikea))
    capture("light-compact", Frame(light, initial, null, width = 180, fixed = true))
  }

  @Test
  fun mixedShowroomFitsPhoneAndBrowserWidths() {
    start()
    val grid =
      card(
        """{"type":"grid","columns":2,"square":false,"cards":[
      {"type":"light","entity":"light.reading","name":"Reading corner"},
      {"type":"thermostat","entity":"climate.room","name":"Living room"},
      {"type":"gauge","entity":"sensor.humidity","name":"Room humidity"},
      {"type":"media-control","entity":"media_player.shelf"},
      {"type":"entities","title":"At home","entities":["switch.reading","sensor.battery"]},
      {"type":"history-graph","title":"Temperature","entities":["sensor.room"]}
    ]}"""
      )
    val snapshots =
      listOf(
        snapshot("light.reading", "on", "brightness" to "180"),
        snapshot(
          "climate.room",
          "heat",
          "temperature" to "22",
          "current_temperature" to "19.5",
          "hvac_action" to "heating",
        ),
        snapshot("sensor.humidity", "64", "unit_of_measurement" to "%"),
        snapshot(
          "media_player.shelf",
          "playing",
          "friendly_name" to "Bookshelf speaker",
          "media_title" to "A quiet morning",
          "media_artist" to "Showroom radio",
        ),
        snapshot("switch.reading", "on", "friendly_name" to "Reading lamp"),
        snapshot(
          "sensor.battery",
          "72",
          "friendly_name" to "Remote battery",
          "device_class" to "battery",
          "unit_of_measurement" to "%",
        ),
        snapshot(
          "sensor.room",
          "21.5",
          "friendly_name" to "Living room",
          "device_class" to "temperature",
          "unit_of_measurement" to "°C",
        ),
      )
    val home =
      HaSnapshot(
        states = snapshots.flatMap { it.states.entries }.associate { it.toPair() },
        history =
          mapOf(
            "sensor.room" to
              listOf(18.5, 19.0, 20.5, 19.8, 21.5).mapIndexed { i, v ->
                ee.schimke.ha.model.HistoryPoint(
                  kotlin.time.Instant.fromEpochSeconds(1_790_000_000L + i * 3600),
                  v.toString(),
                )
              }
          ),
      )
    for (dark in listOf(false, true)) {
      val mode = if (dark) "dark" else "light"
      capture("showroom-$mode", Frame(grid, home, null, dark = dark, width = 560))
      capture("showroom-phone-$mode", Frame(grid, home, null, dark = dark, width = 360))
      capture(
        "before-showroom-$mode",
        Frame(grid, home, null, theme = HaUberTheme.Classic, dark = dark, width = 560),
      )
    }
  }

  @Test
  fun furnitureMetersAndPaddlesStayLive() {
    start()
    val entity = card("""{"type":"entity","entity":"switch.reading","name":"Reading lamp"}""")
    val off = snapshot("switch.reading", "off", "friendly_name" to "Reading lamp")
    val on = snapshot("switch.reading", "on", "friendly_name" to "Reading lamp")
    for (dark in listOf(false, true)) {
      val mode = if (dark) "dark" else "light"
      val playback = template(entity, off, dark)
      val a = capture("paddle-$mode-off", Frame(entity, off, playback, dark = dark))
      val b = capture("paddle-$mode-on", Frame(entity, on, playback, dark = dark))
      assertTrue(differs(a, b) > 10, "paddle indicator responds to the same document's is_on")
      val unavailable = snapshot("switch.reading", "unavailable", "friendly_name" to "Reading lamp")
      val c = capture("paddle-$mode-unavailable", Frame(entity, unavailable, playback, dark = dark))
      assertTrue(differs(b, c) > 10, "offline state is visible")
      val gauge = card("""{"type":"gauge","entity":"sensor.humidity","name":"Room humidity"}""")
      val low = snapshot("sensor.humidity", "30", "unit_of_measurement" to "%")
      val high = snapshot("sensor.humidity", "75", "unit_of_measurement" to "%")
      val meter = template(gauge, low, dark)
      val lowImage = capture("gauge-$mode-30", Frame(gauge, low, meter, dark = dark))
      val highImage = capture("gauge-$mode-75", Frame(gauge, high, meter, dark = dark))
      assertTrue(differs(lowImage, highImage) > 50, "ring and reading move on numeric_state")
      val sensor = card("""{"type":"sensor","entity":"sensor.room","name":"Room temperature"}""")
      val room =
        snapshot(
          "sensor.room",
          "21.5",
          "unit_of_measurement" to "°C",
          "device_class" to "temperature",
        )
      capture("sensor-$mode", Frame(sensor, room, template(sensor, room, dark), dark = dark))
      val thermostat =
        card("""{"type":"thermostat","entity":"climate.room","name":"Living room"}""")
      val climate =
        snapshot(
          "climate.room",
          "heat",
          "current_temperature" to "19.5",
          "temperature" to "22",
          "hvac_action" to "heating",
        )
      capture(
        "climate-$mode",
        Frame(thermostat, climate, template(thermostat, climate, dark), dark = dark),
      )
      val music = card("""{"type":"media-control","entity":"media_player.shelf"}""")
      val playing =
        snapshot(
          "media_player.shelf",
          "playing",
          "friendly_name" to "Bookshelf speaker",
          "media_title" to "A quiet morning",
          "media_artist" to "Showroom radio",
        )
      capture("media-$mode", Frame(music, playing, template(music, playing, dark), dark = dark))
      capture(
        "before-media-$mode",
        Frame(
          music,
          playing,
          template(music, playing, dark, HaUberTheme.Classic),
          theme = HaUberTheme.Classic,
          dark = dark,
        ),
      )
    }
    capture("paddle-compact", Frame(entity, on, null, width = 180, fixed = true))
  }
}
