package ee.schimke.terrazzo.image

import android.app.Application
import android.graphics.Bitmap
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.toUri
import ee.schimke.terrazzo.core.session.DemoData
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class DemoImageFetcherTest {

  private val context: Application = RuntimeEnvironment.getApplication()
  private val imageLoader = ImageLoader.Builder(context).build()
  private val options = Options(context)
  private val factory = DemoImageFetcher.Factory()

  private fun demo(path: String) = DemoData.BASE_URL + path

  private fun render(url: String): Bitmap {
    var bitmap: Bitmap? = null
    runTest {
      val fetcher = assertNotNull(factory.create(url.toUri(), options, imageLoader), url)
      val result = assertNotNull(fetcher.fetch(), url) as ImageFetchResult
      bitmap = (result.image as BitmapImage).bitmap
    }
    return bitmap!!
  }

  @Test
  fun answersForEveryKindOfDemoPicture() {
    val camera = render(demo(DemoData.cameraPictureUrl("camera.back_door_camera", NOW)))
    assertEquals(640, camera.width)
    assertEquals(360, camera.height)
    val cover = render(demo("/api/image_proxy/image.p2s_sn2_cover_image?token=REDACTED"))
    assertEquals(512, cover.width)
    val pick = render(demo("/api/image_proxy/image.p2s_sn2_pick_image?token=REDACTED"))
    assertEquals(512, pick.width)
    val icon = render(demo("/api/hassio/addons/XXXXXXXX_unifi/icon"))
    assertEquals(256, icon.width)
    assertTrue(camera.distinctColours() > 50, "a camera frame is a scene, not a flat fill")
    assertTrue(cover.distinctColours() > 10, "a cover image draws a plate and a print")
  }

  @Test
  fun doesNotAnswerForRealHosts() {
    for (url in
      listOf(
        "https://homeassistant.local:8123/api/camera_proxy/camera.back_door_camera?token=abc",
        "http://192.168.1.10:8123/api/image_proxy/image.p2s_sn2_cover_image?token=REDACTED",
        "https://brands.home-assistant.io/_/meshcore/icon.png",
        "demo://elsewhere/api/camera_proxy/camera.x",
        "/api/camera_proxy/camera.back_door_camera?token=abc",
      )) {
      assertNull(factory.create(url.toUri(), options, imageLoader), url)
    }
  }

  @Test
  fun cameraFramesMoveButAreDeterministic() {
    val url = demo(DemoData.cameraPictureUrl("camera.a1mini_sn1_camera", NOW))
    val next =
      demo(
        DemoData.cameraPictureUrl("camera.a1mini_sn1_camera", NOW + DemoData.CAMERA_FRAME_MILLIS)
      )
    assertTrue(render(url).sameAs(render(url)), "the same frame draws the same pixels")
    assertFalse(render(url).sameAs(render(next)), "the next frame differs")
  }

  @Test
  fun avatarInitialSkipsRedactedPrefixesAndGenericSegments() {
    assertEquals("unifi", DemoImages.avatarName("/api/hassio/addons/XXXXXXXX_unifi/icon"))
    assertEquals("room", DemoImages.avatarName("/api/meshcore/static/room_server.svg"))
    assertEquals("abc123", DemoImages.avatarName("/api/image/serve/abc123/512x512"))
    assertEquals("BACK DOOR", DemoImages.cameraLabel("camera.back_door_camera"))
    assertEquals(
      "G5 TURRET",
      DemoImages.cameraLabel("camera.g5_turret_ultra_high_resolution_channel"),
    )
  }

  /** `-PdemoImagesOut=<dir>`: write one of each picture as a PNG to look at. */
  @Test
  fun writeSamples() {
    val out = System.getProperty("demoImagesOut") ?: return
    val dir = File(out).apply { mkdirs() }
    val samples =
      mapOf(
        "camera_yard" to DemoData.cameraPictureUrl("camera.back_door_camera", NOW),
        "camera_yard_night" to
          DemoData.cameraPictureUrl("camera.g4_instant_high_resolution_channel", NIGHT),
        "camera_printer" to DemoData.cameraPictureUrl("camera.a1mini_sn1_camera", NOW),
        "cover" to "/api/image_proxy/image.p2s_sn2_cover_image?token=REDACTED",
        "cover_a1" to "/api/image_proxy/image.a1mini_sn1_cover_image?token=REDACTED",
        "pick" to "/api/image_proxy/image.p2s_sn2_pick_image?token=REDACTED",
        "avatar" to "/api/hassio/addons/XXXXXXXX_unifi/icon",
      )
    for ((name, path) in samples) {
      File(dir, "$name.png").outputStream().use {
        render(demo(path)).compress(Bitmap.CompressFormat.PNG, 100, it)
      }
    }
  }

  private fun Bitmap.distinctColours(): Int {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    return pixels.toSet().size
  }

  private companion object {
    /** 2026-04-30 12:00 UTC: daylight for the OSD in most zones. */
    const val NOW = 1_777_550_400_000L
    const val NIGHT = NOW + 12 * 3_600_000L
  }
}
