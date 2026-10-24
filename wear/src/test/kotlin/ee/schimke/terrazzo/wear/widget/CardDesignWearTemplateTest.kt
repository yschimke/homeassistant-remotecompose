@file:Suppress("RestrictedApi")

package ee.schimke.terrazzo.wear.widget

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
import androidx.glance.wear.core.ContainerInfo
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.core.WidgetInstanceId
import androidx.wear.compose.remote.material3.RemoteColorScheme
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcFloatExpression
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.protocol.RcPaddingModifier
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.HaRcComposePlayer
import ee.schimke.ha.rc.LauncherDesigns
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.terrazzo.wear.ui.terrazzoWearColorScheme
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
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
 * A phone card design made into a Wear widget on the watch, without a Wear design of its own: its
 * card box becomes the container ([CardDesignWearTemplate]) and [WearWidgetTemplate.instantiate]
 * fits it to each host. The renders, beside the watch's own capture of the same card, land in
 * `build/wear-reframe/` for review.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardDesignWearTemplateTest {
  @get:Rule val compose = createComposeRule()

  private val registry = defaultRegistry()
  private val style = ThemeStyle.entries.first()
  private val colors = RemoteColorScheme(terrazzoWearColorScheme(style))
  private val context = RuntimeEnvironment.getApplication()

  private fun entity(id: String, state: String, vararg attributes: Pair<String, String>) =
    id to
      EntityState(
        id,
        state,
        JsonObject(attributes.associate { (k, v) -> k to JsonPrimitive(v) }),
      )

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
        )
    )

  private fun card(json: String): CardConfig {
    val raw = Json.parseToJsonElement(json) as JsonObject
    return CardConfig(type = (raw["type"] as JsonPrimitive).content, raw = raw)
  }

  private val cards =
    mapOf(
      "tile" to card("""{"type":"tile","entity":"light.kitchen"}"""),
      "button" to card("""{"type":"button","entity":"light.kitchen"}"""),
      "gauge" to card("""{"type":"gauge","entity":"sensor.living_temperature","min":0,"max":40}"""),
      "entities" to
        card(
          """{"type":"entities","title":"Home","entities":["light.kitchen","sensor.living_temperature","switch.fan"]}"""
        ),
    )

  private val cardStyles =
    listOf(HaUberTheme.Classic, HaUberTheme.byId("lcd"), HaUberTheme.byId("lcars"))

  private fun params(
    type: Int,
    width: Float,
    height: Float,
    horizontal: Float,
    vertical: Float,
    corner: Float,
  ) =
    WearWidgetParams(
      WidgetInstanceId("widgets", 1),
      type,
      width,
      height,
      horizontal,
      vertical,
      corner,
    )

  /** Pixel Watch's squircle and Samsung's round host, Small and Large. */
  private val hosts =
    mapOf(
      "squircle-small" to params(ContainerInfo.CONTAINER_TYPE_SMALL, 200f, 60f, 8f, 8f, 26f),
      "squircle-large" to params(ContainerInfo.CONTAINER_TYPE_LARGE, 200f, 108f, 8f, 8f, 26f),
      "round-small" to params(ContainerInfo.CONTAINER_TYPE_SMALL, 200f, 60f, 15f, 8f, 999f),
      "round-large" to params(ContainerInfo.CONTAINER_TYPE_LARGE, 160f, 136f, 35f, 16f, 999f),
    )

  private class Shown(val bytes: ByteArray, val params: WearWidgetParams)

  private var shown by mutableStateOf<Shown?>(null)
  private var contentSet = false

  private fun render(file: File, bytes: ByteArray, params: WearWidgetParams): Bitmap {
    if (!contentSet) {
      compose.setContent {
        val current = shown ?: return@setContent
        val frame =
          Modifier.size(
            (current.params.widthDp + 2 * current.params.horizontalPaddingDp).dp,
            (current.params.heightDp + 2 * current.params.verticalPaddingDp).dp,
          )
        Box(frame.testTag("widget")) {
          key(current) {
            HaRcComposePlayer(
              documentBytes = current.bytes,
              modifier = frame,
              fallback = { error("${file.name} must play") },
            )
          }
        }
      }
      contentSet = true
    }
    compose.runOnIdle { shown = Shown(bytes, params) }
    compose.waitForIdle()
    val image = compose.onNodeWithTag("widget").captureToImage().asAndroidBitmap()
    file.parentFile.mkdirs()
    file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return image
  }

  /**
   * The rows above and below [image]'s icon — the lit bulb's amber, which nothing else in a tile
   * draws (the corners outside a round container are white) — as fractions of its height.
   */
  private fun iconMargins(image: Bitmap): Pair<Float, Float>? {
    fun amber(y: Int) =
      (0 until image.width).any { x ->
        val pixel = image.getPixel(x, y)
        val r = pixel shr 16 and 0xff
        val g = pixel shr 8 and 0xff
        val b = pixel and 0xff
        r > 200 && g in 120..220 && b < 100
      }
    val rows = (0 until image.height).filter(::amber)
    // No icon at all is a failure, not centred content.
    if (rows.isEmpty()) return null
    return rows.first().toFloat() / image.height to
      (image.height - 1 - rows.last()).toFloat() / image.height
  }

  @Test
  fun aPhoneCardDesignPlaysInEveryWearContainer() {
    val out = File("build/wear-reframe")
    val problems = mutableListOf<String>()
    for ((name, card) in cards) for (cardStyle in cardStyles) {
      val key =
        assertNotNull(
          CardDesignTemplates.keyFor(card, snapshot, style, dark = true, cardStyle = cardStyle),
          "$name: no design key",
        )
      val phone = CardDesignTemplates.capture(context, key).bytes
      val template = CardDesignWearTemplate.template(phone)
      if (template == null) {
        problems += "$name/${cardStyle.id}: no card box to reframe"
        continue
      }
      val design = CardDesignTemplates.designCard(card, snapshot, registry)
      for ((host, params) in hosts) {
        val tag = "$name-${cardStyle.id}-$host"
        val widget = runBlocking { WearWidgetTemplate.instantiate(context, template, params) }
        if (widget == null) {
          problems += "$tag: not playable as a Wear widget"
          continue
        }
        val playback = CardDesignTemplates.playback(design, widget.rcDocument, registry)
        val baked = LauncherDesigns.bakeValues(widget.rcDocument, playback, snapshot)
        if (baked == null) {
          problems += "$tag: values can't be baked"
          continue
        }
        val reframed = render(out.resolve("$tag-reframed.png"), baked, params)
        // A Large container is taller than the phone card: the card's content sits in its middle.
        if (
          name == "tile" &&
            cardStyle == HaUberTheme.Classic &&
            params.containerType == ContainerInfo.CONTAINER_TYPE_LARGE
        ) {
          val margins = iconMargins(reframed)
          if (margins == null) {
            problems += "$tag: no icon drawn"
          } else if (abs(margins.first - margins.second) > CENTRED_TOLERANCE) {
            val (top, bottom) = margins
            problems +=
              "$tag: content not centred (top ${"%.2f".format(top)}, bottom ${"%.2f".format(bottom)})"
          }
        }
        // The watch's own capture of the same card, for comparison.
        val own = runBlocking {
          slotWidgetData(registry, colors, style, card, snapshot, cardStyle)
            .captureRawContent(context, params, false)
            .rcDocument
        }
        render(out.resolve("$tag-own.png"), own, params)
      }
    }
    assertEquals(emptyList(), problems)
  }

  /**
   * A style that frames its card (LCARS, Catppuccin, Obsidian) pads it twice: a decorative inset,
   * then the card's own padding. Only the card's own becomes the container's, so the watch's insets
   * apply once.
   */
  @Test
  fun theContainerPaddingIsAppliedOnce() {
    val tile = cards.getValue("tile")
    for (id in listOf("classic", "lcars", "catppuccin", "obsidian")) {
      val cardStyle = HaUberTheme.byId(id)
      val key =
        assertNotNull(
          CardDesignTemplates.keyFor(tile, snapshot, style, dark = true, cardStyle = cardStyle),
          "$id: no design key",
        )
      val template =
        assertNotNull(
          CardDesignWearTemplate.template(CardDesignTemplates.capture(context, key).bytes),
          "$id: no card box to reframe",
        )
      val operations = RcDocumentCodec.decode(template, RcWireLimits()).operations
      // The named horizontal padding, then the pixel expression the paddings read.
      val named =
        operations
          .filterIsInstance<RcNamedVariable>()
          .single { it.name.endsWith(WearWidgetTemplate.HORIZONTAL_PADDING) }
          .id
      val px =
        operations
          .filterIsInstance<RcFloatExpression>()
          .single { it.expression.firstOrNull()?.referencedId == named }
          .id
      val containerPaddings = operations.count {
        it is RcPaddingModifier && it.left.referencedId == px
      }
      assertEquals(1, containerPaddings, "$id: container paddings")
    }
  }

  private companion object {
    /** How far apart the blank bands above and below centred content may be, of the height. */
    const val CENTRED_TOLERANCE = 0.08f
  }
}
