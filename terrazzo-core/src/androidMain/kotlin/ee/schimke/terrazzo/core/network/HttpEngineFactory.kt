package ee.schimke.terrazzo.core.network

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import ee.schimke.terrazzo.core.di.AppScope
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Builds a Ktor [HttpClientEngine] for HA / addon traffic with two interceptors:
 *
 * 1. [RemoteUrlInterceptor] — rewrites requests aimed at the LAN base URL onto the instance's
 *    public ("external") URL when the current network can't reach the LAN one. Only applies if
 *    we've previously read `external_url` from HA via `get_config`.
 * 2. [LanConnectionPolicyInterceptor] — final gate: rejects requests still aimed at a private /
 *    plaintext destination the current network can't safely reach.
 *
 * Singleton so the underlying OkHttp connection pool / dispatcher is shared across `HaClient` and
 * `AddonClient` instances.
 */
@SingleIn(AppScope::class)
@Inject
class HttpEngineFactory(
  private val policy: LanConnectionPolicy,
  private val remoteUrlStore: RemoteUrlStore,
) {

  val engine: HttpClientEngine = OkHttp.create {
    addInterceptor(RemoteUrlInterceptor(policy, remoteUrlStore))
    addInterceptor(LanConnectionPolicyInterceptor(policy))
  }
}
