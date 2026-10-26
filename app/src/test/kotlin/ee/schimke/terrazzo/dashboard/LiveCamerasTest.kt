package ee.schimke.terrazzo.dashboard

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.Section
import ee.schimke.ha.model.View
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

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

    val framed = snapshot.withLiveFrames(mapOf("camera.yard" to "3"))

    assertEquals(
      "/api/camera_proxy/camera.yard?token=t&terrazzo_frame=3",
      (framed.states.getValue("camera.yard").attributes["entity_picture"] as JsonPrimitive).content,
    )
    assertEquals(snapshot.states["camera.door"], framed.states["camera.door"])
    // No live camera on this dashboard: the same snapshot, so nothing recomposes for it.
    assertSame(snapshot, snapshot.withLiveFrames(mapOf("camera.other" to "3")))
  }

  @Test
  fun dashboardCamerasTickSlowerThanLiveOnes() {
    val cameras = setOf("camera.yard", "camera.door")
    // Before the first tick every camera keeps the snapshot's own picture.
    assertEquals(emptyMap(), cameraFrames(cameras, setOf("camera.yard"), 0, 0))
    assertEquals(
      mapOf("camera.yard" to "l4", "camera.door" to "d1"),
      cameraFrames(cameras, setOf("camera.yard"), dashboardFrame = 1, liveFrame = 4),
    )
    // A camera just turned live keeps the dashboard's frame until its own first tick.
    assertEquals(
      mapOf("camera.door" to "d2"),
      cameraFrames(setOf("camera.door"), setOf("camera.door"), dashboardFrame = 2, liveFrame = 0),
    )
  }

  @Test
  fun aLiveCameraEndsAfterItsWindow() {
    LiveCameras.setLive(listOf("camera.yard"), on = true, nowMs = 0L)
    LiveCameras.expire(nowMs = LiveCameras.MAX_LIVE_MS - 1)
    assertTrue("camera.yard" in LiveCameras.live.value)

    LiveCameras.expire(nowMs = LiveCameras.MAX_LIVE_MS)
    assertTrue(LiveCameras.live.value.isEmpty())
  }

  @Test
  fun aPictureCardPairsItsEntityWithItsCamera() {
    fun card(json: String) =
      Json.parseToJsonElement(json).jsonObject.let {
        CardConfig(it["type"].toString().trim('"'), it)
      }
    val dashboard =
      Dashboard(
        views =
          listOf(
            View(
              sections =
                listOf(
                  Section(
                    cards =
                      listOf(
                        card("""{"type":"tile","entity":"light.porch"}"""),
                        // Nested in a stack.
                        card(
                          """{"type":"vertical-stack","cards":[{"type":"picture-entity",""" +
                            """"entity":"binary_sensor.door","camera_image":"camera.door"}]}"""
                        ),
                      )
                  )
                )
            )
          )
      )

    assertEquals("camera.door", pairedCamera(dashboard, "binary_sensor.door"))
    // A picture-glance's cells, bare or as objects.
    val glance =
      Dashboard(
        views =
          listOf(
            View(
              cards =
                listOf(
                  card(
                    """{"type":"picture-glance","camera_image":"camera.yard",""" +
                      """"entities":["light.yard",{"entity":"lock.gate"}]}"""
                  )
                )
            )
          )
      )
    assertEquals("camera.yard", pairedCamera(glance, "light.yard"))
    assertEquals("camera.yard", pairedCamera(glance, "lock.gate"))
    assertNull(pairedCamera(dashboard, "light.porch"))
    // A camera shows its own view.
    assertNull(pairedCamera(dashboard, "camera.door"))
  }
}
