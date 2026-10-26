package ee.schimke.ha.rc

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.PictureGlanceCardConverter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class PictureGlancePictureTest {
  private val converter = PictureGlanceCardConverter()

  private fun card(json: String) =
    CardConfig("picture-glance", Json.parseToJsonElement(json).jsonObject)

  private val snapshot =
    HaSnapshot(
      states =
        mapOf(
          "light.kitchen" to EntityState("light.kitchen", "on"),
          "camera.yard" to
            EntityState(
              "camera.yard",
              "idle",
              JsonObject(mapOf("entity_picture" to JsonPrimitive("/api/camera_proxy/camera.yard"))),
            ),
        )
    )

  @Test
  fun aStaticImageIsDrawnOffTheTemplatePath() {
    val glance =
      card("""{"type":"picture-glance","image":"/local/room.jpg","entities":["light.kitchen"]}""")

    assertNull(converter.designProperties(glance, snapshot))
    val data = converter.pictureGlanceData(glance, snapshot)
    assertEquals("/local/room.jpg", data.imageUrl)
    assertNull(data.pictureEntityId)
  }

  @Test
  fun aCameraImageWinsAndIsAPictureSlot() {
    val glance =
      card(
        """{"type":"picture-glance","image":"/local/room.jpg","camera_image":"camera.yard",""" +
          """"entities":["light.kitchen"]}"""
      )

    val data = converter.pictureGlanceData(glance, snapshot)
    assertEquals("/api/camera_proxy/camera.yard", data.imageUrl)
    assertEquals("camera.yard", data.pictureEntityId)
    assertEquals(setOf("camera.yard"), cardPictureEntityIds(glance))
  }

  @Test
  fun aGlanceWithoutAPictureKeepsTheTemplatePath() {
    val glance = card("""{"type":"picture-glance","title":"Room","entities":["light.kitchen"]}""")
    assertEquals(null, converter.pictureGlanceData(glance, snapshot).imageUrl)
    kotlin.test.assertNotNull(converter.designProperties(glance, snapshot))
  }
}
