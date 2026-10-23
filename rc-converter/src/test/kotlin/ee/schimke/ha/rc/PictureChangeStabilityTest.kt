package ee.schimke.ha.rc

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.player.core.platform.BitmapLoader
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.rcplayer.compose.RcImageLoader
import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.haThemeFor
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A card whose picture changes (a camera's next frame: a new URL, so a new document) keeps its size
 * and its old picture until the new one arrives. A fresh player per document blanked the picture
 * for a few frames on every change; reusing the player without care collapsed the card instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PictureChangeStabilityTest {

  @get:Rule val compose = createComposeRule()

  /** Serves [FIRST] straight away (and from memory); [SECOND] only once [next] completes. */
  private class TickLoader : BitmapLoader, RcImageLoader {
    val next = CompletableDeferred<ImageBitmap?>()

    override fun loadBitmap(name: String) = ByteArrayInputStream(ByteArray(0))

    override suspend fun load(bitmap: RcBitmapData): ImageBitmap? =
      if (bitmap.data.decodeToString() == SECOND) next.await() else cached(bitmap)

    override fun cached(bitmap: RcBitmapData): ImageBitmap? =
      if (bitmap.data.decodeToString() == FIRST) solid(android.graphics.Color.MAGENTA) else null
  }

  @Test
  fun aChangedPictureKeepsTheCardsSizeAndItsOldPictureUntilTheNewOneLoads() {
    val loader = TickLoader()
    var url by mutableStateOf(FIRST)
    val registry = defaultRegistry()
    val theme = haThemeFor(ThemeStyle.TerrazzoHome, false)
    // The dashboard's camera card: a picture-entity whose picture URL is the camera's
    // `entity_picture`, which a camera's next frame changes.
    val card =
      Json.parseToJsonElement(
          """{"type":"picture-entity","entity":"camera.driveway","camera_image":"camera.driveway","camera_view":"auto","name":"Driveway","aspect_ratio":"16:9"}"""
        )
        .jsonObject
        .let { CardConfig("picture-entity", it) }
    compose.setContent {
      val snapshot =
        HaSnapshot(
          states =
            mapOf(
              "camera.driveway" to
                EntityState(
                  "camera.driveway",
                  "recording",
                  buildJsonObject {
                    put("friendly_name", "Driveway")
                    put("entity_picture", url)
                  },
                )
            )
        )
      Box(Modifier.background(Color.White).width(320.dp).testTag("card")) {
        CachedCardPreview(
          cacheKey = "camera",
          profile = androidXExperimentalWrap,
          card = card,
          snapshot = snapshot,
          bitmapLoader = loader,
        ) {
          ProvideCardRegistry(registry) {
            ProvideHaTheme(theme) { RenderChild(card, snapshot, RemoteModifier.fillMaxWidth()) }
          }
        }
      }
    }
    compose.waitForIdle()
    val cardHeight = cardHeight()
    assertEquals(true, cardHeight > 100.dp, "the card has its height ($cardHeight)")
    assertEquals(true, magenta() > 1000, "the first picture is drawn")

    compose.mainClock.autoAdvance = false
    url = SECOND
    // Longer than any of the card's 300 ms layout animations.
    repeat(30) { frame ->
      compose.mainClock.advanceTimeByFrame()
      assertEquals(
        cardHeight,
        cardHeight(),
        "frame $frame after the change: the card keeps its height",
      )
      assertEquals(true, magenta() > 1000, "frame $frame: the old picture is still drawn")
    }

    loader.next.complete(solid(android.graphics.Color.CYAN))
    compose.mainClock.autoAdvance = true
    compose.waitForIdle()
    assertEquals(0, magenta(), "the new picture replaced the old one")
  }

  private fun cardHeight() =
    compose.onNodeWithTag("card").getUnclippedBoundsInRoot().let { it.bottom - it.top }

  private fun magenta(): Int {
    val pixels = compose.onNodeWithTag("card").captureToImage().toPixelMap()
    var count = 0
    for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
      val c = pixels[x, y]
      if (c.red > 0.9f && c.blue > 0.9f && c.green < 0.1f) count++
    }
    return count
  }

  private companion object {
    const val FIRST = "https://example.com/camera.jpg?frame=1"
    const val SECOND = "https://example.com/camera.jpg?frame=2"

    fun solid(color: Int): ImageBitmap =
      Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        .also { Canvas(it).drawColor(color) }
        .asImageBitmap()
  }
}
