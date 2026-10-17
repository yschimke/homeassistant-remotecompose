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
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * [HaClient.replaceDashboardConfig] writes an agent's dashboard back to Home Assistant through
 * `lovelace/config/save` and hands back the config it replaced, for undo.
 */
class HaClientDashboardSaveTest {
  private lateinit var server: EmbeddedServer<*, *>
  private var port = 0

  /** Stored configs by `url_path` ("" for the default dashboard); "yaml" refuses saves. */
  private val configs = ConcurrentHashMap<String, JsonObject>()

  @BeforeTest
  fun start() {
    port = ServerSocket(0).use { it.localPort }
    configs[""] = config("Home")
    configs["garden"] = config("Garden")
    configs["yaml"] = config("Yaml")
    server = newServer(port)
  }

  @AfterTest
  fun stop() {
    runCatching { server.stop(gracePeriodMillis = 50, timeoutMillis = 500) }
  }

  private fun config(title: String) = buildJsonObject { put("title", title) }

  private fun client() = HaClient(HaConfig(baseUrl = "http://127.0.0.1:$port", accessToken = "t"))

  @Test
  fun replacingADashboardSavesItAndReturnsThePreviousConfig() = runBlocking {
    val client = client()
    try {
      client.connect()
      val previous = client.replaceDashboardConfig(config("Garden v2"), urlPath = "garden")
      assertEquals(config("Garden"), previous)
      assertEquals(config("Garden v2"), client.fetchDashboardConfig("garden"))
      // The default dashboard has no url_path and is untouched.
      assertEquals(config("Home"), client.fetchDashboardConfig())
      // Undo is saving the previous config back.
      client.replaceDashboardConfig(previous, urlPath = "garden")
      assertEquals(config("Garden"), client.fetchDashboardConfig("garden"))
    } finally {
      client.close()
    }
  }

  @Test
  fun aRefusedSaveThrowsHomeAssistantsMessage() = runBlocking {
    val client = client()
    try {
      client.connect()
      val error =
        assertFailsWith<IllegalStateException> {
          client.replaceDashboardConfig(config("Nope"), urlPath = "yaml")
        }
      assertTrue(error.message!!.contains("YAML mode"), error.message)
      assertEquals(config("Yaml"), client.fetchDashboardConfig("yaml"))
    } finally {
      client.close()
    }
  }

  @Test
  fun conditionalSaveAndUndoRefuseExternalChanges() = runBlocking {
    val client = client()
    try {
      client.connect()
      val original = client.fetchDashboardConfig("garden")
      configs["garden"] = config("External edit")
      assertFailsWith<ee.schimke.ha.client.dashboard.DashboardConflictException> {
        client.compareAndReplaceDashboardConfig(original, config("Draft"), "garden")
      }
      assertEquals(config("External edit"), client.fetchDashboardConfig("garden"))
      configs["garden"] = original
      client.compareAndReplaceDashboardConfig(original, config("Draft"), "garden")
      assertEquals(config("Draft"), client.fetchDashboardConfig("garden"))
      configs["garden"] = config("External after save")
      assertFailsWith<ee.schimke.ha.client.dashboard.DashboardConflictException> {
        client.compareAndReplaceDashboardConfig(config("Draft"), original, "garden")
      }
      assertEquals(config("External after save"), client.fetchDashboardConfig("garden"))
    } finally {
      client.close()
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
              val reply = handle(msg) ?: continue
              send(Frame.Text(JSON.encodeToString(JsonObject.serializer(), reply)))
            }
          }
        }
      }
      .start(wait = false)

  private fun handle(msg: JsonObject): JsonObject? {
    val type = msg["type"]?.jsonPrimitive?.content ?: return null
    if (type == "auth") return buildJsonObject { put("type", "auth_ok") }
    val id = msg["id"]?.jsonPrimitive?.intOrNull ?: return null
    val path = msg["url_path"]?.jsonPrimitive?.content ?: ""
    return when (type) {
      "lovelace/config" -> result(id, configs.getValue(path))
      "lovelace/config/save" ->
        if (path == "yaml") {
          failure(id, "Cannot save a dashboard in YAML mode")
        } else {
          configs[path] = msg["config"]!!.jsonObject
          result(id, JsonNull)
        }
      else -> null
    }
  }

  private fun result(id: Int, value: JsonElement) = buildJsonObject {
    put("id", id)
    put("type", "result")
    put("success", true)
    put("result", value)
  }

  private fun failure(id: Int, message: String) = buildJsonObject {
    put("id", id)
    put("type", "result")
    put("success", false)
    put(
      "error",
      buildJsonObject {
        put("code", "config_not_writable")
        put("message", message)
      },
    )
  }

  private companion object {
    val JSON = Json { ignoreUnknownKeys = true }
  }
}
