package ee.schimke.ha.rc

import androidx.compose.remote.player.core.platform.BitmapLoader
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import ee.schimke.composeai.rcplayer.compose.RcImageLoader
import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A template's pictures reach the Compose player through the host's own suspending loader. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SlotResolvingImageLoaderTest {

  private val image =
    android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
      .asImageBitmap()

  private class Host(val image: ImageBitmap) : BitmapLoader, RcImageLoader {
    val loaded = mutableListOf<String>()
    val cachedFor = mutableListOf<String>()

    override fun loadBitmap(name: String): InputStream = error("the blocking path isn't used")

    override suspend fun load(bitmap: RcBitmapData): ImageBitmap {
      loaded += bitmap.data.decodeToString()
      return image
    }

    override fun cached(bitmap: RcBitmapData): ImageBitmap {
      cachedFor += bitmap.data.decodeToString()
      return image
    }
  }

  private fun slot(url: String) =
    RcBitmapData(1, 64, 64, RcBitmapData.TYPE_PNG, RcBitmapData.ENCODING_URL, url.toByteArray())

  @Test
  fun aSlotPictureLoadsItsResolvedUrlThroughTheHostsLoader() {
    val host = Host(image)
    val loader =
      SlotResolvingImageLoader(host) {
        if (it == "rc-slot://picture") "/api/camera_proxy/c" else null
      }

    assertSame(image, loader.cached(slot("rc-slot://picture")))
    assertSame(image, runBlocking { loader.load(slot("rc-slot://picture")) })
    assertEquals(listOf("/api/camera_proxy/c"), host.cachedFor)
    assertEquals(listOf("/api/camera_proxy/c"), host.loaded)

    // Nothing the card's entities name: no fetch.
    assertNull(runBlocking { loader.load(slot("https://elsewhere.example/x.png")) })
    assertEquals(1, host.loaded.size)
  }
}
