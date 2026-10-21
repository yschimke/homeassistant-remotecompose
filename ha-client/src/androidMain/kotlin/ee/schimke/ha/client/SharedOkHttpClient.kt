package ee.schimke.ha.client

import ee.schimke.ha.client.tls.ForbiddenCertificatesHostnameVerifier
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.OkHttpClient

/**
 * The app's one [OkHttpClient]. Every OkHttp caller starts from [client]: anything needing
 * interceptors or other settings derives a variant with [OkHttpClient.newBuilder], which shares its
 * connection pool and dispatcher rather than opening its own, and Ktor engines take it as
 * `preconfigured`.
 *
 * Carries [ForbiddenCertificatesHostnameVerifier], so every variant refuses the CVE-2026-111943
 * certificates.
 */
object SharedOkHttpClient {
  val client: OkHttpClient by lazy {
    OkHttpClient.Builder().hostnameVerifier(ForbiddenCertificatesHostnameVerifier()).build()
  }

  /**
   * The Ktor engine over [client]. Never closed: closing an OkHttp engine shuts down its client's
   * dispatcher and evicts its connection pool, which here are [client]'s. An `HttpClient` built on
   * an engine instance doesn't own it, so closing that client is safe.
   */
  val ktorEngine: HttpClientEngine by lazy { OkHttp.create { preconfigured = client } }
}
