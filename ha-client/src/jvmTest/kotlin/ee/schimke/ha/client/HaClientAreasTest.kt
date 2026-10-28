package ee.schimke.ha.client

import ee.schimke.ha.room.HaArea
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The registry reads the room controls stand on: areas, and each entity's area and category. */
class HaClientAreasTest {

  private fun fakeHa(answer: (type: String) -> String, block: suspend (HaClient) -> Unit) =
    testApplication {
      install(WebSockets)
      routing {
        webSocket("/api/websocket") {
          send(Frame.Text("""{"type":"auth_required"}"""))
          incoming.receive()
          send(Frame.Text("""{"type":"auth_ok"}"""))
          for (frame in incoming) {
            val command = Json.parseToJsonElement((frame as Frame.Text).readText()).jsonObject
            val id = command.getValue("id").jsonPrimitive.int
            val type = command.getValue("type").jsonPrimitive.content
            send(
              Frame.Text("""{"id":$id,"type":"result","success":true,"result":${answer(type)}}""")
            )
          }
        }
      }
      val client = HaClient(HaConfig("http://localhost", "token"), createClient {}.engine)
      try {
        client.connect()
        block(client)
      } finally {
        client.close()
      }
    }

  @Test
  fun areasCarryNamesFloorsAndAliases() =
    fakeHa({
      """[{"area_id":"living_room","name":"Living Room","floor_id":"ground","icon":"mdi:sofa",
         "aliases":["Lounge"]},{"area_id":"attic"}]"""
    }) { client ->
      assertEquals(
        mapOf(
          "living_room" to
            HaArea("living_room", "Living Room", "ground", "mdi:sofa", listOf("Lounge")),
          "attic" to HaArea("attic", "attic"),
        ),
        client.fetchAreas(),
      )
    }

  @Test
  fun anEntityTakesItsOwnAreaElseItsDevicesAndKeepsItsCategory() =
    fakeHa({ type ->
      when (type) {
        "config/entity_registry/list_for_display" ->
          """{"entities":[
            {"ei":"media_player.tv","di":"d1"},
            {"ei":"light.lamp","di":"d1","ai":"office"},
            {"ei":"switch.identify","di":"d1","ec":0},
            {"ei":"sensor.signal","di":"d1","ec":1,"hb":true}
          ]}"""
        else -> """[{"id":"d1","area_id":"living_room","manufacturer":"Acme"}]"""
      }
    }) { client ->
      val devices = client.fetchDevices()
      assertEquals("living_room", devices.getValue("media_player.tv").areaId)
      assertEquals("office", devices.getValue("light.lamp").areaId)
      assertEquals("config", devices.getValue("switch.identify").entityCategory)
      assertNull(devices.getValue("media_player.tv").entityCategory)
      assertEquals("diagnostic", devices.getValue("sensor.signal").entityCategory)
      assertTrue(devices.getValue("sensor.signal").hidden)
      assertFalse(devices.getValue("light.lamp").hidden)
    }
}
