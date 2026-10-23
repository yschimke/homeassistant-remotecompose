package ee.schimke.terrazzo.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveImageCacheInterceptorTest {

  @Test
  fun liveImagesAreKeyedWithoutTheirRotatingToken() {
    assertEquals(
      "http://ha.local:8123/api/camera_proxy/camera.yard",
      liveImageDiskKey("http://ha.local:8123/api/camera_proxy/camera.yard?token=abc"),
    )
    // Two rotations of one camera's token share an entry; other parameters still tell frames apart.
    assertEquals(
      liveImageDiskKey("https://ha.example/api/image_proxy/image.plate?token=one&time=5"),
      liveImageDiskKey("https://ha.example/api/image_proxy/image.plate?token=two&time=5"),
    )
  }

  @Test
  fun aLiveCamerasFramesShareOneEntry() {
    assertEquals(
      "http://ha.local:8123/api/camera_proxy/camera.yard",
      liveImageDiskKey(
        "http://ha.local:8123/api/camera_proxy/camera.yard?token=a&terrazzo_frame=7"
      ),
    )
  }

  @Test
  fun otherPicturesKeepCoilsDefaultKey() {
    assertNull(liveImageDiskKey("http://ha.local:8123/api/image/serve/abc/512x512"))
    assertNull(
      liveImageDiskKey("http://ha.local:8123/api/media_player_proxy/media_player.tv?token=t")
    )
    assertNull(liveImageDiskKey("http://ha.local:8123/local/floorplan.png"))
    assertNull(liveImageDiskKey("/api/camera_proxy/camera.yard?token=abc"))
  }

  @Test
  fun picturesThatCanChangeInPlaceRefreshDaily() {
    assertTrue(refreshesDaily("http://ha.local:8123/local/floorplan.png"))
    assertTrue(refreshesDaily("https://brands.home-assistant.io/hue/icon.png"))
    // Content-addressed: a changed picture is a new URL.
    assertFalse(refreshesDaily("http://ha.local:8123/api/image/serve/abc/512x512"))
    assertFalse(
      refreshesDaily("http://ha.local:8123/api/media_player_proxy/media_player.tv?token=t&cache=h")
    )
    // Live pictures have their own rule; relative URLs aren't resolved yet.
    assertFalse(refreshesDaily("http://ha.local:8123/api/camera_proxy/camera.yard?token=a"))
    assertFalse(refreshesDaily("/local/floorplan.png"))
  }
}
