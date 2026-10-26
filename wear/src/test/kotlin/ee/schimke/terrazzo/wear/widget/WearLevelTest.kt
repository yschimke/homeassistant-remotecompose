package ee.schimke.terrazzo.wear.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.glance.wear.core.ContainerInfo
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.core.WidgetInstanceId
import androidx.wear.compose.remote.material3.RemoteColorScheme
import ee.schimke.composeai.rcplayer.compose.rememberRcNamedValues
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.HaRcComposePlayer
import ee.schimke.ha.rc.LauncherDesigns
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.converterFor
import ee.schimke.terrazzo.wear.ui.terrazzoWearColorScheme
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A battery card's Wear container is a level that keeps up with Home Assistant
 * (`live-card-updates.md`): a widget bakes the current reading, the Wear app moves it on a pushed
 * value without a new document, and a reading that isn't a number shows none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WearLevelTest {
  @get:Rule val compose = createComposeRule()

  private val context = RuntimeEnvironment.getApplication()
  private val registry = defaultRegistry()
  private val colors = RemoteColorScheme(terrazzoWearColorScheme(ThemeStyle.entries.first()))
  private val params =
    WearWidgetParams(
      instanceId = WidgetInstanceId("widgets", 1),
      containerType = ContainerInfo.CONTAINER_TYPE_LARGE,
      widthDp = 200f,
      heightDp = 112f,
      horizontalPaddingDp = 8f,
      verticalPaddingDp = 8f,
      cornerRadiusDp = 26f,
    )

  private val card =
    CardConfig(
      type = "tile",
      raw =
        buildJsonObject {
          put("type", "tile")
          put("entity", "sensor.phone_battery")
        },
    )

  private fun snapshot(state: String) =
    HaSnapshot(
      states =
        mapOf(
          "sensor.phone_battery" to
            EntityState(
              "sensor.phone_battery",
              state,
              buildJsonObject {
                put("friendly_name", "Phone")
                put("device_class", "battery")
                put("unit_of_measurement", "%")
              },
            )
        )
    )

  private val design = CardDesignTemplates.designCard(card, snapshot("50"), registry)
  private val input =
    registry.converterFor(design.type)!!.let { converter ->
      converter.wearDesignTemplate(converter.wearDesignProperties(design, snapshot("50"))!!)!!
    }

  private fun template(style: HaUberTheme): ByteArray = runBlocking {
    WearWidgetTemplate.capture(context, style, wearLevel(input.card, input.snapshot)) {
      SlotContent(registry, colors, input.card, input.snapshot, template = true, style)
    }
  }

  /** [style]'s widget for the card in [state], as the watch bakes it. */
  private fun widget(style: HaUberTheme, state: String): ByteArray {
    val widget = runBlocking { WearWidgetTemplate.instantiate(context, template(style), params) }!!
    val playback = CardDesignTemplates.playback(design, widget.rcDocument, registry)
    return assertNotNull(LauncherDesigns.bakeValues(widget.rcDocument, playback, snapshot(state)))
  }

  private var bytes by mutableStateOf(ByteArray(0))
  private var values: MutableMap<String, RcNamedValue>? = null

  private fun play() {
    compose.setContent {
      val named = rememberRcNamedValues()
      values = named
      Box(Modifier.size(200.dp, 112.dp).testTag("widget")) {
        key(bytes) {
          if (bytes.isNotEmpty()) {
            HaRcComposePlayer(
              documentBytes = bytes,
              modifier = Modifier.size(200.dp, 112.dp),
              namedValues = named,
              fallback = { error("must play") },
            )
          }
        }
      }
    }
  }

  private fun shot(): ImageBitmap {
    compose.waitForIdle()
    compose.mainClock.advanceTimeBy(2_000)
    compose.waitForIdle()
    return compose.onNodeWithTag("widget").captureToImage()
  }

  private fun differing(a: ImageBitmap, b: ImageBitmap): Int {
    val pa = a.toPixelMap()
    val pb = b.toPixelMap()
    var count = 0
    for (y in 0 until pa.height) for (x in 0 until pa.width) if (pa[x, y] != pb[x, y]) count++
    return count
  }

  private val ring = HaUberTheme.HomeAssistant
  private val fill = HaUberTheme.Obsidian
  private val segments = HaUberTheme.Lcd

  @Test
  fun aWidgetBakesTheCurrentReading() {
    play()
    for (style in listOf(ring, fill, segments)) {
      bytes = widget(style, "20")
      val low = shot()
      bytes = widget(style, "90")
      val high = shot()
      // The card's own "20 %" / "90 %" text differs too: the level is most of the difference.
      assertTrue(differing(low, high) > 1_500, "${style.id}: the level follows the reading")
    }
  }

  @Test
  fun aReadingThatIsNotANumberShowsNone() {
    play()
    for (style in listOf(ring, fill, segments)) {
      bytes = widget(style, "0")
      val empty = shot()
      bytes = widget(style, "unavailable")
      val unavailable = shot()
      // Only the state text differs ("0 %" against "Unavailable").
      val total = 200 * 112
      assertTrue(
        differing(empty, unavailable) * 20 < total,
        "${style.id}: an unavailable reading draws no level",
      )
    }
  }

  @Test
  fun theWearAppMovesTheLevelOnAPushedReading() {
    val app =
      assertNotNull(WearWidgetTemplate.forApp(template(ring), WearWidgetFrame.DEFAULT_LARGE))
    val playback = CardDesignTemplates.playback(design, app, registry)
    fun push(state: String) {
      val bindings = playback.bindings(snapshot(state), ee.schimke.ha.rc.SystemHaClock)
      compose.runOnIdle {
        bindings.floats.forEach { (name, v) -> values!![name] = RcNamedValue.FloatValue(v) }
        bindings.strings.forEach { (name, v) -> values!![name] = RcNamedValue.Text(v) }
      }
    }
    play()
    bytes = app
    push("20")
    val low = shot()
    val document = bytes
    push("90")
    val high = shot()
    assertTrue(document === bytes, "the same document plays")
    assertTrue(differing(low, high) > 1_500, "the pushed reading moves the level")
    push("20")
    assertTrue(differing(low, shot()) * 100 < 200 * 112, "and back")
  }
}
