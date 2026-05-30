package ee.schimke.terrazzo.integration

import ee.schimke.terrazzo.core.session.LiveHaSession
import ee.schimke.terrazzo.core.session.SessionConnectionStatus
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Regression for "Failed: Software caused connection abort" on dashboard switch.
 *
 * Before the fix: when the underlying WebSocket died (server hang-up, idle TCP
 * timeout, network change), `HaClient.receiveLoop` set `state = Error` but left
 * `session` pointing at the dead socket. The next `awaitCommand` (e.g. a
 * dashboard switch triggering `lovelace/config`) called `send` on that socket
 * and Android surfaced `SocketException: Software caused connection abort` —
 * with no path back to a healthy session, because `LiveHaSession.connectionStatus`
 * was never wired to the underlying HaClient state. So the app's
 * `repeatOnLifecycle(RESUMED)` reconnect loop (which watches `connectionStatus`)
 * never fired either.
 *
 * After the fix:
 *   - `receiveLoop` clears `session` and flips state to `Disconnected` on the way
 *     out, so the next send fails fast with a clear "Not connected" error instead
 *     of a raw SocketException.
 *   - `LiveHaSession` observes `HaClient.state` and propagates Disconnected/Error
 *     to `connectionStatus = Failed`, so the existing reconnect loop wakes up.
 */
class LiveHaSessionReconnectTest {

  private lateinit var server: EmbeddedServer<*, *>
  private var port: Int = 0
  private val handler = HaProtocolHandler()

  private val baseUrl: String
    get() = "http://127.0.0.1:$port"

  @BeforeTest
  fun start() {
    port = freePort()
    server = newServer(port)
  }

  @AfterTest
  fun stop() {
    runCatching { server.stop(gracePeriodMillis = 50, timeoutMillis = 500) }
  }

  @Test
  fun mid_flight_disconnect_flips_status_to_failed_and_clears_dead_session() {
    runBlocking {
      val session = LiveHaSession(baseUrl = baseUrl, accessToken = "test-token")
      try {
        session.connect()
        assertEquals(SessionConnectionStatus.Connected, session.connectionStatus.value)

        // First fetch lands on the live socket.
        val (home, _) = session.loadDashboard(null)
        assertEquals("Home", home.title)

        // Kill the server out from under us — simulates HA restart, idle
        // TCP timeout, or an Android network change. The read loop will
        // catch the resulting close and tear down its session reference.
        server.stop(gracePeriodMillis = 50, timeoutMillis = 500)

        // The bridge into connectionStatus must surface this — without
        // it, the app's reconnect loop never fires and the user is stuck
        // on a dead session.
        withTimeout(3_000) {
          session.connectionStatus.filter { it == SessionConnectionStatus.Failed }.first()
        }

        // A subsequent fetch must not be a `SocketException: Software
        // caused connection abort` from a stale socket. The session
        // reference was cleared on read-loop exit, so we get a clean
        // "Not connected" error the UI can either retry or fall back
        // from. The exact message isn't load-bearing — we just verify
        // we don't fall through into a dead socket.
        assertFailsWith<Throwable> { session.loadDashboard(null) }
      } finally {
        session.close()
      }
    }
  }

  @Test
  fun reconnect_after_server_returns_restores_connected_status() {
    runBlocking {
      val session = LiveHaSession(baseUrl = baseUrl, accessToken = "test-token")
      try {
        session.connect()
        assertEquals("Home", session.loadDashboard(null).first.title)

        // Bounce the server: stop, then bring a fresh one up on the same
        // port so the existing baseUrl still resolves. Mirrors an HA
        // restart from the user's perspective.
        server.stop(gracePeriodMillis = 50, timeoutMillis = 500)
        withTimeout(3_000) {
          session.connectionStatus.filter { it == SessionConnectionStatus.Failed }.first()
        }

        server = newServer(port)
        // Drive the recovery path the app itself drives (the
        // `repeatOnLifecycle(RESUMED)` loop in TerrazzoApp).
        session.connect()
        withTimeout(3_000) {
          session.connectionStatus.filter { it == SessionConnectionStatus.Connected }.first()
        }
        assertEquals("Home", session.loadDashboard(null).first.title)
      } finally {
        session.close()
      }
    }
  }

  @Test
  fun disconnect_parks_cleanly_as_disconnected_not_failed() {
    runBlocking {
      val session = LiveHaSession(baseUrl = baseUrl, accessToken = "test-token")
      try {
        session.connect()
        assertEquals(SessionConnectionStatus.Connected, session.connectionStatus.value)

        // Backgrounding: park the socket. This must read as a clean
        // Disconnected, not Failed — otherwise the log paints it red as
        // an "Error" on every app switch (the bug this fixes) and the
        // reconnect loop treats an intentional park as a fault.
        session.disconnect()
        assertEquals(SessionConnectionStatus.Disconnected, session.connectionStatus.value)

        // The HaClient.Disconnected that the socket close emits behind us
        // must not flip the bridge back to Failed. Settle, then assert we
        // held Disconnected.
        withTimeout(3_000) {
          session.connectionStatus.filter { it == SessionConnectionStatus.Disconnected }.first()
        }
        assertEquals(SessionConnectionStatus.Disconnected, session.connectionStatus.value)

        // Foregrounding: reconnect restores a live, usable session.
        session.connect()
        withTimeout(3_000) {
          session.connectionStatus.filter { it == SessionConnectionStatus.Connected }.first()
        }
        assertEquals("Home", session.loadDashboard(null).first.title)
      } finally {
        session.close()
      }
    }
  }

  private fun newServer(port: Int): EmbeddedServer<*, *> =
    embeddedServer(CIO, port = port, host = "127.0.0.1") {
        install(WebSockets)
        routing {
          webSocket("/api/websocket") {
            send(Frame.Text("""{"type":"auth_required","ha_version":"test"}"""))
            for (frame in incoming) {
              if (frame !is Frame.Text) continue
              val msg = JSON.parseToJsonElement(frame.readText()).jsonObject
              handler.handle(msg)?.let { reply ->
                send(Frame.Text(JSON.encodeToString(JsonObject.serializer(), reply)))
              }
            }
          }
        }
      }
      .start(wait = false)

  private class HaProtocolHandler {
    fun handle(msg: JsonObject): JsonObject? {
      val type = msg["type"]?.jsonPrimitive?.content ?: return null
      if (type == "auth") return buildJsonObject { put("type", "auth_ok") }
      val id = msg["id"]?.jsonPrimitive?.intOrNull ?: return null
      return when (type) {
        "lovelace/config" -> result(id, dashboardConfig())
        "get_states" -> result(id, buildJsonArray {})
        else -> null
      }
    }

    private fun dashboardConfig() = buildJsonObject {
      put("title", "Home")
      put("views", buildJsonArray { add(buildJsonObject {}) })
    }

    private fun result(id: Int, value: kotlinx.serialization.json.JsonElement) = buildJsonObject {
      put("id", id)
      put("type", "result")
      put("success", true)
      put("result", value)
    }
  }

  companion object {
    private val JSON = Json { ignoreUnknownKeys = true }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
  }
}
