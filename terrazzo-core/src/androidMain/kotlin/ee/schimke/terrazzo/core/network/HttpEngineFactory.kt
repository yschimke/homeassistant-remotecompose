package ee.schimke.terrazzo.core.network

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import ee.schimke.terrazzo.core.di.AppScope
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Builds a Ktor [HttpClientEngine] with the LAN connection policy installed as an OkHttp
 * interceptor. Singleton so the underlying OkHttp connection pool / dispatcher is shared across
 * `HaClient` and `AddonClient` instances.
 */
@SingleIn(AppScope::class)
@Inject
class HttpEngineFactory(private val policy: LanConnectionPolicy) {

  val engine: HttpClientEngine =
    OkHttp.create { addInterceptor(LanConnectionPolicyInterceptor(policy)) }
}
