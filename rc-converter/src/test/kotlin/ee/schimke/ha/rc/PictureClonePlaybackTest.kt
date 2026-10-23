package ee.schimke.ha.rc

import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.player.core.platform.BitmapLoader
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import ee.schimke.composeai.rcplayer.compose.RcImageLoader
import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.ha.rc.components.RemoteHaImageUrl
import ee.schimke.ha.rc.components.enableRemoteImageUrls
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A template card's new picture is a clone of the same bytes ([cloneForPlayer]) played in the
 * card's one player: it must still fetch its picture again, or a camera card keeps its first frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PictureClonePlaybackTest {

  @get:Rule val compose = createComposeRule()

  private class CountingLoader : BitmapLoader, RcImageLoader {
    val loads = AtomicInteger()
    private val image =
      android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        .asImageBitmap()

    override fun loadBitmap(name: String): InputStream = error("the Compose player loads")

    override suspend fun load(bitmap: RcBitmapData): ImageBitmap {
      loads.incrementAndGet()
      return image
    }
  }

  @Test
  fun aPictureCloneFetchesItsPictureAgainInTheSamePlayer() {
    enableRemoteImageUrls()
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val template = runBlocking {
      captureSingleRemoteDocument(context = context, profile = androidXExperimental) {
          RemoteHaImageUrl(
            url = "rc-slot://picture",
            contentDescription = "camera".rs,
            modifier = RemoteModifier.size(64.rdp),
          )
        }
        .bytes
    }
    val loader = CountingLoader()
    var bytes by mutableStateOf(template)

    compose.setContent {
      HaRcComposePlayer(
        documentBytes = bytes,
        bitmapLoader = loader,
        fallback = { error("the document plays in the Compose player") },
      )
    }
    compose.waitForIdle()
    assertEquals(1, loader.loads.get())

    // The next frame: the same template, cloned for its new picture.
    bytes = cloneForPlayer(template)
    compose.waitForIdle()
    assertEquals(2, loader.loads.get())
  }
}
