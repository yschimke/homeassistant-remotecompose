package ee.schimke.ha.client

import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HaClientReadyStateTest {
  @Test
  fun aCollectorReactingToReadyCanSubscribeStraightAway() = testApplication {
    install(WebSockets)
    routing {
      webSocket("/api/websocket") {
        send(Frame.Text("""{"type":"auth_required"}"""))
        incoming.receive()
        send(Frame.Text("""{"type":"auth_ok"}"""))
        for (frame in incoming) {
          val id = Regex("\"id\":(\\d+)").find((frame as Frame.Text).readText())!!.groupValues[1]
          send(Frame.Text("""{"id":$id,"type":"result","success":true,"result":null}"""))
        }
      }
    }
    val client = HaClient(HaConfig("http://localhost", "token"), createClient {}.engine)
    try {
      // Unconfined runs the collector inside the Ready transition itself, the tightest form of
      // the race a Dispatchers.Default collector (LiveHaSession's state feed) can lose.
      var failure: Throwable? = null
      coroutineScope {
        launch(Dispatchers.Unconfined) {
          client.state.first { it == HaClient.ConnectionState.Ready }
          try {
            client.subscribeEvents("state_changed")
          } catch (e: IllegalStateException) {
            failure = e
          }
        }
        client.connect()
      }
      assertEquals(null, failure?.message)
    } finally {
      client.close()
    }
  }
}
