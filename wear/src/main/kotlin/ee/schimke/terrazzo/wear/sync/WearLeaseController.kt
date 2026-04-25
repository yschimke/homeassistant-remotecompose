package ee.schimke.terrazzo.wear.sync

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sends a `WearLease` heartbeat to the phone every
 * [HEARTBEAT_MS] milliseconds while the activity is foreground. Phone
 * uses arrival times as the lease window — while a recent lease exists
 * (within [WearSyncPaths.LEASE_WINDOW_MS]) phone streams deltas via
 * MessageClient; otherwise it batches into the `/wear/values` Proto
 * DataStore.
 *
 * Attached as a `LifecycleObserver` so the heartbeat starts on
 * `onStart`, stops on `onStop`, and we send a single foreground=false
 * lease right at `onStop` to release the lease early (rather than
 * letting the phone's window timeout run out).
 */
class WearLeaseController(
    private val scope: CoroutineScope,
    private val repo: WearSyncRepository,
) : DefaultLifecycleObserver {

    private var job: Job? = null

    override fun onStart(owner: LifecycleOwner) {
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
        scope.launch { repo.sendLease(foreground = false) }
    }

    companion object {
        /**
         * Heartbeat cadence. Tuned for ~1/3 of the lease window so a
         * single lost message doesn't trip the lease, and the phone
         * stays in streaming mode the whole time the user is on the
         * watch screen. 10 s window matches the phone-side lease check.
         */
        const val HEARTBEAT_MS: Long = 10_000L
    }
}
