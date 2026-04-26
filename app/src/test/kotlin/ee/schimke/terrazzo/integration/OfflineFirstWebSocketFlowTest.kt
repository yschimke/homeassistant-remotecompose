package ee.schimke.terrazzo.integration

import ee.schimke.ha.client.DashboardSummary
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.core.cache.OfflineCacheStorage
import ee.schimke.terrazzo.core.session.CachedHaSession
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.core.session.LiveHaSession
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
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
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
 * End-to-end test of the offline-first contract through `HaClient` (the
 * real WebSocket client) and `CachedHaSession` (the wrapper).
 *
 * Spins up an embedded Ktor server that speaks just enough of HA's
 * WebSocket protocol — `auth_required` / `auth_ok` handshake plus
 * `lovelace/dashboards/list`, `lovelace/config`, and `get_states`
 * commands matched by id. Drives the session, persists the cache,
 * stops the server, then asserts a fresh cache-only session reads the
 * same dashboard. That's "the activity loads".
 *
 * Why Ktor and not mockserver: HA's WebSocket protocol is full-duplex
 * (server pushes `auth_required` first, then commands ride atop a
 * persistent connection with monotonic ids). mockserver-netty's
 * WebSocket layer is for its own callback infrastructure, not for
 * faking an arbitrary protocol. Ktor server is already a project dep
 * (the `addon-server` module), so this stays a single-library fake.
 * The HTTP-side companion test (`OfflineFirstAddonHttpTest`) uses
 * mockserver where it shines.
 */
class OfflineFirstWebSocketFlowTest {

  private lateinit var server: EmbeddedServer<*, *>
  private var port: Int = 0
  private val handler = HaProtocolHandler()
  private lateinit var cacheRoot: File

  private val baseUrl: String
    get() = "http://127.0.0.1:$port"

  @BeforeTest
  fun start() {
    port = freePort()
    cacheRoot = Files.createTempDirectory("offline-flow-test").toFile()
    server =
      embeddedServer(CIO, port = port, host = "127.0.0.1") {
          install(WebSockets)
          routing {
            webSocket("/api/websocket") {
              // HA's flow: server greets with auth_required before the
              // client sends anything. HaClient's read loop blocks
              // until this frame arrives.
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
  }

  @AfterTest
  fun stop() {
    runCatching { server.stop(gracePeriodMillis = 100, timeoutMillis = 1_000) }
    cacheRoot.deleteRecursively()
  }

  @Test
  fun activity_loads_from_cache_after_disconnect() = runBlocking {
    // ── Phase 1: live session populates the cache ─────────────────────
    val storage = OfflineCacheStorage(cacheRoot)
    val live = LiveHaSession(baseUrl = baseUrl, accessToken = "test-token")
    val session = CachedHaSession(live, storage)

    session.connect()
    val dashboards = session.listDashboards()
    assertEquals(
      listOf("Home" to null, "Office" to "office"),
      dashboards.map { it.title to it.urlPath },
    )

    val (home, snapshot) = session.loadDashboard(null)
    assertEquals("Home", home.title)
    assertEquals(
      "21.4",
      snapshot.states["sensor.living_room"]?.state,
      "live snapshot was not delivered through the wire",
    )

    // The cache is hot — same wire bytes, same content.
    assertNotNull(storage.dashboards(baseUrl))
    assertNotNull(storage.dashboard(baseUrl, null))
    assertNotNull(storage.snapshot(baseUrl, null))

    // ── Phase 2: a streamed update overwrites the cached snapshot ────
    handler.setLivingRoom("22.7")
    val (_, updated) = session.loadDashboard(null)
    assertEquals("22.7", updated.states["sensor.living_room"]?.state)
    assertEquals(
      "22.7",
      storage.snapshot(baseUrl, null)?.states?.get("sensor.living_room")?.state,
      "cache should reflect the streamed update",
    )

    session.close()

    // ── Phase 3: kill the server ─────────────────────────────────────
    server.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)

    // Sanity-check we're actually offline (not silently re-using a
    // pooled connection): a fresh live session must fail to connect.
    val severed = LiveHaSession(baseUrl = baseUrl, accessToken = "test-token")
    assertFailsWith<Throwable> { severed.connect() }
    severed.close()

    // ── Phase 4: cold-start with cache-only — the activity loads ─────
    val coldSession = CachedHaSession(delegate = AlwaysOfflineSession(baseUrl), cache = storage)

    coldSession.connect()
    val cachedDashboards = coldSession.listDashboards()
    assertEquals(2, cachedDashboards.size, "dashboard list must be served from cache")
    val (cachedHome, cachedSnap) = coldSession.loadDashboard(null)
    assertEquals("Home", cachedHome.title)
    assertEquals(
      "22.7",
      cachedSnap.states["sensor.living_room"]?.state,
      "cached snapshot must include the latest streamed update",
    )
    assertTrue(cachedSnap.states.isNotEmpty(), "cached snapshot must include all entities")
    coldSession.close()
  }

  /**
   * Stand-in for the production `OfflineOnlySession` (which is
   * `internal` to `terrazzo-core`). Always throws on live calls so
   * `CachedHaSession` exercises its cache-fallback path.
   */
  private class AlwaysOfflineSession(override val baseUrl: String) : HaSession {
    override suspend fun connect() = Unit

    override suspend fun listDashboards(): List<DashboardSummary> = error("offline")

    override suspend fun loadDashboard(urlPath: String?): Pair<Dashboard, HaSnapshot> =
      error("offline")

    override suspend fun close() = Unit
  }

  /**
   * Minimal HA-protocol simulator. Covers the three commands HaClient
   * sends today (`lovelace/dashboards/list`, `lovelace/config`,
   * `get_states`) and replies with the matching `id`. State is
   * mutable so a test can drive an "update" between two fetches.
   */
  private class HaProtocolHandler {
    @Volatile private var livingRoom = "21.4"

    fun setLivingRoom(value: String) {
      livingRoom = value
    }

    fun handle(msg: JsonObject): JsonObject? {
      val type = msg["type"]?.jsonPrimitive?.content ?: return null
      if (type == "auth") return buildJsonObject { put("type", "auth_ok") }

      val id = msg["id"]?.jsonPrimitive?.intOrNull ?: return null
      return when (type) {
        "lovelace/dashboards/list" -> result(id, dashboardsList())
        "lovelace/config" -> result(id, dashboardConfig())
        "get_states" -> result(id, statesArray())
        else -> null
      }
    }

    private fun dashboardsList() = buildJsonArray {
      // HA omits `url_path` entirely for the default dashboard (the
      // value is null on the wire only when the dashboard is named).
      // Matching that here so the test exercises the same parsing
      // path the production HaClient hits.
      add(buildJsonObject { put("title", "Home") })
      add(
        buildJsonObject {
          put("url_path", "office")
          put("title", "Office")
        }
      )
    }

    private fun dashboardConfig() = buildJsonObject {
      put("title", "Home")
      put(
        "views",
        buildJsonArray {
          add(
            buildJsonObject {
              put(
                "cards",
                buildJsonArray {
                  add(
                    buildJsonObject {
                      put("type", "tile")
                      put("entity", "sensor.living_room")
                    }
                  )
                },
              )
            }
          )
        },
      )
    }

    private fun statesArray() = buildJsonArray {
      add(
        buildJsonObject {
          put("entity_id", "sensor.living_room")
          put("state", livingRoom)
          put(
            "attributes",
            buildJsonObject {
              put("friendly_name", "Living Room")
              put("unit_of_measurement", "°C")
            },
          )
        }
      )
      add(
        buildJsonObject {
          put("entity_id", "light.kitchen")
          put("state", "on")
          put("attributes", buildJsonObject { put("friendly_name", "Kitchen") })
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
