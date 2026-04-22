package ee.schimke.terrazzo.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * One HA instance visible on the LAN.
 *
 * Fields come from the mDNS / DNS-SD TXT record HA publishes via its
 * `zeroconf` integration (enabled by default).
 */
data class HaInstance(
    /** Service name, e.g. `"Home"`. */
    val name: String,
    /** Resolved base URL, e.g. `"http://homeassistant.local:8123"`. */
    val baseUrl: String,
    /** HA version from TXT, if present. */
    val version: String? = null,
)

/**
 * Scan the current network for Home Assistant instances advertising
 * `_home-assistant._tcp`. Emits instances as they're resolved. The
 * flow is cold — collecting it starts a discovery; cancellation stops
 * it.
 *
 * Reference: https://www.home-assistant.io/integrations/zeroconf/
 *
 * HA advertises under `_home-assistant._tcp.` with a TXT record
 * containing at least `base_url`, `version`, `internal_url`. We trust
 * `internal_url` if present (user's configured LAN URL); otherwise
 * build `http://<host>:<port>` from the resolved info.
 */
class HaDiscovery(context: Context) {

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    fun scan(): Flow<HaInstance> = channelFlow {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery start failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(service: NsdServiceInfo) {
                launch { resolve(service)?.let { trySend(it) } }
            }

            override fun onServiceLost(service: NsdServiceInfo) {}
        }

        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    // TODO(api 36 migration): replace `resolveService` with
    //  `registerServiceInfoCallback` — deprecated since API 34, but the
    //  callback-based replacement requires an Executor + keeps the
    //  listener open for lifecycle-aware updates; not worth the extra
    //  ceremony until we want live "instance went away" events.
    @Suppress("DEPRECATION")
    private suspend fun resolve(service: NsdServiceInfo): HaInstance? =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            nsd.resolveService(
                service,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "resolve failed ${s.serviceName}: $errorCode")
                        if (!cont.isCompleted) cont.resumeWith(Result.success(null))
                    }

                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        if (cont.isCompleted) return
                        val attrs = resolved.attributes
                        val address = resolved.hostAddresses.firstOrNull()?.hostAddress
                        val baseUrl = attrs["internal_url"]?.decode()
                            ?: attrs["base_url"]?.decode()
                            ?: "http://$address:${resolved.port}"
                        cont.resumeWith(
                            Result.success(
                                HaInstance(
                                    name = resolved.serviceName,
                                    baseUrl = baseUrl,
                                    version = attrs["version"]?.decode(),
                                ),
                            ),
                        )
                    }
                },
            )
        }

    private fun ByteArray?.decode(): String? =
        this?.toString(Charsets.UTF_8)

    private companion object {
        const val TAG = "HaDiscovery"
        const val SERVICE_TYPE = "_home-assistant._tcp."
    }
}
