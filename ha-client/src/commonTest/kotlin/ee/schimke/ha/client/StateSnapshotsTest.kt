package ee.schimke.ha.client

import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class StateSnapshotsTest {
  private fun event(id: String, state: String?) = buildJsonObject {
    put("event_type", "state_changed")
    put(
      "data",
      buildJsonObject {
        put("entity_id", id)
        put(
          "new_state",
          if (state == null) JsonNull
          else
            buildJsonObject {
              put("entity_id", id)
              put("state", state)
            },
        )
      },
    )
  }

  @Test
  fun fetchesOnceThenAppliesOnOffAndRemovalWithoutReloadingOtherEntities() = runTest {
    val events = MutableSharedFlow<JsonObject>()
    val snapshots = mutableListOf<HaSnapshot>()
    var fetches = 0
    val collector = launch {
      stateSnapshots(events) {
          fetches++
          HaSnapshot(
            states =
              mapOf(
                "light.test" to EntityState("light.test", "off"),
                "sensor.other" to EntityState("sensor.other", "5"),
              )
          )
        }
        .collect { snapshots.add(it) }
    }
    runCurrent()
    events.emit(event("light.test", "on"))
    runCurrent()
    assertEquals("on", snapshots.last().states["light.test"]?.state)
    events.emit(event("light.test", "off"))
    runCurrent()
    assertEquals("off", snapshots.last().states["light.test"]?.state)
    events.emit(event("light.test", null))
    runCurrent()
    assertFalse(snapshots.last().states.containsKey("light.test"))
    assertEquals("5", snapshots.last().states["sensor.other"]?.state)
    assertEquals(1, fetches)
    collector.cancel()
  }

  @Test
  fun retainsEventReceivedWhileInitialSnapshotIsInFlight() = runTest {
    val events = MutableSharedFlow<JsonObject>()
    val initial = CompletableDeferred<HaSnapshot>()
    val snapshots = mutableListOf<HaSnapshot>()
    val collector = launch {
      stateSnapshots(events) { initial.await() }.collect { snapshots.add(it) }
    }
    runCurrent()
    events.emit(event("light.test", "on"))
    initial.complete(HaSnapshot(states = mapOf("light.test" to EntityState("light.test", "off"))))
    runCurrent()
    assertEquals(listOf("off", "on"), snapshots.map { it.states["light.test"]?.state })
    collector.cancel()
  }
}
