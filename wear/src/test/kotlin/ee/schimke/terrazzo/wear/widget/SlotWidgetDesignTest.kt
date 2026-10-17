@file:Suppress("RestrictedApi", "RestrictedApiAndroidX")

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
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.glance.wear.WearWidgetData
import androidx.glance.wear.WearWidgetDocument
import androidx.glance.wear.core.ContainerInfo
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.core.WidgetInstanceId
import androidx.wear.compose.remote.material3.RemoteColorScheme
import ee.schimke.composeai.rcplayer.edit.RcTargetProfiles
import ee.schimke.composeai.rcplayer.edit.bitmapUrls
import ee.schimke.composeai.rcplayer.edit.namedVariables
import ee.schimke.composeai.rcplayer.edit.validateFor
import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CachedCardPreview
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.CardTemplatePlayback
import ee.schimke.ha.rc.HaRcComposePlayer
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.terrazzo.wear.ui.terrazzoWearColorScheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
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

/**
 * Wear widgets from card designs: Glance Wear captures the slot's template, and the card's values
 * are baked in. The baked widget must hold no slot bindings and draw what a direct capture draws.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SlotWidgetDesignTest {

  @get:Rule val compose = createComposeRule()

  private val registry = defaultRegistry()
  private val style = ThemeStyle.entries.first()
  private val colors = RemoteColorScheme(terrazzoWearColorScheme(style))

  private val params =
    WearWidgetParams(
      instanceId = WidgetInstanceId("widgets", 1),
      containerType = ContainerInfo.CONTAINER_TYPE_LARGE,
      widthDp = 200f,
      heightDp = 112f,
      verticalPaddingDp = 8f,
      horizontalPaddingDp = 8f,
      cornerRadiusDp = 26f,
    )

  private fun card(type: String, entity: String) =
    CardConfig(
      type = type,
      raw =
        buildJsonObject {
          put("type", type)
          put("entity", entity)
        },
    )

  private fun entity(id: String, state: String, vararg attrs: Pair<String, String>) =
    EntityState(id, state, JsonObject(attrs.associate { (k, v) -> k to JsonPrimitive(v) }))

  private fun data(
    card: CardConfig,
    snapshot: HaSnapshot,
    fetchImage: (String) -> ByteArray? = { null },
  ): WearWidgetData =
    slotWidgetData(registry, colors, style, card, snapshot, 0, fetchImage = fetchImage)

  private val cameraUrl = "/api/camera_proxy/camera.driveway?token=abc"

  private val camera =
    HaSnapshot(
      states =
        mapOf(
          "camera.driveway" to
            entity(
              "camera.driveway",
              "idle",
              "friendly_name" to "Driveway",
              "entity_picture" to cameraUrl,
            )
        )
    )

  /** A solid yellow PNG: the picture the phone synced. */
  private val yellowPng: ByteArray =
    Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).let { bitmap ->
      android.graphics.Canvas(bitmap).drawColor(android.graphics.Color.YELLOW)
      java.io
        .ByteArrayOutputStream()
        .also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        .toByteArray()
    }

  private fun capture(data: WearWidgetData): ByteArray = runBlocking {
    data.captureRawContent(RuntimeEnvironment.getApplication(), params, false).rcDocument
  }

  private fun assertBaked(
    card: CardConfig,
    snapshot: HaSnapshot,
    slot: String,
    text: String,
    fetchImage: (String) -> ByteArray? = { null },
  ): ByteArray {
    val data = data(card, snapshot, fetchImage)
    assertFalse(data is WearWidgetDocument, "${card.type} renders from its design")
    val bytes = capture(data)
    val document = RcDocumentCodec.decode(bytes, RcWireLimits())
    assertEquals(
      emptyList(),
      // A slot picture keeps its name with the embedded bytes as its value (bitmapUrls below):
      // a host may swap it, but has nothing to supply.
      document.namedVariables
        .map { it.name }
        .filter { slot in it && !it.endsWith(".entity_picture") },
      "every slot binding is baked",
    )
    assertEquals(emptyList(), document.bitmapUrls, "a Wear widget names no image URL")
    assertEquals(
      emptyList(),
      document.validateFor(RcTargetProfiles.WEAR_WIDGETS),
      "the baked widget stays in the Wear widget op set",
    )
    assertTrue(text in String(bytes, Charsets.ISO_8859_1), "the value is baked in")
    return bytes
  }

  @Test
  fun bakingLeavesTheCachedTemplateUnchanged() {
    val card = card("picture-entity", "camera.driveway")
    val snapshot =
      HaSnapshot(
        states =
          mapOf(
            "camera.driveway" to entity("camera.driveway", "idle", "friendly_name" to "Driveway")
          )
      )
    val first = capture(data(card, snapshot))
    val second = capture(data(card, snapshot))
    assertTrue(
      first.contentEquals(second),
      "a second bake of the cached template matches the first",
    )
  }

  @Test
  fun tileBakesItsValues() {
    assertBaked(
      card("tile", "light.kitchen"),
      HaSnapshot(
        states =
          mapOf("light.kitchen" to entity("light.kitchen", "on", "friendly_name" to "Kitchen"))
      ),
      "light.s0",
      "Kitchen",
    )
  }

  @Test
  fun pictureCardEmbedsItsSyncedPicture() {
    val fetched = mutableListOf<String>()
    val bytes =
      assertBaked(card("picture-entity", "camera.driveway"), camera, "camera.s0", "Driveway") {
        fetched += it
        if (it == cameraUrl) yellowPng else null
      }
    // Only the card's own picture was asked for, and the widget carries it inline.
    assertEquals(listOf(cameraUrl), fetched)
    val bitmaps =
      RcDocumentCodec.decode(bytes, RcWireLimits()).operations.filterIsInstance<RcBitmapData>()
    assertTrue(
      bitmaps.any {
        it.encoding == RcBitmapData.ENCODING_INLINE &&
          it.width == 64 &&
          it.height == 48 &&
          it.data.contentEquals(yellowPng)
      },
      "the synced PNG is embedded",
    )
    // And drawn: played as the watch face preview plays a widget, at its container's size.
    compose.setContent {
      Box(Modifier.size(params.widthDp.dp, params.heightDp.dp).testTag("widget")) {
        HaRcComposePlayer(
          documentBytes = bytes,
          modifier = Modifier.size(params.widthDp.dp, params.heightDp.dp),
          fallback = { error("the baked widget plays in the Compose player") },
        )
      }
    }
    compose.waitForIdle()
    val pixels = pixels("synced-picture")
    var yellow = 0
    for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
      val c = pixels[x, y]
      if (c.red > 0.9f && c.green > 0.9f && c.blue < 0.2f) yellow++
    }
    assertTrue(
      yellow > pixels.width * pixels.height / 3,
      "the synced picture fills the card: $yellow of ${pixels.width * pixels.height} pixels",
    )
  }

  @Test
  fun pictureCardWithoutASyncedPictureShowsItsIcon() {
    // Not synced yet: the direct capture, which draws the card's icon.
    val bytes = capture(data(card("picture-entity", "camera.driveway"), camera))
    val document = RcDocumentCodec.decode(bytes, RcWireLimits())
    assertEquals(emptyList(), document.bitmapUrls, "a Wear widget names no image URL")
    assertTrue("Driveway" in String(bytes, Charsets.ISO_8859_1))
  }

  @Test
  fun bakedTileMatchesADirectCapture() =
    assertParity(
      "tile",
      card("tile", "sensor.temp"),
      HaSnapshot(
        states =
          mapOf(
            "sensor.temp" to
              entity(
                "sensor.temp",
                "21.5",
                "friendly_name" to "Living room",
                "device_class" to "temperature",
                "unit_of_measurement" to "°C",
              )
          )
      ),
    )

  /** A picture card whose entity has no picture: its icon design, baked. */
  @Test
  fun bakedPicturelessCardMatchesADirectCapture() =
    assertParity(
      "picture-entity",
      card("picture-entity", "camera.driveway"),
      HaSnapshot(
        states =
          mapOf(
            "camera.driveway" to entity("camera.driveway", "idle", "friendly_name" to "Driveway")
          )
      ),
    )

  /**
   * An entities slot with a light that is on: the baked template must draw it on, as the direct
   * capture does, not in the off accent of the template's placeholder entities.
   */
  @Test
  fun bakedEntitiesShowTheLiveOnOffState() =
    assertPlayedParity(
      "entities",
      CardConfig(
        "entities",
        kotlinx.serialization.json.Json.parseToJsonElement(
          """{"type":"entities","title":"Living Room","entities":["light.lamp","switch.coffee"]}"""
        ) as JsonObject,
      ),
      HaSnapshot(
        states =
          mapOf(
            "light.lamp" to entity("light.lamp", "on", "friendly_name" to "Office lamp"),
            "switch.coffee" to entity("switch.coffee", "on", "friendly_name" to "Coffee maker"),
          )
      ),
    )

  /**
   * [assertParity] played as the watch face plays a widget, at its container's size: a card that
   * fills its slot (the entities rows share the slot's height) draws nothing in a wrap-height
   * player.
   */
  private fun assertPlayedParity(name: String, card: CardConfig, snapshot: HaSnapshot) {
    val data = data(card, snapshot)
    assertFalse(data is WearWidgetDocument, "${card.type} renders from its design")
    val direct = capture(slotDocumentForTest(card, snapshot))
    val baked = capture(data)
    var bytes by mutableStateOf(direct)
    compose.setContent {
      Box(Modifier.size(params.widthDp.dp, params.heightDp.dp).testTag("widget")) {
        key(bytes) {
          HaRcComposePlayer(
            documentBytes = bytes,
            modifier = Modifier.size(params.widthDp.dp, params.heightDp.dp),
            fallback = { error("the widget plays in the Compose player") },
          )
        }
      }
    }
    compose.waitForIdle()
    val directPixels = pixels("direct-$name")
    compose.runOnIdle { bytes = baked }
    compose.waitForIdle()
    val bakedPixels = pixels("baked-$name")
    var differing = 0
    for (y in 0 until directPixels.height) for (x in 0 until directPixels.width) {
      if (directPixels[x, y] != bakedPixels[x, y]) differing++
    }
    assertEquals(0, differing, "$name: pixels differing between a direct and a baked Wear widget")
  }

  private fun assertParity(name: String, card: CardConfig, snapshot: HaSnapshot) {
    val direct = capture(slotDocumentForTest(card, snapshot))
    val baked = capture(data(card, snapshot))
    var bytes by mutableStateOf(direct)
    compose.setContent {
      Box(Modifier.size(params.widthDp.dp, params.heightDp.dp).testTag("widget")) {
        // A fresh player per document: a player handed a new document in place keeps the old
        // one's text state, which a widget host never does.
        key(bytes) {
          CachedCardPreview(
            cacheKey = "wear-$name-${bytes.contentHashCode()}",
            template =
              CardTemplatePlayback(
                bytes,
                { _, _ -> CardSnapshotBindings(emptyMap(), emptyMap(), emptyMap(), emptyMap()) },
                HaAction.None,
              ),
          ) {}
        }
      }
    }
    compose.waitForIdle()
    val directPixels = pixels("direct-$name")
    compose.runOnIdle { bytes = baked }
    compose.waitForIdle()
    val bakedPixels = pixels("baked-$name")
    var differing = 0
    for (y in 0 until directPixels.height) for (x in 0 until directPixels.width) {
      if (directPixels[x, y] != bakedPixels[x, y]) differing++
    }
    assertEquals(0, differing, "$name: pixels differing between a direct and a baked Wear widget")
  }

  private fun slotDocumentForTest(card: CardConfig, snapshot: HaSnapshot): WearWidgetData =
    slotDirectDocument(registry, colors, card, snapshot, slotIndex = 0)

  private fun pixels(name: String) =
    compose
      .onNodeWithTag("widget")
      .captureToImage()
      .also { image ->
        val dir = File("build/wear-designs").also { it.mkdirs() }
        dir.resolve("$name.png").outputStream().use {
          image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
      }
      .toPixelMap()
}
