package ee.schimke.terrazzo.wearsync

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import ee.schimke.terrazzo.wearsync.proto.WearPicture
import ee.schimke.terrazzo.wearsync.proto.decodeProto
import ee.schimke.terrazzo.wearsync.proto.encodeProto
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Wear widget pictures: which ones the phone syncs, and the wire shape they travel in. */
class WearPictureSyncTest {

  private fun card(json: String): CardConfig {
    val raw = Json.parseToJsonElement(json) as JsonObject
    return CardConfig(type = (raw["type"] as JsonPrimitive).content, raw = raw)
  }

  private fun entity(id: String, picture: String? = null) =
    EntityState(
      id,
      "idle",
      JsonObject(buildMap { picture?.let { put("entity_picture", JsonPrimitive(it)) } }),
    )

  @Test
  fun syncsThePicturesOfEveryEntityTheSlotCardsBind() {
    val snapshot =
      HaSnapshot(
        states =
          listOf(
              entity("camera.driveway", "/api/camera_proxy/camera.driveway?token=a"),
              entity("person.anna", "/local/anna.png"),
              entity("light.kitchen"),
              entity("camera.unpinned", "/api/camera_proxy/camera.unpinned?token=b"),
            )
            .associateBy { it.entityId }
      )
    val cards =
      listOf(
        card("""{"type":"picture-entity","entity":"camera.driveway"}"""),
        card(
          """{"type":"vertical-stack","cards":[
            {"type":"tile","entity":"person.anna"},
            {"type":"tile","entity":"light.kitchen"},
            {"type":"picture-entity","entity":"camera.driveway"}]}"""
        ),
      )
    assertEquals(
      mapOf(
        "camera.driveway" to "/api/camera_proxy/camera.driveway?token=a",
        "person.anna" to "/local/anna.png",
      ),
      wearPictureUrls(cards, snapshot),
    )
  }

  @Test
  fun aWatchOnTheOldSchemaStillDecodesEntityValues() {
    val bytes = encodeProto(EntityValue(state = "idle", entityPicture = "/local/a.png"))
    assertEquals("idle", decodeProto<OldEntityValue>(bytes)?.state)
    // And the other way: values from an older phone carry no picture.
    val old = decodeProto<EntityValue>(encodeProto(OldEntityValue(state = "on")))
    assertEquals(EntityValue(state = "on"), old)
  }

  @Test
  fun picturesRoundTrip() {
    val png = byteArrayOf(-119, 80, 78, 71, 1, 2, 3)
    val decoded =
      decodeProto<WearPicture>(
        encodeProto(WearPicture("camera.driveway", "/x", png, updatedAtMs = 42))
      )
    assertEquals("camera.driveway", decoded?.entityId)
    assertEquals("/x", decoded?.url)
    assertContentEquals(png, decoded?.png)
    assertEquals(42, decoded?.updatedAtMs)
  }

  /** [EntityValue] before `entity_picture`. */
  @Serializable
  data class OldEntityValue(
    val state: String = "",
    val friendlyName: String = "",
    val unit: String = "",
    val deviceClass: String = "",
  )
}
