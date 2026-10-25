package ee.schimke.ha.client

import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The Terrazzo integration's silent push commands, as the phone calls them. */
class HaClientTerrazzoPushTest {

  private fun fakeHa(
    commands: MutableList<JsonObject>,
    answer: (Int) -> String,
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
          send(Frame.Text(answer(command.getValue("id").jsonPrimitive.int)))
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
  fun registeringSendsTheTokenAndRelayAndReturnsWhatHaIsToCall() {
    val commands = mutableListOf<JsonObject>()
    fakeHa(
      commands,
      { id ->
        """{"id":$id,"type":"result","success":true,"result":
          {"push_url":"http://127.0.0.1:8123/api/terrazzo/push/hook","push_token":"secret"}}"""
      },
    ) { client ->
      val result = client.registerTerrazzoPush("hook", "fcm-token", "https://relay.test/haPush")
      assertEquals("http://127.0.0.1:8123/api/terrazzo/push/hook", result.string("push_url"))
      assertEquals("secret", result.string("push_token"))
    }
    val command = commands.single()
    assertEquals("terrazzo/push/register", command.string("type"))
    assertEquals("hook", command.string("webhook_id"))
    assertEquals("fcm-token", command.string("fcm_token"))
    assertEquals("https://relay.test/haPush", command.string("relay_url"))
  }

  @Test
  fun takingReturnsTheHeldMessagesInOrder() {
    fakeHa(
      mutableListOf(),
      { id ->
        """{"id":$id,"type":"result","success":true,"result":{"messages":[
          {"message":"Washer done","title":"Laundry","data":{"tag":"washer"}},
          {"message":"Second"}]}}"""
      },
    ) { client ->
      val held = client.takeTerrazzoPushes("hook")
      assertEquals(listOf("Washer done", "Second"), held.map { it.string("message") })
      assertEquals("washer", HaPushNotification.parse(held.first())?.tag)
    }
  }

  @Test
  fun withoutTheIntegrationTheCommandIsRefused() {
    fakeHa(
      mutableListOf(),
      { id ->
        """{"id":$id,"type":"result","success":false,
          "error":{"code":"unknown_command","message":"Unknown command."}}"""
      },
    ) { client ->
      val refused =
        assertFailsWith<HaCommandException> {
          client.registerTerrazzoPush("hook", "t", "https://r")
        }
      assertEquals("unknown_command", refused.code)
    }
  }
}
