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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.withTimeout

class HaClientHandshakeTest {
  @Test
  fun silentServerFailsTheConnectInsteadOfHangingIt() = testApplication {
    val closed = CompletableDeferred<Unit>()
    install(WebSockets)
    routing {
      webSocket("/api/websocket") {
        // Accept the upgrade, then never send auth_required.
        incoming.consumeEach {}
        closed.complete(Unit)
      }
    }
    val client = HaClient(HaConfig("http://localhost", "token"), createClient {}.engine)
    client.handshakeTimeout = 200.milliseconds
    try {
      val error = assertFailsWith<IllegalStateException> { client.connect() }
      assertTrue("did not answer" in error.message.orEmpty(), error.message)
      assertEquals(HaClient.ConnectionState.Error, client.state.value)
      // The half-open socket is closed rather than leaked.
      withTimeout(5_000) { closed.await() }
    } finally {
      client.close()
    }
  }

  @Test
  fun unexpectedFirstFrameClosesTheSocketAndReportsAnError() = testApplication {
    val closed = CompletableDeferred<Unit>()
    install(WebSockets)
    routing {
      webSocket("/api/websocket") {
        send(Frame.Text("""{"type":"not_auth_required"}"""))
        incoming.consumeEach {}
        closed.complete(Unit)
      }
    }
    val client = HaClient(HaConfig("http://localhost", "token"), createClient {}.engine)
    try {
      assertFailsWith<IllegalArgumentException> { client.connect() }
      assertEquals(HaClient.ConnectionState.Error, client.state.value)
      withTimeout(5_000) { closed.await() }
    } finally {
      client.close()
    }
  }

  @Test
  fun successfulHandshakeIsReady() = testApplication {
    install(WebSockets)
    routing {
      webSocket("/api/websocket") {
        send(Frame.Text("""{"type":"auth_required"}"""))
        val auth = incoming.receive() as Frame.Text
        check("\"access_token\":\"token\"" in auth.readText())
        send(Frame.Text("""{"type":"auth_ok"}"""))
        incoming.consumeEach {}
      }
    }
    val client = HaClient(HaConfig("http://localhost", "token"), createClient {}.engine)
    try {
      client.connect()
      assertEquals(HaClient.ConnectionState.Ready, client.state.value)
    } finally {
      client.close()
    }
  }
}
