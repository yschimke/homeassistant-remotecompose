package ee.schimke.terrazzo.previews

import android.app.Application
import ee.schimke.ha.rc.image.CoilBitmapLoader
import ee.schimke.terrazzo.core.network.LanConnectionPolicy
import ee.schimke.terrazzo.core.network.RemoteUrlStore
import ee.schimke.terrazzo.core.session.DemoData
import ee.schimke.terrazzo.image.HaImageStack
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The screen previews' demo pictures are in memory before the first frame, under the key the card
 * loader's synchronous lookup uses, so a camera tile never depends on an async fetch beating the
 * render's capture.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class PreviewDemoImagesTest {

  private val context: Application = RuntimeEnvironment.getApplication()

  @Test
  fun everyDemoPictureHitsTheCardLoadersFirstLookup() {
    val stack = HaImageStack(context, LanConnectionPolicy(context), RemoteUrlStore(context))
    val snapshot = DemoData.snapshot(nowMs = 0L)
    val held = prewarmDemoPictures(stack, snapshot)

    // What the dashboard's cards ask for: each entity's server-relative `entity_picture`, which
    // CoilBitmapLoader resolves against the session's base URL.
    val pictures =
      snapshot.states.values
        .mapNotNull { (it.attributes["entity_picture"] as? JsonPrimitive)?.contentOrNull }
        .filter { it.startsWith('/') }
        .toSet()
    assertTrue(pictures.count { it.startsWith("/api/camera_proxy/") } >= 3, "$pictures")
    assertEquals(pictures.size, held.size)

    // Built as CardSlot builds it. A miss would answer with no bytes (and enqueue a fetch).
    val loader =
      CoilBitmapLoader(context, imageLoader = stack.imageLoader, baseUrl = DemoData.BASE_URL)
    for (picture in pictures) {
      val bytes = loader.loadBitmap(picture).use { it.readBytes() }
      assertTrue(bytes.isNotEmpty(), "memory-cache miss for $picture")
    }
  }

  @Test
  fun leavesPicturesOffTheDemoHostAlone() {
    val snapshot = DemoData.snapshot(nowMs = 0L)
    val urls = demoPictureUrls(snapshot)
    assertTrue(urls.isNotEmpty())
    assertTrue(urls.all { it.startsWith(DemoData.BASE_URL + "/") }, "$urls")
  }
}
