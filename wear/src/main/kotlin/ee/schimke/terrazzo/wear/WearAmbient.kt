package ee.schimke.terrazzo.wear

import androidx.wear.ambient.AmbientLifecycleObserver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Whether the watch shows the app in ambient (always-on), and its ambient updates so far: [tick]
 * counts the system's ambient updates, about one a minute, and entering ambient.
 */
data class WearAmbientState(val ambient: Boolean = false, val tick: Long = 0)

/**
 * The app's ambient mode, from the system ([callback], for `AmbientLifecycleObserver`) to [state]:
 * cards are played with the host's ambient flag set while the watch is ambient
 * (docs/architecture/ambient-mode.md), and their live values land on its ambient updates.
 */
class WearAmbient {
  private val mutableState = MutableStateFlow(WearAmbientState())
  val state: StateFlow<WearAmbientState> = mutableState.asStateFlow()

  val callback =
    object : AmbientLifecycleObserver.AmbientLifecycleCallback {
      override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
        mutableState.update { it.copy(ambient = true, tick = it.tick + 1) }
      }

      override fun onUpdateAmbient() {
        mutableState.update { it.copy(tick = it.tick + 1) }
      }

      override fun onExitAmbient() {
        mutableState.update { it.copy(ambient = false) }
      }
    }
}

/**
 * [this] as an ambient display should draw it: every value while interactive; while ambient, the
 * latest value once per ambient update ([WearAmbientState.tick]), so changes between updates don't
 * redraw the screen. Leaving ambient lets the latest value through at once.
 */
fun <T> Flow<T>.heldWhileAmbient(ambient: Flow<WearAmbientState>): Flow<T> =
  combine(this, ambient) { value, state -> value to state }
    .distinctUntilChanged { (_, old), (_, new) ->
      // Held: still ambient, and no update since the last value let through.
      old.ambient && new.ambient && old.tick == new.tick
    }
    .map { (value, _) -> value }
    // Entering or leaving ambient with nothing new isn't a change.
    .distinctUntilChanged()
