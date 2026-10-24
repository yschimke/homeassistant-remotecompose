package ee.schimke.terrazzo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ee.schimke.terrazzo.core.session.HaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Owns the active [HaSession] above the composition, in the Activity's `ViewModelStore`, so a
 * configuration change (rotation, fold, window resize) keeps the same live session instead of
 * rebuilding it. Held in `remember`, the session was dropped on every recreation: its WebSocket
 * stayed open with nothing to close it, the UI fell back to a fresh cache-only stub, and the
 * cold-start resume ran again.
 *
 * [closeScope] outlives the holder — [viewModelScope] is already cancelled by the time [onCleared]
 * runs, and [HaSession.close] suspends.
 */
class SessionHolder(private val closeScope: CoroutineScope) : ViewModel() {
  var session: HaSession? by mutableStateOf(null)

  private var started = false

  /**
   * Seeds [session] from [initial] and runs [resume] in [viewModelScope], once per holder. A
   * recreated Activity calls this again and gets a no-op, so neither the cache-only stub nor the
   * token refresh is repeated, and an in-flight refresh isn't cancelled by the rotation.
   */
  fun start(initial: () -> HaSession?, resume: suspend (initial: HaSession?) -> Unit) {
    if (started) return
    started = true
    val first = initial()
    session = first
    viewModelScope.launch { resume(first) }
  }

  override fun onCleared() {
    val last = session ?: return
    session = null
    closeScope.launch { last.close() }
  }
}
