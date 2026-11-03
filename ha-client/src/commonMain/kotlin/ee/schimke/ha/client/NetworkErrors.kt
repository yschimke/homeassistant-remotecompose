package ee.schimke.ha.client

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpResponseValidator

/** A request never got an answer: offline, refused, or the server is restarting. */
class NetworkUnreachableException(cause: Throwable) :
  Exception("Couldn't reach the server; it retries by itself.", cause)

/**
 * Report a failed request as an [Exception], whatever the engine threw. On Wasm and JS, Ktor's
 * fetch engine throws a Kotlin `Error` ("Fail to fetch") when the server can't be reached, which
 * `catch (e: Exception)` lets through: a long-polling loop would die at the first network blip or
 * server deploy instead of retrying.
 */
fun HttpClientConfig<*>.networkErrorsAsExceptions() {
  HttpResponseValidator {
    handleResponseExceptionWithRequest { cause, _ ->
      if (cause !is Exception) throw NetworkUnreachableException(cause)
    }
  }
}
