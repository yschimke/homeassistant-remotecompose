package ee.schimke.terrazzo.wear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.wear.compose.foundation.AmbientMode
import androidx.wear.compose.foundation.AmbientModeManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * While the watch is ambient, live values land once per ambient update: the changes between them
 * are held, the latest one shows on the next update, and leaving ambient shows it at once.
 */
class WearAmbientTest {
  /** The system's ambient state, as Wear Compose's manager reports it. */
  private class FakeAmbient : AmbientModeManager {
    var mode: AmbientMode by mutableStateOf(AmbientMode.Interactive)
    private val ticks = MutableSharedFlow<Unit>()

    override val currentAmbientMode: AmbientMode
      get() = mode

    override suspend fun withAmbientTick(block: () -> Unit) {
      ticks.first()
      block()
    }

    suspend fun tick() = ticks.emit(Unit)
  }

  @Test
  fun valuesLandOnAmbientUpdates() =
    runTest(UnconfinedTestDispatcher()) {
      val ambient = FakeAmbient()
      fun enter(mode: AmbientMode) {
        ambient.mode = mode
        Snapshot.sendApplyNotifications()
        runCurrent()
      }
      val values = MutableStateFlow(1)
      val seen = mutableListOf<Int>()
      val job = launch { values.heldWhileAmbient(ambient).toList(seen) }
      runCurrent()

      values.value = 2 // interactive: shown
      enter(
        AmbientMode.Ambient(isBurnInProtectionRequired = false, isLowBitAmbientSupported = false)
      )
      values.value = 3 // ambient: held
      values.value = 4 // held, replaced
      ambient.tick() // the latest lands
      values.value = 5 // held
      enter(AmbientMode.Interactive) // shown at once
      values.value = 6 // interactive again
      runCurrent()

      assertEquals(listOf(1, 2, 4, 5, 6), seen)
      job.cancel()
    }
}
