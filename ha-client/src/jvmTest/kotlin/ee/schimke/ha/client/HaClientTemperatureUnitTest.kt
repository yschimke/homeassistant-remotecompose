package ee.schimke.ha.client

import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reading HA's unit system is best effort: a `get_config` that is answered gives the unit, and one
 * that is refused or never answered gives null, quickly, without failing the snapshot it rides on.
 */
class HaClientTemperatureUnitTest {

  private enum class Config {
    Answered,
    Refused,
    Silent,
  }

  private fun fakeHa(config: Config, block: suspend (HaClient) -> Unit) = testApplication {
    install(WebSockets)
    routing {
      webSocket("/api/websocket") {
        send(Frame.Text("""{"type":"auth_required"}"""))
        incoming.receive()
        send(Frame.Text("""{"type":"auth_ok"}"""))
        for (frame in incoming) {
          val command = Json.parseToJsonElement((frame as Frame.Text).readText()).jsonObject
          val id = command.getValue("id").jsonPrimitive.int
          when (command.getValue("type").jsonPrimitive.content) {
            "get_states" ->
              send(
                Frame.Text(
                  """{"id":$id,"type":"result","success":true,"result":[""" +
                    """{"entity_id":"climate.hall","state":"heat","attributes":{}}]}"""
                )
              )
            "get_config" ->
              when (config) {
                Config.Answered ->
                  send(
                    Frame.Text(
                      """{"id":$id,"type":"result","success":true,"result":""" +
                        """{"unit_system":{"temperature":"°F","length":"mi"}}}"""
                    )
                  )
                Config.Refused ->
                  send(
                    Frame.Text(
                      """{"id":$id,"type":"result","success":false,""" +
                        """"error":{"code":"unknown_command","message":"Unknown command."}}"""
                    )
                  )
                Config.Silent -> Unit
              }
            else ->
              send(
                Frame.Text(
                  """{"id":$id,"type":"result","success":false,"error":{"message":"unsupported"}}"""
                )
              )
          }
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
  fun anAnsweredConfigGivesTheUnit() =
    fakeHa(Config.Answered) { client ->
      assertEquals("°F", client.fetchTemperatureUnit())
      assertEquals("°F", client.snapshotWithDevices().temperatureUnit)
    }

  @Test
  fun aRefusedConfigGivesNoUnit() =
    fakeHa(Config.Refused) { client ->
      assertNull(client.fetchTemperatureUnit())
      val snapshot = client.snapshotWithDevices()
      assertNull(snapshot.temperatureUnit)
      assertEquals(setOf("climate.hall"), snapshot.states.keys)
    }

  @Test
  fun aSilentConfigTimesOutToNoUnit() =
    fakeHa(Config.Silent) { client ->
      assertNull(client.fetchTemperatureUnit(timeout = 200.milliseconds))
      // The states still arrive, and the client stays usable afterwards.
      assertEquals(setOf("climate.hall"), client.snapshotWithDevices().states.keys)
      assertEquals(setOf("climate.hall"), client.snapshot().states.keys)
    }
}
