package ee.schimke.ha.client

import ee.schimke.ha.client.tls.ForbiddenCertificatesHostnameVerifier
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient =
  HttpClient(OkHttp) {
    engine { config { hostnameVerifier(ForbiddenCertificatesHostnameVerifier()) } }
    block()
  }
