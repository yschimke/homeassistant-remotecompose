package ee.schimke.terrazzo.wearsync

import ee.schimke.terrazzo.wearsync.proto.EntityValue
import ee.schimke.terrazzo.wearsync.proto.WearSyncPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WearDataCleanupTest {

  @Test
  fun signOutDeletesEveryAccountItemButKeepsSettings() {
    val paths = WATCH_ACCOUNT_DATA.map { it.path }
    for (path in
      listOf(
        WearSyncPaths.dashboardPath("energy"),
        WearSyncPaths.dashboardPath(null),
        WearSyncPaths.VALUES,
        WearSyncPaths.picturePath("camera.door"),
        WearSyncPaths.PINNED,
        WearSyncPaths.SECTIONS,
        WearSyncPaths.SLOTS,
        WearSyncPaths.SLOT_DESIGNS,
        WearSyncPaths.designPath("ab12"),
      )) {
      assertTrue(
        WATCH_ACCOUNT_DATA.any { if (it.prefix) path.startsWith(it.path) else path == it.path },
        "$path is not deleted on sign-out",
      )
    }
    assertTrue(WearSyncPaths.SETTINGS !in paths)
    // Literal paths are deleted as literals: `/wear/values` must not take `/wear/values2` along.
    assertTrue(WATCH_ACCOUNT_DATA.filter { !it.prefix }.none { it.path.endsWith("/") })
  }

  @Test
  fun dashboardsNoLongerInHomeAssistantAreStale() {
    val existing =
      listOf(
        WearSyncPaths.dashboardPath(null),
        WearSyncPaths.dashboardPath("energy"),
        WearSyncPaths.dashboardPath("old-board"),
      )
    val current = listOf(WearSyncPaths.dashboardPath(null), WearSyncPaths.dashboardPath("energy"))
    assertEquals(
      setOf(WearSyncPaths.dashboardPath("old-board")),
      staleDashboardPaths(existing, current),
    )
  }

  @Test
  fun onlyDashboardItemsArePruned() {
    val existing =
      listOf(WearSyncPaths.VALUES, WearSyncPaths.PINNED, WearSyncPaths.dashboardPath("x"))
    assertEquals(
      setOf(WearSyncPaths.dashboardPath("x")),
      staleDashboardPaths(existing, emptyList()),
    )
    assertEquals(
      emptySet<String>(),
      staleDashboardPaths(emptyList(), listOf(WearSyncPaths.dashboardPath("x"))),
    )
  }

  @Test
  fun cachedValuesKeepTheirFetchTime() {
    // Backgrounded phone serving a snapshot fetched ten minutes ago.
    assertEquals(1_000L, valuesCapturedAtMs(fetchedAtMs = 1_000L, nowMs = 601_000L))
  }

  @Test
  fun freshlyFetchedValuesAreStampedNow() {
    assertEquals(601_000L, valuesCapturedAtMs(fetchedAtMs = null, nowMs = 601_000L))
  }

  @Test
  fun aFetchTimeAheadOfTheClockIsCappedAtNow() {
    assertEquals(500L, valuesCapturedAtMs(fetchedAtMs = 900L, nowMs = 500L))
  }

  @Test
  fun deltasLostToAFailedSendAreResent() {
    val before = mapOf("light.kitchen" to EntityValue(state = "off"))
    val tick1 = mapOf("light.kitchen" to EntityValue(state = "on"))
    assertEquals(tick1, streamDeltas(before, tick1))

    // The send failed (or no watch was connected): the baseline stays.
    val baseline = streamBaselineAfterSend(before, tick1, delivered = false)
    assertEquals(before, baseline)

    // Nothing changed since, but the lost delta still goes out.
    assertEquals(tick1, streamDeltas(baseline, tick1))
  }

  @Test
  fun deliveredDeltasAreNotResent() {
    val before = mapOf("light.kitchen" to EntityValue(state = "off"))
    val tick1 = mapOf("light.kitchen" to EntityValue(state = "on"))
    val baseline = streamBaselineAfterSend(before, tick1, delivered = true)
    assertEquals(emptyMap<String, EntityValue>(), streamDeltas(baseline, tick1))
  }
}
