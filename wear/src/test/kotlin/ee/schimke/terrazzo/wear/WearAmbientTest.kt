package ee.schimke.terrazzo.wear

import androidx.wear.ambient.AmbientLifecycleObserver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * While the watch is ambient, live values land once per ambient update: the changes between them
 * are held, the latest one shows on the next update, and leaving ambient shows it at once.
 */
class WearAmbientTest {
  @Test
  fun valuesLandOnAmbientUpdates() =
    runTest(UnconfinedTestDispatcher()) {
      val ambient = WearAmbient()
      val values = MutableStateFlow(1)
      val seen = mutableListOf<Int>()
      val job = launch { values.heldWhileAmbient(ambient.state).toList(seen) }

      values.value = 2 // interactive: shown
      ambient.callback.onEnterAmbient(AmbientLifecycleObserver.AmbientDetails(false, false))
      values.value = 3 // ambient: held
      values.value = 4 // held, replaced
      ambient.callback.onUpdateAmbient() // the latest lands
      values.value = 5 // held
      ambient.callback.onExitAmbient() // shown at once
      values.value = 6 // interactive again

      assertEquals(listOf(1, 2, 4, 5, 6), seen)
      job.cancel()
    }

  @Test
  fun theStateFollowsTheSystem() {
    val ambient = WearAmbient()
    ambient.callback.onEnterAmbient(AmbientLifecycleObserver.AmbientDetails(true, true))
    assertEquals(WearAmbientState(ambient = true, tick = 1), ambient.state.value)
    ambient.callback.onUpdateAmbient()
    assertEquals(WearAmbientState(ambient = true, tick = 2), ambient.state.value)
    ambient.callback.onExitAmbient()
    assertEquals(false, ambient.state.value.ambient)
  }
}
