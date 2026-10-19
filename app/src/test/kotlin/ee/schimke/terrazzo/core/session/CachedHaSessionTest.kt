package ee.schimke.terrazzo.core.session

import ee.schimke.ha.client.DashboardSummary
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.View
import ee.schimke.terrazzo.core.cache.OfflineCacheStorage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest

/**
 * The contract under test:
 * - First successful live fetch is mirrored to the cache.
 * - Subsequent failures fall back to the cached value silently.
 * - First-ever fetch with no cache propagates the failure so [DashboardListState] can show an error
 *   screen.
 */
class CachedHaSessionTest {

  private fun storage(): OfflineCacheStorage =
    OfflineCacheStorage(Files.createTempDirectory("cached-session-test").toFile())

  private val baseUrl = "http://homeassistant.local:8123"

  private val sampleSummary = listOf(DashboardSummary(urlPath = null, title = "Home"))
  private val sampleDashboard = Dashboard(title = "Home", views = listOf(View(title = "Main")))
  private val sampleSnapshot =
    HaSnapshot(states = mapOf("sw.x" to EntityState(entityId = "sw.x", state = "on")))

  @Test
  fun listDashboards_persists_then_falls_back_on_failure() = runTest {
    val cache = storage()
    var failNext = false
    val delegate =
      FakeSession(
        baseUrl = baseUrl,
        listProvider = { if (failNext) error("network down") else sampleSummary },
      )
    val session = CachedHaSession(delegate, cache)

    // First call: live succeeds, cache populated.
    assertEquals(sampleSummary, session.listDashboards())
    assertEquals(sampleSummary, cache.dashboards(baseUrl))

    // Live fails — cached value returned, no exception.
    failNext = true
    assertEquals(sampleSummary, session.listDashboards())
  }

  @Test
  fun listDashboards_propagates_failure_when_cache_is_empty() = runTest {
    val cache = storage()
    val delegate = FakeSession(baseUrl = baseUrl, listProvider = { error("offline") })
    val session = CachedHaSession(delegate, cache)
    assertFailsWith<IllegalStateException> { session.listDashboards() }
  }

  @Test
  fun loadDashboard_persists_then_falls_back_on_failure() = runTest {
    val cache = storage()
    var failNext = false
    val delegate =
      FakeSession(
        baseUrl = baseUrl,
        loadProvider = { _ ->
          if (failNext) error("ws closed") else sampleDashboard to sampleSnapshot
        },
      )
    val session = CachedHaSession(delegate, cache)

    val (d1, s1) = session.loadDashboard(null)
    assertEquals(sampleDashboard, d1)
    assertEquals(sampleSnapshot, s1)
    assertEquals(sampleDashboard, cache.dashboard(baseUrl, null))
    assertEquals(sampleSnapshot, cache.snapshot(baseUrl, null))

    failNext = true
    val (d2, s2) = session.loadDashboard(null)
    assertEquals(sampleDashboard, d2)
    assertEquals(sampleSnapshot, s2)
  }

  @Test
  fun namedDashboardRefresh_updates_widget_snapshot_but_failed_fetch_keeps_last_success() =
    runTest {
      val cache = storage()
      var fresh = sampleSnapshot
      var failNext = false
      val session =
        CachedHaSession(
          FakeSession(
            baseUrl = baseUrl,
            loadProvider = {
              if (failNext) error("offline")
              sampleDashboard to fresh
            },
          ),
          cache,
        )
      session.loadDashboard("printer-dashboard")
      assertNull(cache.snapshot(baseUrl, null))
      assertEquals(sampleSnapshot, cache.latestSnapshot(baseUrl))
      fresh = HaSnapshot(states = mapOf("sw.x" to EntityState("sw.x", "off")))
      session.loadDashboard("printer-dashboard")
      assertEquals(fresh, cache.latestSnapshot(baseUrl))
      failNext = true
      session.loadDashboard("printer-dashboard")
      assertEquals(fresh, cache.latestSnapshot(baseUrl))
    }

  @Test
  fun snapshotFetchedAt_is_null_when_live_and_the_fetch_time_when_served_from_cache() = runTest {
    val cache = storage()
    var failNext = false
    val session =
      CachedHaSession(
        FakeSession(
          baseUrl = baseUrl,
          loadProvider = {
            if (failNext) error("backgrounded") else sampleDashboard to sampleSnapshot
          },
        ),
        cache,
      )
    val before = System.currentTimeMillis()
    session.loadDashboard(null)
    val after = System.currentTimeMillis()
    // Live: the caller's "now" is the honest time.
    assertNull(session.snapshotFetchedAtMs(null))

    failNext = true
    session.loadDashboard(null)
    // Cached: the time it was fetched, not now.
    val fetchedAt = session.snapshotFetchedAtMs(null)!!
    assertTrue(fetchedAt in before..after, "fetchedAt=$fetchedAt not in $before..$after")
    // Another dashboard's state is its own.
    assertNull(session.snapshotFetchedAtMs("other"))

    failNext = false
    session.loadDashboard(null)
    assertNull(session.snapshotFetchedAtMs(null))
  }

  @Test
  fun snapshotFetchedAt_after_a_cold_start_fallback_is_the_cache_write_time() = runTest {
    val cache = storage()
    cache.putDashboard(baseUrl, null, sampleDashboard)
    cache.putSnapshot(baseUrl, null, sampleSnapshot)
    val written = cache.snapshotSavedAtMs(baseUrl, null)!!
    val session =
      CachedHaSession(FakeSession(baseUrl = baseUrl, loadProvider = { error("offline") }), cache)
    session.loadDashboard(null)
    assertEquals(written, session.snapshotFetchedAtMs(null))
  }

  @Test
  fun loadDashboard_falls_back_to_empty_snapshot_when_only_dashboard_was_cached() = runTest {
    val cache = storage()
    // Pre-seed only the dashboard, not the snapshot — simulates the
    // edge case where the snapshot file got corrupted / wiped.
    cache.putDashboard(baseUrl, null, sampleDashboard)

    val session =
      CachedHaSession(
        delegate = FakeSession(baseUrl = baseUrl, loadProvider = { error("offline") }),
        cache = cache,
      )
    val (d, s) = session.loadDashboard(null)
    assertEquals(sampleDashboard, d)
    assertTrue(s.states.isEmpty())
  }

  @Test
  fun connect_marks_lastInstance_even_on_failure() = runTest {
    val cache = storage()
    val session =
      CachedHaSession(
        delegate = FakeSession(baseUrl = baseUrl, connectProvider = { error("dns") }),
        cache = cache,
      )
    session.connect()
    assertEquals(baseUrl, cache.lastInstance())
  }
}

private class FakeSession(
  override val baseUrl: String,
  private val connectProvider: suspend () -> Unit = {},
  private val listProvider: suspend () -> List<DashboardSummary> = { emptyList() },
  private val loadProvider: suspend (String?) -> Pair<Dashboard, HaSnapshot> = { _ ->
    Dashboard() to HaSnapshot()
  },
) : HaSession {
  override val connectionStatus: StateFlow<SessionConnectionStatus> =
    MutableStateFlow(SessionConnectionStatus.Connected)
  override val refreshIntervalMillis: Long? = null

  override suspend fun connect() = connectProvider()

  override suspend fun listDashboards(): List<DashboardSummary> = listProvider()

  override suspend fun loadDashboard(urlPath: String?) = loadProvider(urlPath)

  override suspend fun close() = Unit
}
