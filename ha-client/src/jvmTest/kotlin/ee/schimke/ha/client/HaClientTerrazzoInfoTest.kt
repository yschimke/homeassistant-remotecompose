package ee.schimke.ha.client

import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** [detectTerrazzo] over a real socket: the commands it sends, and what it makes of the answers. */
class HaClientTerrazzoInfoTest {

  private fun fakeHa(
    commands: MutableList<JsonObject>,
    answer: (type: String, id: Int) -> String,
    block: suspend (HaClient) -> Unit,
  ) = testApplication {
    install(WebSockets)
    routing {
      webSocket("/api/websocket") {
        send(Frame.Text("""{"type":"auth_required"}"""))
        incoming.receive()
        send(Frame.Text("""{"type":"auth_ok"}"""))
        for (frame in incoming) {
          val command = Json.parseToJsonElement((frame as Frame.Text).readText()).jsonObject
          synchronized(commands) { commands += command }
          send(
            Frame.Text(
              answer(
                command.getValue("type").jsonPrimitive.content,
                command.getValue("id").jsonPrimitive.int,
              ),
            ),
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

  private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

  @Test
  fun aCurrentIntegrationAnswersInOneRoundTrip() {
    val commands = mutableListOf<JsonObject>()
    fakeHa(
      commands,
      { _, id ->
        """{"id":$id,"type":"result","success":true,"result":{"protocol":1,"min_client":1,
          "version":"0.1.98","capabilities":{"edit":1,"push":1,"edit_apply":1}}}"""
      },
    ) { client ->
      val installed = assertIs<TerrazzoIntegration.Installed>(client.detectTerrazzo())
      assertEquals("0.1.98", installed.info.version)
      assertEquals(1, installed.info.capabilities.editApply)
    }
    assertEquals(listOf("terrazzo/info"), commands.map { it.string("type") })
  }

  @Test
  fun anOlderIntegrationIsFoundByItsManifest() {
    val commands = mutableListOf<JsonObject>()
    fakeHa(
      commands,
      { type, id ->
        if (type == "terrazzo/info")
          """{"id":$id,"type":"result","success":false,
            "error":{"code":"unknown_command","message":"Unknown command."}}"""
        else
          """{"id":$id,"type":"result","success":true,
            "result":{"domain":"terrazzo","version":"0.1.90"}}"""
      },
    ) { client ->
      assertEquals(TerrazzoIntegration.TooOld("0.1.90"), client.detectTerrazzo())
    }
    assertEquals(listOf("terrazzo/info", "manifest/get"), commands.map { it.string("type") })
    assertEquals("terrazzo", commands[1].string("integration"))
  }

  @Test
  fun noIntegrationIsAbsent() {
    fakeHa(
      mutableListOf(),
      { type, id ->
        val code = if (type == "terrazzo/info") "unknown_command" else "not_found"
        """{"id":$id,"type":"result","success":false,"error":{"code":"$code","message":"No."}}"""
      },
    ) { client ->
      assertEquals(TerrazzoIntegration.Absent, client.detectTerrazzo())
    }
  }
}
