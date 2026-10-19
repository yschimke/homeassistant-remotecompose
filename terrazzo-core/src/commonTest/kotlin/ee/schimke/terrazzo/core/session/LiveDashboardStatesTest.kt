package ee.schimke.terrazzo.core.session

import ee.schimke.ha.client.DashboardSummary
import ee.schimke.ha.design.DeviceInfo
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.HistoryPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class LiveDashboardStatesTest {
  private fun states(vararg pairs: Pair<String, String>) = pairs.associate { (id, state) ->
    id to EntityState(id, state)
  }

  private val history =
    mapOf("sensor.power" to listOf(HistoryPoint(Instant.fromEpochSeconds(0), "1")))
  private val device = mapOf("light.kitchen" to DeviceInfo(integration = "hue"))

  private val loaded =
    HaSnapshot(
      states = states("light.kitchen" to "off", "sensor.power" to "1"),
      history = history,
      devices = device,
    )

  @Test
  fun liveStatesReplaceEntityStatesAndKeepWhatTheFeedDoesNotCarry() {
    val live = HaSnapshot(states = states("light.kitchen" to "on"))

    val merged = loaded.withLiveStates(live)

    assertEquals(states("light.kitchen" to "on"), merged.states)
    assertEquals(history, merged.history)
    assertEquals(device, merged.devices)
  }

  @Test
  fun liveDevicesWinWhenTheFeedHasThem() {
    val fresh = mapOf("light.kitchen" to DeviceInfo(integration = "zha"))

    assertEquals(fresh, loaded.withLiveStates(HaSnapshot(devices = fresh)).devices)
  }

  @Test
  fun followsEachStateChangeAndSkipsGapsAndRepeats() = runTest {
    val session = FakeLiveSession()
    val shown = mutableListOf<HaSnapshot>()
    val job = launch { session.liveDashboardSnapshots(loaded).collect { shown += it } }
    runCurrent()
    assertEquals(emptyList(), shown, "no live feed yet: keep the loaded snapshot")

    // The connection's first full fetch matches what was loaded: nothing to redraw.
    session.live.value = HaSnapshot(states = loaded.states)
    runCurrent()
    assertEquals(emptyList(), shown)

    // A `state_changed` event turns the light on.
    session.live.value = HaSnapshot(states = states("light.kitchen" to "on", "sensor.power" to "1"))
    runCurrent()
    // Reconnecting: the feed goes quiet, then a fresh connection re-arms it.
    session.live.value = null
    runCurrent()
    session.live.value = HaSnapshot(states = states("light.kitchen" to "on", "sensor.power" to "1"))
    runCurrent()
    // Back to exactly the loaded states: still a change from what's shown.
    session.live.value = HaSnapshot(states = loaded.states)
    runCurrent()

    assertEquals(listOf("on", "off"), shown.map { it.states.getValue("light.kitchen").state })
    shown.forEach { assertEquals(history, it.history) }
    job.cancel()
  }

  private class FakeLiveSession : HaSession {
    val live = MutableStateFlow<HaSnapshot?>(null)
    override val liveStates: StateFlow<HaSnapshot?> = live
    override val baseUrl = "http://ha.test"
    override val connectionStatus = MutableStateFlow(SessionConnectionStatus.Connected)

    override suspend fun connect() {}

    override suspend fun listDashboards(): List<DashboardSummary> = emptyList()

    override suspend fun loadDashboard(urlPath: String?): Pair<Dashboard, HaSnapshot> =
      error("unused")

    override suspend fun close() {}
  }
}
