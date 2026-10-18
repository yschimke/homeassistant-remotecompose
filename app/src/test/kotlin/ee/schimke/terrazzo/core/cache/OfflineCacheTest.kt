package ee.schimke.terrazzo.core.cache

import ee.schimke.ha.client.DashboardSummary
import ee.schimke.ha.design.DeviceInfo
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.View
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Verifies the offline-first contract: writes are durable, reads are keyed correctly, and missing
 * entries return null without throwing (so the UI can treat absence as "no cache yet" instead of an
 * error).
 */
class OfflineCacheTest {

  private val instance = "http://homeassistant.local:8123"
  private val instance2 = "https://other.example/"

  private fun cache(): OfflineCacheStorage =
    OfflineCacheStorage(Files.createTempDirectory("offline-cache-test").toFile())

  @Test
  fun lastInstance_round_trips() {
    val c = cache()
    assertNull(c.lastInstance())
    c.setLastInstance(instance)
    assertEquals(instance, c.lastInstance())
    c.clearLastInstance()
    assertNull(c.lastInstance())
  }

  @Test
  fun dashboards_round_trip_per_instance() {
    val c = cache()
    val list =
      listOf(
        DashboardSummary(urlPath = null, title = "Home"),
        DashboardSummary(urlPath = "office", title = "Office"),
      )
    c.putDashboards(instance, list)
    assertEquals(list, c.dashboards(instance))
    // Different instance has its own slot, not shared.
    assertNull(c.dashboards(instance2))
  }

  @Test
  fun dashboard_and_snapshot_round_trip_per_url_path() {
    val c = cache()
    val home =
      Dashboard(
        title = "Home",
        views =
          listOf(
            View(
              cards =
                listOf(
                  CardConfig(
                    type = "tile",
                    raw = JsonObject(mapOf("type" to JsonPrimitive("tile"))),
                  )
                )
            )
          ),
      )
    val homeSnap =
      HaSnapshot(
        states = mapOf("light.kitchen" to EntityState(entityId = "light.kitchen", state = "on"))
      )

    c.putDashboard(instance, urlPath = null, dashboard = home)
    c.putSnapshot(instance, urlPath = null, snapshot = homeSnap)

    assertEquals(home, c.dashboard(instance, null))
    assertEquals(homeSnap, c.snapshot(instance, null))

    // A different urlPath has its own slot.
    assertNull(c.dashboard(instance, "office"))
  }

  @Test
  fun dashboard_with_slash_in_url_path_does_not_collide_with_filesystem() {
    val c = cache()
    val nested = Dashboard(title = "Nested")
    c.putDashboard(instance, "lovelace/main", nested)
    assertEquals(nested, c.dashboard(instance, "lovelace/main"))
  }

  @Test
  fun missing_entries_return_null_not_throw() {
    val c = cache()
    assertNull(c.dashboards(instance))
    assertNull(c.dashboard(instance, "absent"))
    assertNull(c.snapshot(instance, "absent"))
  }

  @Test
  fun clearInstance_wipes_payloads_and_pointer() {
    val c = cache()
    c.setLastInstance(instance)
    c.putDashboards(instance, listOf(DashboardSummary(null, "Home")))
    c.putDashboard(instance, null, Dashboard(title = "Home"))
    c.putSnapshot(instance, null, HaSnapshot())

    c.clearInstance(instance)

    assertNull(c.lastInstance())
    assertNull(c.dashboards(instance))
    assertNull(c.dashboard(instance, null))
    assertNull(c.snapshot(instance, null))
  }

  @Test
  fun clearInstance_only_clears_pointer_when_it_matches() {
    val c = cache()
    c.setLastInstance(instance2)
    c.putDashboards(instance, listOf(DashboardSummary(null, "Home")))

    c.clearInstance(instance)

    // instance's payloads gone, but the pointer to instance2 is intact.
    assertNull(c.dashboards(instance))
    assertEquals(instance2, c.lastInstance())
  }

  @Test
  fun snapshot_preserves_entity_attributes_through_round_trip() {
    val c = cache()
    val snap =
      HaSnapshot(
        states =
          mapOf(
            "sensor.t" to
              EntityState(
                entityId = "sensor.t",
                state = "21.4",
                attributes =
                  JsonObject(
                    mapOf(
                      "friendly_name" to JsonPrimitive("Living Room"),
                      "unit_of_measurement" to JsonPrimitive("°C"),
                    )
                  ),
              )
          )
      )

    c.putSnapshot(instance, null, snap)
    val read = c.snapshot(instance, null)
    assertNotNull(read)
    assertEquals("21.4", read.states["sensor.t"]?.state)
    assertEquals(
      JsonPrimitive("Living Room"),
      read.states["sensor.t"]?.attributes?.get("friendly_name"),
    )
  }

  @Test
  fun latestSnapshot_tracks_named_dashboard_refreshes_per_instance_and_survives_restart() {
    val root = Files.createTempDirectory("latest-snapshot-test").toFile()
    val c = OfflineCacheStorage(root)
    val id = "sensor.p2s_print_status"
    val finished =
      HaSnapshot(
        states = mapOf(id to EntityState(id, "finish")),
        devices = mapOf(id to DeviceInfo(integration = "bambu_lab", deviceId = "printer-device")),
      )
    val printing = finished.copy(states = mapOf(id to EntityState(id, "running")))
    assertNull(c.latestSnapshot(instance))
    c.putSnapshot(instance, "printer-dashboard", finished)
    // Installing a card from a named dashboard must work without ever opening the default one.
    assertNull(c.snapshot(instance, null))
    assertEquals(finished, OfflineCacheStorage(root).latestSnapshot(instance))
    c.putSnapshot(instance2, null, HaSnapshot())
    assertEquals(finished, c.latestSnapshot(instance))
    c.putSnapshot(instance, null, finished)
    c.putSnapshot(instance, "printer-dashboard", printing)
    assertEquals(printing, c.latestSnapshot(instance))
    // Dashboard-specific offline reads retain their own snapshot, independently of widgets.
    assertEquals(finished, c.snapshot(instance, null))
    c.clearInstance(instance)
    assertNull(c.latestSnapshot(instance))
    assertEquals(HaSnapshot(), c.latestSnapshot(instance2))
  }

  @Test
  fun latestSnapshot_recovers_legacy_named_dashboard_cache_and_skips_corrupt_payloads() {
    val root = Files.createTempDirectory("legacy-snapshot-test").toFile()
    val c = OfflineCacheStorage(root)
    val id = "sensor.p2s_print_status"
    val old = HaSnapshot(states = mapOf(id to EntityState(id, "idle")))
    val fresh = HaSnapshot(states = mapOf(id to EntityState(id, "finish")))
    c.putSnapshot(instance, null, old)
    c.putSnapshot(instance, "printer-dashboard", fresh)
    val directory = java.io.File(root, "cache/${OfflineCacheStorage.instanceKey(instance)}")
    // Simulate an existing install made before the latest-snapshot pointer was introduced.
    java.io.File(directory, "latest-snapshot-pointer.json").delete()
    java.io.File(directory, "snapshot/_default.json").setLastModified(1_000)
    java.io.File(directory, "snapshot/printer-dashboard.json").setLastModified(2_000)
    assertEquals(fresh, OfflineCacheStorage(root).latestSnapshot(instance))
    java.io.File(directory, "snapshot/printer-dashboard.json").writeText("broken JSON")
    assertEquals(old, c.latestSnapshot(instance))
  }
}
