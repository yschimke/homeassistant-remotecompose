package ee.schimke.terrazzo.widget

import android.app.Application
import coil3.ImageLoader
import coil3.request.Options
import coil3.toUri
import ee.schimke.terrazzo.core.network.LanConnectionPolicy
import ee.schimke.terrazzo.core.session.DemoData
import ee.schimke.terrazzo.image.DemoImageFetcher
import ee.schimke.terrazzo.image.DemoImages
import ee.schimke.terrazzo.image.HaImageStack
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A widget's server-relative pictures resolve against its own server, never the foreground's. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WidgetImagesTargetTest {

  private val context: Application = RuntimeEnvironment.getApplication()
  private val stack = HaImageStack(context, LanConnectionPolicy(context))
  private val demoFetcher = DemoImageFetcher.Factory()
  private val options = Options(context)
  private val imageLoader = ImageLoader.Builder(context).build()

  @Test
  fun realWidgetKeepsItsOwnServerWhileTheAppIsInDemoMode() {
    // The foreground session is the demo: the stack's base is the demo host.
    stack.setAuth(DemoData.BASE_URL, null)
    stack.ensureBaseUrl(REAL_BASE)
    // Left relative, the stack would pin the real widget's picture to the demo host, where the
    // demo fetcher answers with a stand-in.
    assertTrue(DemoImages.isDemoUrl(stack.resolveAgainstBase(CAMERA)))

    val resolved = stack.resolveAgainstBase(WidgetImages.target(REAL_BASE, CAMERA))

    assertEquals("$REAL_BASE$CAMERA", resolved)
    assertNull(demoFetcher.create(resolved.toUri(), options, imageLoader))
  }

  @Test
  fun demoWidgetKeepsTheDemoServerWhileTheAppShowsARealOne() {
    stack.setAuth(REAL_BASE, "token")
    stack.ensureBaseUrl(DemoData.BASE_URL)

    val resolved = stack.resolveAgainstBase(WidgetImages.target(DemoData.BASE_URL, CAMERA))

    assertEquals(DemoData.BASE_URL + CAMERA, resolved)
    assertNotNull(demoFetcher.create(resolved.toUri(), options, imageLoader))
  }

  @Test
  fun absoluteUrlsPassThrough() {
    val external = "https://cdn.example.com/cover.png"
    assertEquals(external, WidgetImages.target(REAL_BASE, external))
    assertEquals("$REAL_BASE$CAMERA", WidgetImages.target("$REAL_BASE/", CAMERA))
  }

  private companion object {
    const val REAL_BASE = "https://ha.example.com:8123"
    const val CAMERA = "/api/camera_proxy/camera.driveway?token=abc"
  }
}
