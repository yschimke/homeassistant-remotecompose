package ee.schimke.terrazzo.core.session

import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * [LiveHaSession] against a scripted Home Assistant WebSocket: token refresh on `auth_invalid`, a
 * sticky sign-out when the refresh token is refused, and `state_changed` events reaching
 * [HaSession.liveStates] across a reconnect.
 */
class LiveHaSessionTest {
  /** A minimal HA: accepts [acceptedToken], answers `get_states` from [states], acks subscribes. */
  private class FakeHa(private val acceptedToken: String) {
    val offeredTokens = CopyOnWriteArrayList<String>()
    val states = mutableMapOf("light.kitchen" to "off")
    val stateSubscriptions = CopyOnWriteArrayList<Pair<DefaultWebSocketServerSession, Int>>()

    suspend fun serve(socket: DefaultWebSocketServerSession): Unit =
      with(socket) {
        send(Frame.Text("""{"type":"auth_required"}"""))
        val auth = Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
        val token = auth.getValue("access_token").jsonPrimitive.content
        offeredTokens += token
        if (token != acceptedToken) {
          send(Frame.Text("""{"type":"auth_invalid","message":"Invalid access token"}"""))
          return
        }
        send(Frame.Text("""{"type":"auth_ok"}"""))
        for (frame in incoming) {
          val command = Json.parseToJsonElement((frame as Frame.Text).readText()).jsonObject
          val id = command.getValue("id").jsonPrimitive.int
          when (command.getValue("type").jsonPrimitive.content) {
            "subscribe_events" -> {
              if (command["event_type"]?.jsonPrimitive?.content == "state_changed") {
                stateSubscriptions += this to id
              }
              send(Frame.Text("""{"id":$id,"type":"result","success":true,"result":null}"""))
            }
            "get_states" -> {
              val result = buildJsonArray {
                states.forEach { (entityId, state) -> add(entityState(entityId, state)) }
              }
              send(Frame.Text("""{"id":$id,"type":"result","success":true,"result":$result}"""))
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

    /** Push a `state_changed` event on the latest connection's subscription. */
    suspend fun changeState(entityId: String, state: String) {
      states[entityId] = state
      val (socket, id) = stateSubscriptions.last()
      val event = buildJsonObject {
        put("id", id)
        put("type", "event")
        put(
          "event",
          buildJsonObject {
            put("event_type", "state_changed")
            put(
              "data",
              buildJsonObject {
                put("entity_id", entityId)
                put("new_state", entityState(entityId, state))
              },
            )
          },
        )
      }
      socket.send(Frame.Text(event.toString()))
    }

    suspend fun dropConnection() {
      stateSubscriptions.last().first.close(CloseReason(CloseReason.Codes.GOING_AWAY, "restart"))
    }

    private fun entityState(entityId: String, state: String) = buildJsonObject {
      put("entity_id", entityId)
      put("state", state)
      put("attributes", buildJsonObject {})
    }
  }

  private suspend fun HaSession.awaitLiveState(entityId: String, state: String) =
    withTimeout(10_000) { liveStates.first { it?.states?.get(entityId)?.state == state } }

  @Test
  fun stateChangedEventsReachLiveStatesAndResubscribeAfterAReconnect() = testApplication {
    val ha = FakeHa(acceptedToken = "token")
    install(WebSockets)
    routing { webSocket("/api/websocket") { ha.serve(this) } }
    val session = LiveHaSession("http://localhost", "token", createClient {}.engine)
    try {
      session.connect()
      session.awaitLiveState("light.kitchen", "off")

      ha.changeState("light.kitchen", "on")
      session.awaitLiveState("light.kitchen", "on")

      // Home Assistant restarts: the socket drops, the app reconnects, and the subscription is
      // re-armed on the new socket — with the states fetched afresh.
      ha.states["light.kitchen"] = "off"
      ha.dropConnection()
      withTimeout(10_000) {
        session.connectionStatus.first { it == SessionConnectionStatus.Failed }
      }
      session.connect()
      session.awaitLiveState("light.kitchen", "off")
      withTimeout(10_000) { while (ha.stateSubscriptions.size < 2) kotlinx.coroutines.delay(10) }

      ha.changeState("light.kitchen", "on")
      session.awaitLiveState("light.kitchen", "on")
      assertEquals(2, ha.stateSubscriptions.size)
    } finally {
      session.close()
    }
  }

  @Test
  fun authInvalidMintsAFreshTokenAndConnects() = testApplication {
    val ha = FakeHa(acceptedToken = "fresh")
    install(WebSockets)
    routing { webSocket("/api/websocket") { ha.serve(this) } }
    var mints = 0
    val tokens =
      HaAccessTokens(
        initialToken = "expired",
        mint = {
          mints++
          MintedAccessToken("fresh")
        },
      )
    val session = LiveHaSession("http://localhost", tokens, createClient {}.engine)
    try {
      session.connect()

      assertEquals(SessionConnectionStatus.Connected, session.connectionStatus.value)
      assertEquals(listOf("expired", "fresh"), ha.offeredTokens.toList())
      assertEquals("fresh", session.accessToken)
      assertEquals(1, mints)
    } finally {
      session.close()
    }
  }

  @Test
  fun aRefusedRefreshTokenNeedsReauthAndStopsTouchingTheNetwork() = testApplication {
    val ha = FakeHa(acceptedToken = "fresh")
    install(WebSockets)
    routing { webSocket("/api/websocket") { ha.serve(this) } }
    val tokens =
      HaAccessTokens(
        initialToken = "expired",
        mint = { throw HaReauthRequiredException("invalid_grant") },
      )
    val session = LiveHaSession("http://localhost", tokens, createClient {}.engine)
    try {
      assertFailsWith<HaReauthRequiredException> { session.connect() }
      assertTrue(session.needsReauth.value)
      assertEquals(SessionConnectionStatus.Failed, session.connectionStatus.value)

      assertFailsWith<HaReauthRequiredException> { session.connect() }
      assertEquals(listOf("expired"), ha.offeredTokens.toList(), "no second socket")
    } finally {
      session.close()
    }
  }
}
