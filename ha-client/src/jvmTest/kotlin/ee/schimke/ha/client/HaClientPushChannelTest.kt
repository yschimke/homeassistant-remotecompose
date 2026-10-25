package ee.schimke.ha.client

import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * HA's push channel delivers distinct messages, so none may be dropped while the app is busy with
 * an earlier one (a `clear_notification` after its post must still arrive).
 */
class HaClientPushChannelTest {
  @Test
  fun everyMessageArrivesInOrderWhileTheCollectorIsSlow() {
    val commands = mutableListOf<JsonObject>()
    testApplication {
      install(WebSockets)
      routing {
        webSocket("/api/websocket") {
          send(Frame.Text("""{"type":"auth_required"}"""))
          incoming.receive()
          send(Frame.Text("""{"type":"auth_ok"}"""))
          for (frame in incoming) {
            val command = Json.parseToJsonElement((frame as Frame.Text).readText()).jsonObject
            commands += command
            val id = command.getValue("id").jsonPrimitive.int
            send(Frame.Text("""{"id":$id,"type":"result","success":true,"result":null}"""))
            if (
              command.getValue("type").jsonPrimitive.content ==
                "mobile_app/push_notification_channel"
            ) {
              // A burst, faster than the app handles them.
              for (message in listOf("one", "two", "clear_notification")) {
                send(Frame.Text("""{"id":$id,"type":"event","event":{"message":"$message"}}"""))
              }
            }
          }
        }
      }
      val client = HaClient(HaConfig("http://localhost", "token"), createClient {}.engine)
      try {
        client.connect()
        val received =
          client
            .pushNotificationChannel("hook")
            .map { event ->
              delay(100)
              event.getValue("message").jsonPrimitive.content
            }
            .take(3)
            .toList()
        assertEquals(listOf("one", "two", "clear_notification"), received)
      } finally {
        client.close()
      }
    }
    val subscribe = commands.first {
      it.getValue("type").jsonPrimitive.content == "mobile_app/push_notification_channel"
    }
    assertEquals("hook", subscribe.getValue("webhook_id").jsonPrimitive.content)
    assertEquals(true, subscribe.getValue("support_confirm").jsonPrimitive.content.toBoolean())
  }
}
