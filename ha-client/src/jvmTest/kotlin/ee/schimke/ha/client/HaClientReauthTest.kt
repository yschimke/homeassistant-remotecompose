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
import kotlinx.coroutines.channels.consumeEach

class HaClientReauthTest {
  @Test
  fun authInvalidIsTypedAndTheNextConnectAsksTheProviderForAFreshToken() = testApplication {
    val offered = mutableListOf<String>()
    install(WebSockets)
    routing {
      webSocket("/api/websocket") {
        send(Frame.Text("""{"type":"auth_required"}"""))
        val auth = (incoming.receive() as Frame.Text).readText()
        val token = Regex("\"access_token\":\"([^\"]*)\"").find(auth)!!.groupValues[1]
        offered += token
        if (token == "fresh") {
          send(Frame.Text("""{"type":"auth_ok"}"""))
          incoming.consumeEach {}
        } else {
          send(Frame.Text("""{"type":"auth_invalid","message":"Invalid access token"}"""))
        }
      }
    }
    val tokens = ArrayDeque(listOf("expired", "fresh"))
    val client =
      HaClient(
        HaConfig("http://localhost", "unused"),
        createClient {}.engine,
        accessTokenProvider = { tokens.removeFirst() },
      )
    try {
      val error = assertFailsWith<HaAuthInvalidException> { client.connect() }
      assertEquals(true, error.message?.contains("auth_invalid"), error.message)
      assertEquals(HaClient.ConnectionState.Error, client.state.value)

      client.connect()
      assertEquals(HaClient.ConnectionState.Ready, client.state.value)
      assertEquals(listOf("expired", "fresh"), offered)
    } finally {
      client.close()
    }
  }
}
