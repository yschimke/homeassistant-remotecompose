package ee.schimke.ha.client

import ee.schimke.ha.client.tls.ForbiddenCertificatesHostnameVerifier
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/**
 * The app's one [OkHttpClient]. Every OkHttp caller starts from [client]: anything needing
 * interceptors or other settings derives a variant with [OkHttpClient.newBuilder], which shares its
 * connection pool rather than opening its own, and Ktor engines use [forKtor].
 *
 * Carries [ForbiddenCertificatesHostnameVerifier], so every variant refuses the CVE-2026-111943
 * certificates.
 */
object SharedOkHttpClient {
  val client: OkHttpClient by lazy {
    OkHttpClient.Builder().hostnameVerifier(ForbiddenCertificatesHostnameVerifier()).build()
  }

  /**
   * [client] for a Ktor engine's `preconfigured`, with a dispatcher of its own: closing a Ktor
   * client shuts down its engine's dispatcher, which would otherwise stop [client] for every other
   * caller.
   */
  fun forKtor(): OkHttpClient = client.newBuilder().dispatcher(Dispatcher()).build()
}
