package ee.schimke.ha.rc

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class StablePicturesTest {

  private val card =
    CardConfig(
      "picture-entity",
      buildJsonObject {
        put("type", "picture-entity")
        put("entity", "camera.front")
      },
    )

  private fun snapshot(picture: String?) =
    HaSnapshot(
      states =
        mapOf(
          "camera.front" to
            EntityState(
              "camera.front",
              "idle",
              JsonObject(
                buildMap {
                  put("friendly_name", JsonPrimitive("Front"))
                  if (picture != null) put("entity_picture", JsonPrimitive(picture))
                }
              ),
            )
        )
    )

  @Test
  fun aNewFrameKeepsTheBakedSnapshotAndPresence() {
    val first = snapshot("/api/camera_proxy/camera.front?token=a")
    val next = snapshot("/api/camera_proxy/camera.front?token=b")

    assertEquals(StablePictures.snapshot(card, first), StablePictures.snapshot(card, next))
    assertEquals(StablePictures.presence(card, first), StablePictures.presence(card, next))
    assertNotEquals(cardPictureSignature(card, first), cardPictureSignature(card, next))
  }

  @Test
  fun thePlaceholderResolvesToTheLiveUrl() {
    val baked = StablePictures.snapshot(card, snapshot("/a?token=a"))
    val placeholder =
      (baked.states.getValue("camera.front").attributes["entity_picture"] as JsonPrimitive).content

    assertEquals("/a?token=b", StablePictures.resolve(placeholder, snapshot("/a?token=b")))
    assertNull(StablePictures.resolve("/local/plain.png", snapshot("/a?token=b")))
  }

  @Test
  fun aSlotWithoutAPictureStaysAsIsAndChangesPresence() {
    val none = snapshot(null)

    assertSame(none, StablePictures.snapshot(card, none))
    assertNotEquals(
      StablePictures.presence(card, none),
      StablePictures.presence(card, snapshot("/a")),
    )
  }

  @Test
  fun aCameraImageIsTheSlotsPicture() {
    // A door sensor drawn over the camera watching the door: the camera's frames are the
    // picture, so they load in place like the camera's own card.
    val door =
      CardConfig(
        "picture-entity",
        buildJsonObject {
          put("type", "picture-entity")
          put("entity", "binary_sensor.door")
          put("camera_image", "camera.front")
        },
      )
    fun withDoor(picture: String) =
      snapshot(picture).let {
        it.copy(
          states = it.states + ("binary_sensor.door" to EntityState("binary_sensor.door", "off"))
        )
      }
    val first = withDoor("/api/camera_proxy/camera.front?token=a")
    val next = withDoor("/api/camera_proxy/camera.front?token=b")

    assertEquals(setOf("binary_sensor.door", "camera.front"), cardPictureEntityIds(door))
    assertEquals(StablePictures.snapshot(door, first), StablePictures.snapshot(door, next))
    assertEquals(StablePictures.presence(door, first), StablePictures.presence(door, next))
    assertNotEquals(cardPictureSignature(door, first), cardPictureSignature(door, next))
  }
}
