package ee.schimke.terrazzo.integration

import ee.schimke.ha.client.HaClient
import ee.schimke.ha.client.HaConfig
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
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
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
 * Covers the new event-subscription dispatch path in [HaClient].
 *
 * Before the feature: `receiveLoop` matched every framed `id` against the one-shot `pending` map and
 * dropped anything else, so HA-side `type:"event"` frames went to /dev/null. Adding
 * persistent-notification support meant teaching the loop to route those event frames to a separate
 * per-subscription `SharedFlow` while keeping the existing `type:"result"` matching intact.
 *
 * The two tests pin both halves of that contract:
 *   - `fetchPersistentNotifications` round-trips a real `persistent_notification/get` reply and
 *     parses it.
 *   - `subscribeEvents` ack lands in `pending` (and the call returns), and the subsequent event
 *     frame sharing that id reaches the returned flow — i.e. the ack didn't dismantle the
 *     subscription.
 */
class HaClientSubscriptionTest {

  private lateinit var server: EmbeddedServer<*, *>
  private var port: Int = 0
  private val handler = NotifyingProtocolHandler()

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
  fun fetchPersistentNotifications_parses_get_reply() {
    runBlocking {
      val client = HaClient(HaConfig(baseUrl = baseUrl, accessToken = "test-token"))
      try {
        client.connect()
        val notifications = client.fetchPersistentNotifications()
        assertEquals(1, notifications.size)
        val only = notifications.single()
        assertEquals("welcome", only.notificationId)
        assertEquals("Welcome", only.title)
        assertEquals("Hello there", only.message)
        assertEquals("2026-05-17T08:00:00+00:00", only.createdAt)
      } finally {
        client.close()
      }
    }
  }

  @Test
  fun dismissNotification_sends_dismiss_call_service() {
    runBlocking {
      val client = HaClient(HaConfig(baseUrl = baseUrl, accessToken = "test-token"))
      try {
        client.connect()
        client.dismissNotification("welcome")
        val sent = withTimeout(2_000) { handler.calledServices.receive() }
        assertEquals("persistent_notification", sent["domain"]?.jsonPrimitive?.content)
        assertEquals("dismiss", sent["service"]?.jsonPrimitive?.content)
        assertEquals(
          "welcome",
          sent["service_data"]?.jsonObject?.get("notification_id")?.jsonPrimitive?.content,
        )
      } finally {
        client.close()
      }
    }
  }

  @Test
  fun dismissAllNotifications_sends_dismiss_all_call_service() {
    runBlocking {
      val client = HaClient(HaConfig(baseUrl = baseUrl, accessToken = "test-token"))
      try {
        client.connect()
        client.dismissAllNotifications()
        val sent = withTimeout(2_000) { handler.calledServices.receive() }
        assertEquals("persistent_notification", sent["domain"]?.jsonPrimitive?.content)
        assertEquals("dismiss_all", sent["service"]?.jsonPrimitive?.content)
      } finally {
        client.close()
      }
    }
  }

  @Test
  fun subscribeEvents_delivers_event_frame_after_ack() {
    runBlocking {
      val client = HaClient(HaConfig(baseUrl = baseUrl, accessToken = "test-token"))
      try {
        client.connect()
        // Subscribing returns once the server has acked. The ack lives in `pending`; if the receive
        // loop also tore the subscription down at that point, the follow-up push below would never
        // reach the flow and `await()` would hang until the 2s timeout.
        val flow = client.subscribeEvents("persistent_notifications_updated")

        // Attach the collector BEFORE pushing. SharedFlow has replay=0; the receive loop's
        // `tryEmit` would happily land in the extra buffer if no one is collecting yet, and a late
        // subscriber sees nothing. `onSubscription` fires the moment the collector is wired in,
        // so we can synchronise the test against "now safe to push".
        val collectorReady = CompletableDeferred<Unit>()
        val received = CompletableDeferred<JsonObject>()
        val collectJob = launch {
          flow
            .onSubscription { collectorReady.complete(Unit) }
            .collect { ev ->
              if (ev["event_type"]?.jsonPrimitive?.content == "persistent_notifications_updated") {
                received.complete(ev)
              }
            }
        }
        collectorReady.await()

        handler.firePersistentNotificationsUpdated()

        val event = withTimeout(2_000) { received.await() }
        assertTrue(event.containsKey("event_type"))
        collectJob.cancel()
      } finally {
        client.close()
      }
    }
  }

  private fun newServer(port: Int): EmbeddedServer<*, *> =
    embeddedServer(CIO, port = port, host = "127.0.0.1") {
        install(WebSockets)
        routing {
          webSocket("/api/websocket") {
            send(Frame.Text("""{"type":"auth_required","ha_version":"test"}"""))
            // Separate coroutine forwards server-pushed frames so they don't block on the inbound
            // loop. Without this, `firePersistentNotificationsUpdated()` would only get flushed
            // the next time the client happened to send a frame — which after `subscribeEvents`
            // it never does.
            val pushJob = launch {
              for (push in handler.pushes) {
                send(Frame.Text(JSON.encodeToString(JsonObject.serializer(), push)))
              }
            }
            try {
              for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val msg = JSON.parseToJsonElement(frame.readText()).jsonObject
                for (reply in handler.handle(msg)) {
                  send(Frame.Text(JSON.encodeToString(JsonObject.serializer(), reply)))
                }
              }
            } finally {
              pushJob.cancel()
            }
          }
        }
      }
      .start(wait = false)

  private class NotifyingProtocolHandler {
    @Volatile private var subscriptionId: Int? = null
    val pushes: Channel<JsonObject> = Channel(Channel.UNLIMITED)

    /** Every `call_service` frame the client sends, in arrival order, for assertions. */
    val calledServices: Channel<JsonObject> = Channel(Channel.UNLIMITED)

    fun handle(msg: JsonObject): List<JsonObject> {
      val type = msg["type"]?.jsonPrimitive?.content ?: return emptyList()
      if (type == "auth") return listOf(buildJsonObject { put("type", "auth_ok") })
      val id = msg["id"]?.jsonPrimitive?.intOrNull ?: return emptyList()
      return when (type) {
        "call_service" -> {
          calledServices.trySend(msg)
          listOf(result(id, JsonObject(emptyMap())))
        }
        "persistent_notification/get" ->
          listOf(
            result(
              id,
              buildJsonArray {
                add(
                  buildJsonObject {
                    put("notification_id", "welcome")
                    put("title", "Welcome")
                    put("message", "Hello there")
                    put("created_at", "2026-05-17T08:00:00+00:00")
                    put("status", "unread")
                  }
                )
              },
            )
          )
        "subscribe_events" -> {
          subscriptionId = id
          listOf(result(id, JsonObject(emptyMap())))
        }
        else -> emptyList()
      }
    }

    /** Push a single `persistent_notifications_updated` event on whatever id is currently subscribed. */
    fun firePersistentNotificationsUpdated() {
      val subId = subscriptionId ?: error("no active subscription")
      pushes.trySend(
        buildJsonObject {
          put("id", subId)
          put("type", "event")
          put(
            "event",
            buildJsonObject {
              put("event_type", "persistent_notifications_updated")
              put("data", JsonObject(emptyMap()))
              put("time_fired", "2026-05-17T08:00:01+00:00")
              put("origin", "LOCAL")
            },
          )
        }
      )
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
