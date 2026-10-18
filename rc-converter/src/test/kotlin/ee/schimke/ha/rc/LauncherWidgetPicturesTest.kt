package ee.schimke.ha.rc

import android.graphics.Bitmap
import android.graphics.Color
import ee.schimke.composeai.rcplayer.edit.bitmapUrls
import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.enableRemoteImageUrls
import java.io.ByteArrayOutputStream
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherWidgetPicturesTest {
  private val context = RuntimeEnvironment.getApplication()
  private val registry = defaultRegistry()
  private val url = "/api/camera_proxy/camera.driveway?token=test-only"
  private val snapshot =
    HaSnapshot(
      states =
        mapOf(
          "camera.driveway" to
            EntityState(
              "camera.driveway",
              "idle",
              buildJsonObject {
                put("friendly_name", "Driveway")
                put("entity_picture", url)
              },
            )
        )
    )
  private val camera =
    card(
      """{"type":"picture-entity","entity":"camera.driveway","camera_image":"camera.driveway","camera_view":"auto","aspect_ratio":"16:9"}"""
    )

  private fun card(raw: String): CardConfig {
    val json = Json.parseToJsonElement(raw).jsonObject
    return CardConfig(json.getValue("type").jsonPrimitive.content, json)
  }

  private fun png(color: Int): ByteArray =
    Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).let { bitmap ->
      bitmap.eraseColor(color)
      ByteArrayOutputStream()
        .also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        .toByteArray()
        .also { bitmap.recycle() }
    }

  private fun capture(card: CardConfig, width: Int, height: Int, fetch: (String) -> ByteArray?) =
    captureLauncherWidgetDocument(
      context,
      card,
      snapshot,
      registry,
      width,
      height,
      160,
      widgetsProfile,
      dark = false,
      fetchImage = fetch,
    )

  @Test
  fun realCameraConfigsAndNestedCardsEmbedOnlyTheirOwnImageAtEverySize() {
    enableRemoteImageUrls()
    assertNull(
      LauncherDesigns.keyFor(camera, snapshot, 288, 252, false, false, registry),
      "Bundled camera configs bypass the template image baker",
    )
    val nested = card("""{"type":"vertical-stack","cards":[${camera.raw}]}""")
    for (card in listOf(camera, nested)) {
      for ((width, height) in listOf(144 to 168, 288 to 252, 432 to 336)) {
        val fetched = mutableListOf<String>()
        val image = png(Color.MAGENTA)
        val capture =
          capture(card, width, height) {
            fetched += it
            image
          }
        assertEquals(listOf(url), fetched)
        val document = RcDocumentCodec.decode(platformWidgetDocument(capture.bytes), RcWireLimits())
        assertTrue(document.bitmapUrls.isEmpty(), "Launcher must never fetch a camera URL itself")
        val bitmap = document.operations.filterIsInstance<RcBitmapData>().single()
        assertEquals(RcBitmapData.ENCODING_INLINE, bitmap.encoding)
        assertEquals(RcBitmapData.TYPE_PNG_8888, bitmap.type)
        assertEquals(64, bitmap.width)
        assertEquals(32, bitmap.height)
        assertContentEquals(image, bitmap.data)
        assertTrue(capture.actions.isNotEmpty(), "Image handling must preserve camera taps")
        // A new capture must contain fresh bytes, even if the camera URL did not change.
        val nextImage = png(Color.CYAN)
        val next = capture(card, width, height) { nextImage }
        val nextBitmap =
          RcDocumentCodec.decode(next.bytes, RcWireLimits())
            .operations
            .filterIsInstance<RcBitmapData>()
            .single()
        assertContentEquals(nextImage, nextBitmap.data)
      }
    }
  }

  @Test
  fun unavailableAndInvalidImagesUseTheSupportedFallbackWithoutAUrl() {
    enableRemoteImageUrls()
    for (result in listOf(null, byteArrayOf(1, 2, 3))) {
      val captured = capture(camera, 288, 252) { result }
      val document = RcDocumentCodec.decode(platformWidgetDocument(captured.bytes), RcWireLimits())
      assertTrue(document.bitmapUrls.isEmpty())
      assertTrue(document.operations.filterIsInstance<RcBitmapData>().isEmpty())
      assertTrue(captured.bytes.toString(Charsets.ISO_8859_1).contains("Driveway"))
      assertFalse(captured.bytes.toString(Charsets.ISO_8859_1).contains("test-only"))
      assertTrue(captured.actions.isNotEmpty())
    }
  }
}
