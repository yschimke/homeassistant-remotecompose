package ee.schimke.terrazzo.wearsync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope

/**
 * Phone-side probe that reports whether any paired Wear node has
 * declared the wear-widgets capability — i.e. the watch is running
 * Wear OS 6+ where the Terrazzo wear app advertises widget support.
 *
 * The capability `terrazzo_wear_widgets` is added at runtime by the
 * watch (see `WearSyncRepository.start`) when its OS version supports
 * Wear widgets. Phone uses [CapabilityClient.getCapability] +
 * the [CapabilityClient.OnCapabilityChangedListener] to track updates
 * without polling.
 */
class WearCapabilityProbe(context: Context) {

    private val capabilityClient: CapabilityClient = Wearable.getCapabilityClient(context)

    /**
     * Cold flow that emits the latest "any reachable node supports
     * wear widgets" boolean. Re-subscribes to the capability listener
     * on each collector. Most consumers should hand this to
     * [stateIn] via [stateFlow].
     */
    val supportsWearWidgets: Flow<Boolean> = callbackFlow {
        val listener = CapabilityClient.OnCapabilityChangedListener { info ->
            trySend(info.nodes.isNotEmpty())
        }
        runCatching {
            capabilityClient.addListener(listener, CAPABILITY)
                .addOnFailureListener { error ->
                    // Devices without the Wearable component fail here
                    // with ApiException(API_NOT_CONNECTED). Silently
                    // emit false so the rest of the UI hides wear-only
                    // affordances instead of waiting forever.
                    Log.i(TAG, "capability listener unavailable: ${error.message}")
                    trySend(false)
                }
        }.onFailure {
            Log.i(TAG, "capability listener unavailable: ${it.message}")
            trySend(false)
        }
        // Seed with the current state — addListener doesn't replay.
        runCatching {
            capabilityClient
                .getCapability(CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener { info: CapabilityInfo ->
                    trySend(info.nodes.isNotEmpty())
                }
                .addOnFailureListener {
                    trySend(false)
                }
        }.onFailure {
            trySend(false)
        }
        awaitClose { runCatching { capabilityClient.removeListener(listener) } }
    }
        .map { it }
        .distinctUntilChanged()

    /**
     * Hot [StateFlow] form of [supportsWearWidgets]. Pass an app-scoped
     * [CoroutineScope] so the probe stays alive across UI rebinds. The
     * initial value is `false` until the first capability fetch
     * arrives.
     */
    fun stateFlow(scope: CoroutineScope): StateFlow<Boolean> =
        supportsWearWidgets.stateIn(scope, SharingStarted.Eagerly, false)

    companion object {
        /**
         * Capability name advertised by the wear app on Wear OS 6+
         * (Android 16, API 36+) where the new Wear Widgets framework
         * is available. Mirrored in `WearSyncRepository.start`.
         */
        const val CAPABILITY: String = "terrazzo_wear_widgets"

        private const val TAG = "WearCapability"
    }
}
