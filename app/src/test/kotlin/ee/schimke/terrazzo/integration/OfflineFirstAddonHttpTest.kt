package ee.schimke.terrazzo.integration

import ee.schimke.ha.client.AddonClient
import ee.schimke.terrazzo.core.cache.OfflineCacheStorage
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.mockserver.model.MediaType

/**
 * HTTP-side companion to `OfflineFirstWebSocketFlowTest`. Drives
 * `AddonClient` (the REST client for the RemoteCompose HA add-on)
 * against a mockserver-netty fake, and verifies the offline-first
 * fallback contract on this surface:
 *
 *   1. With the server up, `AddonClient` round-trips the health
 *      probe and a dashboard fetch.
 *   2. The fetched dashboard is mirrored to `OfflineCacheStorage` so a
 *      later cold start can read it back without hitting the network.
 *   3. With the server down, `AddonClient.health()` returns `false`
 *      instead of throwing — exactly the fall-through signal the
 *      runtime uses to drop the addon from the `CardSource` chain.
 *   4. The cache still holds the dashboard from step 2, so the UI
 *      surface ("activity loads") would render from disk.
 *
 * mockserver-netty is the right fit here: every endpoint is a plain
 * GET that maps cleanly to a `request().respond()` expectation, and
 * mockserver's lifecycle (`start` / `stop`) gives us a real socket
 * that can be torn down to simulate offline. (`fetchCardBytes` is
 * intentionally not exercised here — `CardKey.toCacheKey()` uses `#`
 * in the path which Ktor's URL builder treats as a fragment
 * delimiter; that's a separate concern from offline-first.)
 */
class OfflineFirstAddonHttpTest {

  private lateinit var mockServer: ClientAndServer
  private var port: Int = 0
  private lateinit var cacheRoot: java.io.File

  private val baseUrl: String
    get() = "http://127.0.0.1:$port"

  @BeforeTest
  fun start() {
    port = freePort()
    cacheRoot = Files.createTempDirectory("offline-addon-test").toFile()
    mockServer = ClientAndServer.startClientAndServer(port)
  }

  @AfterTest
  fun stop() {
    runCatching { mockServer.stop() }
    cacheRoot.deleteRecursively()
  }

  @Test
  fun activity_loads_from_cache_when_addon_is_down() = runBlocking {
    // ── Phase 1: server is up ────────────────────────────────────────
    mockServer
      .`when`(request().withMethod("GET").withPath("/healthz"))
      .respond(response().withStatusCode(200).withBody("ok"))

    val dashboardJson =
      """
      {
        "title": "Home",
        "views": [
          { "cards": [ { "type": "tile", "raw": { "type": "tile", "entity": "sensor.living_room" } } ] }
        ]
      }
      """
        .trimIndent()
    mockServer
      .`when`(request().withMethod("GET").withPath("/v1/dashboards/_default"))
      .respond(
        response()
          .withStatusCode(200)
          .withContentType(MediaType.APPLICATION_JSON)
          .withBody(dashboardJson)
      )

    val storage = OfflineCacheStorage(cacheRoot)
    val client = AddonClient(baseUrl = baseUrl, accessToken = "test-token")

    assertTrue(client.health(), "addon health probe must pass while mockserver is up")

    val dashboard = client.fetchDashboard(urlPath = null)
    assertEquals("Home", dashboard.title)
    assertEquals(1, dashboard.views.size)

    // Mirror the live fetch to the cache — this is what a future
    // CachedAddonClient (or the existing CachedHaSession path) would
    // do on every successful fetch.
    storage.putDashboard(baseUrl, urlPath = null, dashboard = dashboard)

    // ── Phase 2: kill the server ─────────────────────────────────────
    mockServer.stop()

    // health() is documented to swallow network errors and return
    // false so the runtime can drop the addon from the chain.
    assertFalse(client.health(), "health() must return false (not throw) when addon is down")

    // fetchDashboard() does not have the swallow-and-return-null
    // contract — it throws on any error so a CachedHaSession-style
    // wrapper can fall through to its on-disk mirror. Verify the
    // throw, then verify the cache has the dashboard from Phase 1.
    assertFailsWith<Throwable> { client.fetchDashboard(urlPath = null) }

    // ── Phase 3: the activity loads — cache reads still succeed ──────
    val cached = storage.dashboard(baseUrl, urlPath = null)
    assertNotNull(cached, "cache must retain the dashboard after the addon went away")
    assertEquals("Home", cached.title)
    assertEquals(1, cached.views.size)
    assertEquals("tile", cached.views[0].cards[0].type)

    client.close()
  }

  companion object {
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
  }
}
