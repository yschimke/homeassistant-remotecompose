package ee.schimke.ha.client

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

internal actual fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient =
  HttpClient(block)
