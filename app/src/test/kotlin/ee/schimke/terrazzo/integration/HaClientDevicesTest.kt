package ee.schimke.terrazzo.integration

import ee.schimke.ha.client.HaClient
import ee.schimke.ha.client.HaConfig
import ee.schimke.ha.design.DeviceInfo
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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * [HaClient.fetchDevices] joins the entity registry's display list with the device registry, in the
 * compact keys Home Assistant's own frontend reads (`ei`, `di`, `pl`, `tk`, `dp`).
 */
class HaClientDevicesTest {
  private var server: EmbeddedServer<*, *>? = null

  @AfterTest
  fun stop() {
    runCatching { server?.stop(gracePeriodMillis = 50, timeoutMillis = 500) }
  }

  private val entities =
    Json.parseToJsonElement(
      """
      {"entity_categories": {}, "entities": [
        {"ei": "sensor.boiler_flow", "di": "dev1", "pl": "vaillant", "tk": "flow_temperature"},
        {"ei": "light.kitchen", "pl": "hue"},
        {"ei": "sensor.outdoor_temperature", "pl": "met", "dp": 1},
        {"ei": "sensor.bare"}
      ]}
      """
    )

  private val devices =
    Json.parseToJsonElement(
      """[{"id": "dev1", "manufacturer": "Vaillant", "model": "ecoTEC plus 832"}]"""
    )

  private fun start(deviceRegistry: Boolean): String {
    val port = ServerSocket(0).use { it.localPort }
    server =
      embeddedServer(CIO, port = port, host = "127.0.0.1") {
          install(WebSockets)
          routing {
            webSocket("/api/websocket") {
              send(Frame.Text("""{"type":"auth_required"}"""))
              for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val msg = Json.parseToJsonElement(frame.readText()).jsonObject
                val type = msg["type"]?.jsonPrimitive?.content
                if (type == "auth") {
                  send(Frame.Text("""{"type":"auth_ok"}"""))
                  continue
                }
                val id = msg["id"]?.jsonPrimitive?.intOrNull ?: continue
                val reply =
                  when (type) {
                    "config/entity_registry/list_for_display" -> result(id, entities)
                    "config/device_registry/list" ->
                      if (deviceRegistry) result(id, devices) else unauthorized(id)
                    else -> continue
                  }
                send(Frame.Text(reply.toString()))
              }
            }
          }
        }
        .start(wait = false)
    return "http://127.0.0.1:$port"
  }

  private fun result(id: Int, value: JsonElement) = buildJsonObject {
    put("id", id)
    put("type", "result")
    put("success", true)
    put("result", value)
  }

  private fun unauthorized(id: Int): JsonObject = buildJsonObject {
    put("id", id)
    put("type", "result")
    put("success", false)
    put(
      "error",
      buildJsonObject {
        put("code", "unauthorized")
        put("message", "Unauthorized")
      },
    )
  }

  private fun devices(deviceRegistry: Boolean): Map<String, DeviceInfo> = runBlocking {
    val client = HaClient(HaConfig(baseUrl = start(deviceRegistry), accessToken = "t"))
    try {
      client.connect()
      client.fetchDevices()
    } finally {
      client.close()
    }
  }

  @Test
  fun entitiesGetTheirIntegrationAndTheirDevicesMakeAndModel() {
    assertEquals(
      mapOf(
        "sensor.boiler_flow" to
          DeviceInfo(
            integration = "vaillant",
            manufacturer = "Vaillant",
            model = "ecoTEC plus 832",
            translationKey = "flow_temperature",
          ),
        "light.kitchen" to DeviceInfo(integration = "hue"),
        // `dp`: the registry's display precision, which `formatState` rounds to.
        "sensor.outdoor_temperature" to DeviceInfo(integration = "met", displayPrecision = 1),
      ),
      devices(deviceRegistry = true),
    )
  }

  @Test
  fun withoutTheDeviceRegistryEntitiesKeepTheirIntegration() {
    assertEquals(
      DeviceInfo(integration = "vaillant", translationKey = "flow_temperature"),
      devices(deviceRegistry = false)["sensor.boiler_flow"],
    )
  }
}
