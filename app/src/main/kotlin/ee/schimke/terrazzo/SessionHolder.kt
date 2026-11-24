package ee.schimke.terrazzo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import ee.schimke.terrazzo.core.di.AppScope
import ee.schimke.terrazzo.core.logs.LogStore
import ee.schimke.terrazzo.core.session.HaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

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
@ContributesIntoMap(AppScope::class, binding<ViewModel>())
@ViewModelKey(SessionHolder::class)
class SessionHolder(private val closeScope: CoroutineScope) : ViewModel() {

  @Inject
  constructor(
    logStore: LogStore,
  ) : this(ProcessLifecycleOwner.get().lifecycleScope + logStore.coroutineExceptionHandler)

  var session: HaSession? by mutableStateOf(null)

  /**
   * The instance this install knows (its dashboard cache was restored) but holds no token for: a
   * restore from backup or a device transfer, which leaves the token vault behind. Set by the
   * cold-start resume in place of a session. The sign-in screen offers it pre-filled, and its cache
   * is kept so signing in again shows the dashboards at once. Cleared when anything signs in.
   */
  var reauthBaseUrl: String? by mutableStateOf(null)

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
