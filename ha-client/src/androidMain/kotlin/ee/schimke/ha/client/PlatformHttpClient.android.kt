package ee.schimke.ha.client

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient =
  HttpClient(OkHttp) {
    engine { preconfigured = SharedOkHttpClient.forKtor() }
    block()
  }
