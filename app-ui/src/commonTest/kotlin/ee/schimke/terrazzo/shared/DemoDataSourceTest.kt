@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ee.schimke.terrazzo.shared

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class DemoDataSourceTest {
  @Test
  fun fakeModeNeverReadsGateway() = runTest {
    val frames = demoFrames(DemoDataMode.FAKE) { error("Must not connect") }.toList()
    assertEquals(listOf(DemoFrame(DemoData.Fake, DemoDataStatus.FAKE)), frames)
  }

  @Test
  fun outageDropsLiveDataAndRecovers() = runTest {
    var reads = 0
    val live = DemoData(DemoDashboard.dashboard, DemoDashboard.snapshot.copy(states = emptyMap()))
    val frames =
      demoFrames(DemoDataMode.AUTO) {
          if (++reads == 2) error("Gateway stale")
          live
        }
        .take(4)
        .toList()
    assertEquals(
      listOf(
        DemoDataStatus.CONNECTING,
        DemoDataStatus.LIVE,
        DemoDataStatus.UNAVAILABLE,
        DemoDataStatus.LIVE,
      ),
      frames.map { it.status },
    )
    assertEquals(DemoData.Fake, frames[2].data)
    assertEquals(20_000, testScheduler.currentTime)
  }

  @Test
  fun hungRequestFallsBackAfterFourSeconds() = runTest {
    val frames = demoFrames(DemoDataMode.AUTO) { awaitCancellation() }.take(2).toList()
    assertEquals(DemoDataStatus.UNAVAILABLE, frames.last().status)
    assertEquals(4_000, testScheduler.currentTime)
  }

  @Test
  fun hostCancellationPropagates() = runTest {
    assertFailsWith<CancellationException> {
      demoFrames(DemoDataMode.AUTO) { throw CancellationException("Leaving demo") }.toList()
    }
  }

  @Test
  fun decodesPublicHaWireStatesIncludingLiveTimestamps() {
    val snapshot =
      decodeDemoStates(
        Json.parseToJsonElement(
            """[{"entity_id":"sensor.demo_temperature","state":"21.5","attributes":{"unit_of_measurement":"°C"},"last_changed":"2026-10-17T12:00:00Z","last_updated":"2026-10-17T12:01:00Z","context":{"user_id":null}}]"""
          )
          .jsonArray
      )
    val entity = snapshot.states.getValue("sensor.demo_temperature")
    assertEquals("21.5", entity.state)
    assertEquals("2026-10-17T12:01:00Z", entity.lastUpdated.toString())
  }
}
