package ee.schimke.ha.client

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine

/**
 * How every client in this library makes its Ktor [HttpClient]: the seam an app uses to supply its
 * own engine and configuration on each Kotlin Multiplatform target.
 *
 * The library calls [create] with the plugins it needs (WebSockets, content negotiation, timeouts,
 * auth headers); a factory applies its own configuration around them. The client [create] returns
 * is owned by the library, which closes it when its owner closes. So a factory that wants clients
 * to share a connection pool hands each one the same engine, and `HttpClient(engine) { … }` never
 * closes an engine it was given.
 *
 * ```
 * // Android: an app's own OkHttpClient (custom CA for a self-signed Home Assistant, client
 * // certificates, interceptors), shared by every client.
 * val engine = OkHttp.create { preconfigured = myOkHttpClient }
 * val httpClients = HaHttpClientFactory { configure ->
 *   HttpClient(engine) {
 *     install(Logging)
 *     configure()
 *   }
 * }
 * HaClient(config, httpClients = httpClients)
 * ```
 *
 * The same shape works with Darwin (`Darwin.create { handleChallenge(…) }`), CIO / Java on the JVM,
 * and Js on Wasm.
 */
fun interface HaHttpClientFactory {
  fun create(configure: HttpClientConfig<*>.() -> Unit): HttpClient

  companion object {
    /**
     * The platform default: on Android the OkHttp engine over `SharedOkHttpClient` (which refuses
     * the forbidden certificates), elsewhere Ktor's engine for the target.
     */
    val Platform: HaHttpClientFactory = HaHttpClientFactory { platformHttpClient(it) }

    /** Every client on [engine], which the library never closes. */
    fun of(engine: HttpClientEngine): HaHttpClientFactory = HaHttpClientFactory {
      HttpClient(engine, it)
    }
  }
}

/** [factory] if given, else this engine, else [HaHttpClientFactory.Platform]. */
internal fun HttpClientEngine?.orFactory(factory: HaHttpClientFactory?): HaHttpClientFactory =
  factory ?: this?.let(HaHttpClientFactory::of) ?: HaHttpClientFactory.Platform
