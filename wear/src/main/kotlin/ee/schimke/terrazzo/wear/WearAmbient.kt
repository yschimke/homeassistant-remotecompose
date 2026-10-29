package ee.schimke.terrazzo.wear

import androidx.compose.runtime.snapshotFlow
import androidx.wear.compose.foundation.AmbientMode
import androidx.wear.compose.foundation.AmbientModeManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/**
 * Whether the watch shows the app in ambient (always-on). Snapshot state: read it in an effect or a
 * `snapshotFlow`, not in composition, so going in and out of ambient recomposes nothing.
 */
val AmbientModeManager.isAmbient: Boolean
  get() = currentAmbientMode is AmbientMode.Ambient

/**
 * [this] as an ambient display should draw it: every value while interactive; while ambient, the
 * latest value once per ambient update ([AmbientModeManager.withAmbientTick], about one a minute),
 * so changes between updates don't redraw the screen. Leaving ambient lets the latest value through
 * at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> StateFlow<T>.heldWhileAmbient(manager: AmbientModeManager): Flow<T> = snapshotFlow {
  manager.isAmbient
}
  .flatMapLatest { ambient ->
    if (!ambient) this
    else
      flow {
        while (true) {
          emit(value)
          manager.withAmbientTick {}
        }
      }
  }
  // Entering or leaving ambient with nothing new isn't a change.
  .distinctUntilChanged()
