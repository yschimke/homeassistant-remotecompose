package ee.schimke.ha.client

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

/**
 * `HttpClient(block)` on the platform's default engine, except that on Android the OkHttp engine
 * refuses the certificates [ee.schimke.ha.client.tls.ForbiddenCertificatesHostnameVerifier]
 * denylists. Use it wherever a client would otherwise be built without an engine.
 */
internal expect fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit = {}): HttpClient
