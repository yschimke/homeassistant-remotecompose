package ee.schimke.terrazzo.wear.sync

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sends a `WearLease` heartbeat to the phone every [HEARTBEAT_MS] milliseconds while the activity
 * is foreground. Phone uses arrival times as the lease window — while a recent lease exists (within
 * [WearSyncPaths.LEASE_WINDOW_MS]) phone streams deltas via MessageClient; otherwise it batches
 * into the `/wear/values` Proto DataStore.
 *
 * Attached as a `LifecycleObserver` so the heartbeat starts on `onStart`, stops on `onStop`, and we
 * send a single foreground=false lease right at `onStop` to release the lease early (rather than
 * letting the phone's window timeout run out).
 */
class WearLeaseController(private val scope: CoroutineScope, private val repo: WearSyncRepository) :
  DefaultLifecycleObserver {

  private var job: Job? = null
  private var directJob: Job? = null

  override fun onStart(owner: LifecycleOwner) {
    // While the app is open, read the cards' values from Home Assistant whenever the phone's go
    // stale (the repository decides; it reads at most once a minute). The first check waits one
    // heartbeat: a phone in range starts streaming by then, so nothing is read twice.
    directJob?.cancel()
    directJob = scope.launch {
      delay(HEARTBEAT_MS)
      while (true) {
        repo.refreshDirect()
        delay(DIRECT_CHECK_MS)
      }
    }
    job?.cancel()
    job = scope.launch {
      // First lease right away so the phone starts streaming
      // without waiting on the heartbeat tick.
      repo.sendLease(foreground = true)
      while (true) {
        delay(HEARTBEAT_MS)
        repo.sendLease(foreground = true)
      }
    }
  }

  override fun onStop(owner: LifecycleOwner) {
    job?.cancel()
    job = null
    directJob?.cancel()
    directJob = null
    scope.launch { repo.sendLease(foreground = false) }
  }

  companion object {
    /**
     * Heartbeat cadence. Tuned for ~1/3 of the lease window so a single lost message doesn't trip
     * the lease, and the phone stays in streaming mode the whole time the user is on the watch
     * screen. 10 s window matches the phone-side lease check.
     */
    const val HEARTBEAT_MS: Long = 10_000L

    /** How often the open app checks whether the phone's values have gone stale. */
    const val DIRECT_CHECK_MS: Long = 30_000L
  }
}
