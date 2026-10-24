package ee.schimke.ha.rc

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises the published-template path, then pushes new data into the same running document. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConsoleDesignRenderTest {
  @get:Rule val compose = createComposeRule()
  private val dawn = FixedHaClock(ZonedDateTime.of(2026, 10, 17, 5, 17, 28, 0, ZoneOffset.UTC))
  private val style = ThemeStyle.TerrazzoHome

  private data class Frame(
    val card: CardConfig,
    val snapshot: HaSnapshot,
    val playback: CardTemplatePlayback?,
    val width: Int = 360,
    val dark: Boolean = true,
    val clock: HaClock,
    val direct: Boolean = false,
    val fixed: Boolean = false,
  )

  private var frame by mutableStateOf<Frame?>(null)

  private fun state(id: String, value: String, vararg attrs: Pair<String, String>) =
    EntityState(id, value, JsonObject(attrs.associate { it.first to JsonPrimitive(it.second) }))

  private fun snapshot(vararg states: EntityState) =
    HaSnapshot(states = states.associateBy { it.entityId })

  private fun card(type: String, vararg props: Pair<String, String>) =
    CardConfig(
      type,
      buildJsonObject {
        put("type", type)
        props.forEach { put(it.first, it.second) }
      },
    )

  private fun template(card: CardConfig, snapshot: HaSnapshot): CardTemplatePlayback {
    val key =
      assertNotNull(
        CardDesignTemplates.keyFor(card, snapshot, style, false, cardStyle = HaUberTheme.Console)
      )
    assertEquals(
      key,
      CardDesignTemplates.keyFor(card, snapshot, style, true, cardStyle = HaUberTheme.Console),
      "Console uses the same instrument palette in both dashboard modes",
    )
    val built = CardDesignTemplates.capture(RuntimeEnvironment.getApplication(), key)
    return CardDesignTemplates.playback(card, built.bytes)
  }

  private fun capture(name: String, next: Frame): Bitmap {
    compose.runOnIdle { frame = next }
    compose.waitForIdle()
    val image = compose.onNodeWithTag("card").captureToImage().asAndroidBitmap()
    assertTrue(image.width <= 1800 && image.height <= 1800)
    System.getProperty("lovelaceRenderEvidence")?.let { out ->
      File(out, "$name.png")
        .also { it.parentFile.mkdirs() }
        .outputStream()
        .use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    return image
  }

  private fun pixels(bitmap: Bitmap): IntArray =
    IntArray(bitmap.width * bitmap.height).also {
      bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

  private fun differs(a: Bitmap, b: Bitmap) =
    pixels(a).zip(pixels(b)).count { it.first != it.second }

  private fun startRendering() {
    compose.setContent {
      val f = frame ?: return@setContent
      CompositionLocalProvider(LocalHaClock provides f.clock) {
        Box(
          Modifier.width(f.width.dp)
            .then(if (f.fixed) Modifier.height(80.dp) else Modifier)
            .background(if (f.dark) Color(0xFF111315) else Color(0xFFF2F1ED))
            .testTag("card")
        ) {
          key(f.playback ?: if (f.direct) f.card else null) {
            CachedCardPreview(
              cacheKey =
                f.playback ?: if (f.direct) Triple(f.card, f.width, f.fixed) else "thermometer",
              profile = androidXExperimentalWrap,
              card = f.card,
              snapshot = f.snapshot,
              template = f.playback,
            ) {
              ProvideCardStyle(
                HaUberTheme.Console,
                HaUberTheme.Console.cardPalette(style, f.dark),
                f.dark,
              ) {
                if (f.direct) {
                  ProvideCardSizeMode(if (f.fixed) CardSizeMode.Fixed else CardSizeMode.Wrap) {
                    ProvideCardRegistry(ee.schimke.ha.rc.cards.defaultRegistry()) {
                      RenderChild(f.card, f.snapshot, RemoteModifier.fillMaxWidth())
                    }
                  }
                } else
                  androidx.compose.remote.creation.compose.layout.RemoteBox(
                    RemoteModifier.fillMaxWidth()
                      .then(
                        cardChrome(
                          currentRemoteHaTheme().cardBackground,
                          currentRemoteHaTheme().divider,
                        )
                      )
                  ) {
                    RemoteHaThermometer(
                      HaReading.live(
                        "sensor.temp",
                        "Room temperature",
                        "21.5 °C",
                        21.5f,
                        "°C",
                        HaMeasureKind.Temperature,
                      ),
                      modifier = RemoteModifier.fillMaxWidth(),
                    )
                  }
              }
            }
          }
        }
      }
    }
  }

  @Test
  fun physicalInstrumentsRenderAndUpdate() {
    startRendering()
    val fan = card("button", "entity" to "switch.fan", "name" to "Fan")
    val off = snapshot(state("switch.fan", "off", "friendly_name" to "Fan"))
    val on = snapshot(state("switch.fan", "on", "friendly_name" to "Fan"))
    val unavailable = snapshot(state("switch.fan", "unavailable", "friendly_name" to "Fan"))
    val toggle = template(fan, off)
    for (dark in listOf(false, true)) {
      val mode = if (dark) "dark" else "light"
      val a = capture("switch-$mode-off", Frame(fan, off, toggle, dark = dark, clock = dawn))
      val b = capture("switch-$mode-on", Frame(fan, on, toggle, dark = dark, clock = dawn))
      assertTrue(differs(a, b) > 50, "same switch document responds to is_on")
      val c =
        capture(
          "switch-$mode-unavailable",
          Frame(fan, unavailable, toggle, dark = dark, clock = dawn),
        )
      assertTrue(differs(b, c) > 50, "availability dims the instrument")
    }
    capture("switch-compact", Frame(fan, on, toggle, width = 96, clock = dawn))
    val row = card("entity", "entity" to "switch.fan")
    val rowPlayback = template(row, off)
    val rowOff = capture("switch-row-off", Frame(row, off, rowPlayback, clock = dawn))
    val rowOn = capture("switch-row-on", Frame(row, on, rowPlayback, clock = dawn))
    assertTrue(differs(rowOff, rowOn) > 20, "same row rocker responds to is_on")
    val media = card("media-control", "entity" to "media_player.studio")
    for (state in listOf("playing", "paused")) {
      val music =
        snapshot(
          state(
            "media_player.studio",
            state,
            "friendly_name" to "Studio",
            "media_title" to "Blue in Green",
            "media_artist" to "Miles Davis",
          )
        )
      capture("media-$state", Frame(media, music, template(media, music), clock = dawn))
    }
    val printerCard = card("custom:ha-bambulab-print_control-card", "entity_prefix" to "x1c")
    for (phase in listOf("running", "pause")) {
      val printer =
        snapshot(
          state("sensor.x1c_print_status", phase),
          state("button.x1c_pause_printing", "unknown"),
          state("button.x1c_resume_printing", "unknown"),
          state("button.x1c_stop_printing", "unknown"),
        )
      capture(
        "controls-$phase",
        Frame(printerCard, printer, template(printerCard, printer), clock = dawn),
      )
    }
    val disabledPrinter =
      snapshot(
        state("sensor.x1c_print_status", "running"),
        state("button.x1c_pause_printing", "unavailable"),
        state("button.x1c_stop_printing", "unknown"),
      )
    capture(
      "controls-disabled",
      Frame(
        printerCard,
        disabledPrinter,
        template(printerCard, disabledPrinter),
        clock = dawn,
      ),
    )
    val gauge = card("gauge", "entity" to "sensor.cpu", "name" to "CPU")
    val cpu = snapshot(state("sensor.cpu", "64", "unit_of_measurement" to "%"))
    capture("gauge", Frame(gauge, cpu, template(gauge, cpu), clock = dawn))
    val temp = card("sensor", "entity" to "sensor.temp")
    val low =
      snapshot(
        state("sensor.temp", "10", "unit_of_measurement" to "°C", "device_class" to "temperature")
      )
    val high =
      snapshot(
        state("sensor.temp", "32", "unit_of_measurement" to "°C", "device_class" to "temperature")
      )
    val cold = capture("thermometer-low", Frame(temp, low, null, clock = dawn))
    val hot = capture("thermometer-high", Frame(temp, high, null, clock = dawn))
    assertTrue(differs(cold, hot) > 50, "liquid column responds to numeric_state without recapture")
    var liquidChanges = 0
    for (y in 0 until cold.height) for (x in 0 until 30) {
      if (cold.getPixel(x, y) != hot.getPixel(x, y)) liquidChanges++
    }
    assertTrue(liquidChanges > 5, "the liquid itself moves, independently of the numeric label")
    val clock =
      card(
        "clock",
        "clock_size" to "large",
        "time_format" to "12",
        "time_zone" to "UTC",
        "show_seconds" to "true",
      )
    val clockPlayback = template(clock, HaSnapshot())
    val early = capture("clock-seconds", Frame(clock, HaSnapshot(), clockPlayback, clock = dawn))
    val nextSecond = FixedHaClock(ZonedDateTime.of(2026, 10, 17, 5, 17, 29, 0, ZoneOffset.UTC))
    val ticked =
      capture("clock-next-second", Frame(clock, HaSnapshot(), clockPlayback, clock = nextSecond))
    assertTrue(differs(early, ticked) > 20, "seconds advance without changing the date or document")
    val tokyo =
      card(
        "clock",
        "clock_size" to "large",
        "time_format" to "12",
        "time_zone" to "Asia/Tokyo",
        "show_seconds" to "true",
      )
    val shifted =
      capture(
        "clock-tokyo",
        Frame(tokyo, HaSnapshot(), template(tokyo, HaSnapshot()), clock = dawn),
      )
    assertTrue(differs(early, shifted) > 100, "the LED hour follows the configured zone offset")
    val midnight = FixedHaClock(ZonedDateTime.of(2026, 10, 18, 0, 0, 0, 0, ZoneOffset.UTC))
    val late =
      capture("clock-midnight", Frame(clock, HaSnapshot(), clockPlayback, clock = midnight))
    assertTrue(
      differs(early, late) > 100,
      "same clock document follows player time across midnight",
    )
    capture("clock-compact", Frame(clock, HaSnapshot(), clockPlayback, width = 180, clock = dawn))
    val simpleClock = card("clock", "clock_size" to "large", "display" to "time")
    capture(
      "clock",
      Frame(simpleClock, HaSnapshot(), template(simpleClock, HaSnapshot()), clock = dawn),
    )
    assertTrue(
      pixels(early).count {
        android.graphics.Color.red(it) > 200 && android.graphics.Color.green(it) < 150
      } > 100,
      "console clock has real red LED emitters, not ordinary theme text",
    )
  }

  @Test
  fun thermostatSeparatesRoomReadingFromSetPointKnob() {
    startRendering()
    val thermostat = card("thermostat", "entity" to "climate.studio")
    fun climate(current: String, target: String? = "22", mode: String = "heat") =
      snapshot(
        state(
          "climate.studio",
          mode,
          *buildList {
            add("friendly_name" to "Studio")
            add("current_temperature" to current)
            if (target != null) add("temperature" to target)
            add(
              "hvac_action" to
                if (mode == "cool") "cooling" else if (mode == "off") "off" else "idle"
            )
          }
            .toTypedArray(),
        )
      )
    val initial = climate("19")
    val playback = template(thermostat, initial)
    val cold = capture("thermostat-room-19", Frame(thermostat, initial, playback, clock = dawn))
    val warm =
      capture("thermostat-room-26", Frame(thermostat, climate("26"), playback, clock = dawn))
    val adjusted =
      capture("thermostat-set-28", Frame(thermostat, climate("26", "28"), playback, clock = dawn))
    capture(
      "thermostat-cooling",
      Frame(thermostat, climate("26", "18", "cool"), playback, clock = dawn),
    )
    val off = climate("19", null, "off")
    capture("thermostat-no-target", Frame(thermostat, off, template(thermostat, off), clock = dawn))
    capture("thermostat-narrow", Frame(thermostat, initial, playback, width = 260, clock = dawn))
    capture(
      "thermostat-compact",
      Frame(thermostat, initial, null, width = 180, clock = dawn, direct = true, fixed = true),
    )
    assertTrue(differs(cold, warm) > 20, "room reading stays live in the same document")
    assertTrue(differs(warm, adjusted) > 20, "set point stays live in the same document")
    // This region is the physical knob and printed scale, excluding the separate readouts.
    fun knobChanges(a: Bitmap, b: Bitmap): Int =
      (108 until 220).sumOf { y ->
        (94 until 198).count { x -> a.getPixel(x, y) != b.getPixel(x, y) }
      }
    assertEquals(0, knobChanges(cold, warm), "a room sensor cannot turn a set-point knob")
    assertTrue(
      knobChanges(warm, adjusted) > 10,
      "the knob index follows the live requested temperature",
    )
  }

  @Test
  fun remainingControlsAndServiceTapeRender() {
    startRendering()
    fun jsonCard(type: String, raw: String) =
      CardConfig(type, kotlinx.serialization.json.Json.parseToJsonElement(raw) as JsonObject)
    val base =
      snapshot(
        state("switch.fan", "on", "friendly_name" to "Fan"),
        state("light.kitchen", "on", "friendly_name" to "Kitchen"),
        state("sensor.temp", "21.5", "friendly_name" to "Room", "unit_of_measurement" to "°C"),
        state(
          "alarm_control_panel.home",
          "disarmed",
          "code_format" to "number",
          "friendly_name" to "Alarm",
        ),
        state(
          "sensor.x1c_ams_1_tray_1",
          "Bambu PLA Basic",
          "active" to "true",
          "color" to "#F2C200FF",
          "remain" to "80",
        ),
        state(
          "sensor.x1c_ams_1_tray_2",
          "unknown",
          "active" to "false",
          "remain_enabled" to "false",
        ),
        state(
          "sensor.x1c_ams_1_tray_3",
          "Generic PETG",
          "active" to "false",
          "color" to "#1E5AA8FF",
          "remain" to "35",
        ),
        state(
          "sensor.x1c_ams_1_tray_4",
          "Bambu PLA very long material name",
          "active" to "false",
          "remain_enabled" to "false",
        ),
        state("button.restart", "2026-10-17T05:17:28Z", "friendly_name" to "Restart"),
        state("todo.empty", "0", "friendly_name" to "Empty list"),
        state("calendar.empty", "off", "friendly_name" to "Calendar"),
        EntityState(
          "climate.room",
          "heat",
          kotlinx.serialization.json.Json.parseToJsonElement(
            """{"friendly_name":"Studio","current_temperature":21.5,"temperature":22,"min_temp":10,"max_temp":32,"supported_features":401,"hvac_modes":["heat","off"]}"""
          ) as JsonObject,
        ),
        EntityState(
          "todo.shopping",
          "1",
          kotlinx.serialization.json.Json.parseToJsonElement(
            """{"friendly_name":"Shopping","items":[{"uid":"1","summary":"Milk","status":"needs_action"},{"uid":"2","summary":"Bread","status":"completed"}]}"""
          ) as JsonObject,
        ),
      )
    val controls =
      listOf(
        "alarm-keys" to
          jsonCard(
            "alarm-panel",
            """{"type":"alarm-panel","entity":"alarm_control_panel.home","show_keypad":true}""",
          ),
        "dial-steppers" to card("thermostat", "entity" to "climate.room"),
        "tile-features" to
          jsonCard(
            "tile",
            """{"type":"tile","entity":"climate.room","features":[{"type":"target-temperature"},{"type":"climate-hvac-modes"}]}""",
          ),
        "tile-command" to
          jsonCard(
            "tile",
            """{"type":"tile","entity":"button.restart","tap_action":{"action":"call-service","service":"button.press","target":{"entity_id":"button.restart"}}}""",
          ),
        "area-actions" to
          jsonCard(
            "area",
            """{"type":"area","area":"living_room","entities":["sensor.temp","light.kitchen","switch.fan"]}""",
          ),
        "glance-keys" to
          jsonCard("glance", """{"type":"glance","entities":["light.kitchen","switch.fan"]}"""),
        "picture-keys" to
          jsonCard(
            "picture-glance",
            """{"type":"picture-glance","title":"Studio","image":"/local/studio.jpg","entities":["switch.fan","light.kitchen"]}""",
          ),
        "service-keys" to
          jsonCard(
            "picture-elements",
            """{"type":"picture-elements","image":"/local/studio.jpg","elements":[{"type":"state-icon","entity":"switch.fan","style":{"left":"20%","top":"60%"}},{"type":"service-button","title":"All off","service":"light.turn_off"}]}""",
          ),
        "spool-labels" to card("custom:ha-bambulab-ams-card", "entity_prefix" to "x1c"),
        "todo-keys" to card("todo-list", "entity" to "todo.shopping"),
      )
    for ((name, card) in controls) capture(
      "audit-$name",
      Frame(
        card,
        base,
        if (name.startsWith("tile-")) null else template(card, base),
        clock = dawn,
        direct = name.startsWith("tile-"),
      ),
    )
    val placeholders =
      listOf(
        "no-controls" to card("custom:ha-bambulab-print_control-card", "entity_prefix" to "empty"),
        "unsupported" to card("custom:missing-console-card"),
        "no-image" to card("picture-entity", "entity" to "switch.fan"),
        "no-history" to
          jsonCard("history-graph", """{"type":"history-graph","entities":["sensor.temp"]}"""),
        "no-items" to card("todo-list", "entity" to "todo.empty"),
        "no-events" to
          jsonCard("calendar", """{"type":"calendar","entities":["calendar.empty"]}"""),
        "no-activity" to jsonCard("logbook", """{"type":"logbook","entities":[]}"""),
      )
    fun paperPixels(image: Bitmap) =
      pixels(image).count {
        android.graphics.Color.red(it) > 210 &&
          android.graphics.Color.green(it) > 210 &&
          android.graphics.Color.blue(it) > 200
      }
    for ((name, card) in placeholders) {
      val image =
        capture(
          "tape-$name",
          Frame(
            card,
            base,
            if (name == "no-activity") null else template(card, base),
            clock = dawn,
            direct = name == "no-activity",
          ),
        )
      assertTrue(paperPixels(image) > 400, "$name is a legible white tape note")
    }
    val fan = card("button", "entity" to "switch.fan")
    val playback = template(fan, base)
    val available = capture("tape-available", Frame(fan, base, playback, clock = dawn))
    val missing = snapshot(state("switch.fan", "unavailable", "friendly_name" to "Fan"))
    val unavailable = capture("tape-unavailable", Frame(fan, missing, playback, clock = dawn))
    assertTrue(
      paperPixels(unavailable) > paperPixels(available) + 400,
      "the same document shows tape on loss of availability",
    )
    val recovered = capture("tape-recovered", Frame(fan, base, playback, clock = dawn))
    assertEquals(0, differs(available, recovered), "tape disappears when the entity returns")
  }
}
