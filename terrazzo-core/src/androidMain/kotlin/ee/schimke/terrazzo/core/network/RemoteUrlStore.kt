package ee.schimke.terrazzo.core.network

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import ee.schimke.terrazzo.core.di.AppScope
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Per-instance public ("external") URL persisted after we read HA's
 * `get_config` payload. Pairs the URL the user logged in with (their LAN
 * baseUrl) to the public URL HA exposes for remote access — usually a
 * Nabu Casa hostname under `ui.nabu.casa` or a self-hosted reverse
 * proxy.
 *
 * Two access paths:
 *   - `Flow` / suspend reads for UI surfaces (Settings).
 *   - Synchronous lookup via [externalHostFor] for the OkHttp interceptor
 *     that rewrites outgoing requests to the public host when the LAN
 *     destination isn't reachable from the current network. The
 *     interceptor runs on the network thread and can't suspend, so the
 *     store keeps a process-local map mirrored to disk.
 */
@SingleIn(AppScope::class)
@Inject
class RemoteUrlStore(private val context: Context) {

    // Mirrors disk into an O(1) lookup the interceptor can consult per
    // request without touching DataStore. Keys are the *host* of the
    // login baseUrl; lookup is per outgoing request host.
    private val cache = ConcurrentHashMap<String, ExternalTarget>()

    private val hydrateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Background-hydrate so the interceptor sees mapped hosts ASAP
        // without blocking app start on a DataStore read. Until this
        // completes the interceptor returns null (= "no rewrite"),
        // which is the same behaviour as a fresh install.
        hydrateScope.launch {
            context.store.data.first().asMap().forEach { (key, value) ->
                val baseUrl = key.name.removePrefix(KEY_PREFIX)
                val host = uriHost(baseUrl) ?: return@forEach
                val target = parseTarget(value as? String ?: return@forEach) ?: return@forEach
                cache[host] = target
            }
        }
    }

    /**
     * Persist the external URL HA reports for [baseUrl]. Pass null to clear (the user removed
     * their `external_url` in HA, or sign-out wiped the slot).
     */
    suspend fun setExternalUrl(baseUrl: String, externalUrl: String?) {
        val host = uriHost(baseUrl) ?: return
        val target = externalUrl?.let(::parseTarget)
        context.store.edit { prefs ->
            val key = keyOf(baseUrl)
            if (externalUrl.isNullOrBlank()) prefs.remove(key) else prefs[key] = externalUrl
        }
        if (target != null) cache[host] = target else cache.remove(host)
    }

    fun externalUrl(baseUrl: String): Flow<String?> =
        context.store.data.map { it[keyOf(baseUrl)] }

    suspend fun externalUrlNow(baseUrl: String): String? =
        context.store.data.first()[keyOf(baseUrl)]

    /**
     * Cached lookup keyed by request host. Returns the parsed `scheme/host/port` for the public
     * URL the interceptor should rewrite to, or null if we have no public URL on file for that
     * host. Runs in O(1) on the network thread.
     */
    fun externalHostFor(internalHost: String): ExternalTarget? =
        cache[internalHost.lowercase()]

    /** Wipe a single instance's public URL — called from sign-out. */
    suspend fun clear(baseUrl: String) {
        setExternalUrl(baseUrl, null)
    }

    data class ExternalTarget(val scheme: String, val host: String, val port: Int)

    private fun keyOf(baseUrl: String) = stringPreferencesKey("$KEY_PREFIX${normalize(baseUrl)}")

    private companion object {
        private val Context.store by preferencesDataStore(name = "terrazzo_remote_urls")
        const val KEY_PREFIX = "external_url."

        fun normalize(baseUrl: String): String = baseUrl.trim().removeSuffix("/")

        fun uriHost(url: String): String? =
            runCatching { URI(normalize(url)).host?.lowercase() }.getOrNull()

        fun parseTarget(url: String): ExternalTarget? = runCatching {
            val u = URI(url.trim())
            val scheme = u.scheme?.lowercase() ?: return@runCatching null
            val host = u.host?.lowercase() ?: return@runCatching null
            val port = if (u.port == -1) defaultPort(scheme) else u.port
            ExternalTarget(scheme, host, port)
        }.getOrNull()

        fun defaultPort(scheme: String): Int = when (scheme) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
    }
}
