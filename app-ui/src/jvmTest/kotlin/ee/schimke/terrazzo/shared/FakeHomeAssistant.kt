package ee.schimke.terrazzo.shared

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A Home Assistant on a real socket, for the shared app's tests: it accepts any token, answers each
 * WebSocket command from [answers] (by type, the result's JSON) or [errors], and every other one
 * `unknown_command`, as a Home Assistant without that integration does. It records what it was
 * sent, and how many times a client signed in.
 */
class FakeHomeAssistant(
  private val answers: Map<String, String>,
  /** Commands refused, by type, with the error code to answer. */
  private val errors: Map<String, String> = emptyMap(),
) : AutoCloseable {
  /** Every command's type, in the order received. */
  val sent: MutableList<String> = CopyOnWriteArrayList()
  /** Completed sign-ins: one per connection the client opened. */
  val connections = AtomicInteger()

  private val server: EmbeddedServer<*, *> =
    embeddedServer(CIO, port = 0, host = "127.0.0.1") {
        install(WebSockets)
        routing {
          webSocket("/api/websocket") {
            send(Frame.Text("""{"type":"auth_required","ha_version":"2026.11.0"}"""))
            incoming.receive()
            send(Frame.Text("""{"type":"auth_ok","ha_version":"2026.11.0"}"""))
            connections.incrementAndGet()
            for (frame in incoming) {
              if (frame !is Frame.Text) continue
              val command = Json.parseToJsonElement(frame.readText()).jsonObject
              val type = command.getValue("type").jsonPrimitive.content
              val id = command["id"]?.jsonPrimitive?.int ?: continue
              sent += type
              val result = answers[type] ?: DEFAULTS[type].takeIf { type !in errors }
              val error = errors[type] ?: "unknown_command"
              send(
                Frame.Text(
                  if (result != null)
                    """{"id":$id,"type":"result","success":true,"result":$result}"""
                  else
                    """{"id":$id,"type":"result","success":false,
                      "error":{"code":"$error","message":"$error"}}""",
                ),
              )
            }
          }
        }
      }
      .start(wait = false)

  /** Where the app signs in: `http://127.0.0.1:<port>`. */
  val baseUrl: String = runBlocking {
    "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
  }

  override fun close() = server.stop(0, 1_000)

  companion object {
    /** What every Home Assistant answers: an empty house with one two-view dashboard. */
    val DEFAULTS =
      mapOf(
        "subscribe_events" to "null",
        "get_states" to "[]",
        "get_config" to """{"components":[],"unit_system":{"temperature":"°C"}}""",
        "lovelace/dashboards/list" to "[]",
        "lovelace/config" to
          """{"title":"Home","views":[{"title":"Live living room","cards":[]},
            {"title":"Live garden","cards":[]}]}""",
      )
  }
}
