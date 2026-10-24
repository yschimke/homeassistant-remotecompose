package ee.schimke.terrazzo.dashboard

import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class LiveCamerasTest {

  @AfterTest fun reset() = LiveCameras.setLive(LiveCameras.live.value.keys, on = false)

  private fun camera(id: String, picture: String) =
    EntityState(id, "idle", JsonObject(mapOf("entity_picture" to JsonPrimitive(picture))))

  @Test
  fun liveCamerasMoveOnAFrameAndOthersStay() {
    val snapshot =
      HaSnapshot(
        states =
          mapOf(
            "camera.yard" to camera("camera.yard", "/api/camera_proxy/camera.yard?token=t"),
            "camera.door" to camera("camera.door", "/api/camera_proxy/camera.door?token=t"),
          )
      )

    val framed = snapshot.withLiveFrames(setOf("camera.yard"), frame = 3)

    assertEquals(
      "/api/camera_proxy/camera.yard?token=t&terrazzo_frame=3",
      (framed.states.getValue("camera.yard").attributes["entity_picture"] as JsonPrimitive).content,
    )
    assertEquals(snapshot.states["camera.door"], framed.states["camera.door"])
    // No live camera on this dashboard: the same snapshot, so nothing recomposes for it.
    assertSame(snapshot, snapshot.withLiveFrames(setOf("camera.other"), frame = 3))
  }

  @Test
  fun aLiveCameraEndsAfterItsWindow() {
    LiveCameras.setLive(listOf("camera.yard"), on = true, nowMs = 0L)
    LiveCameras.expire(nowMs = LiveCameras.MAX_LIVE_MS - 1)
    assertTrue("camera.yard" in LiveCameras.live.value)

    LiveCameras.expire(nowMs = LiveCameras.MAX_LIVE_MS)
    assertTrue(LiveCameras.live.value.isEmpty())
  }
}
