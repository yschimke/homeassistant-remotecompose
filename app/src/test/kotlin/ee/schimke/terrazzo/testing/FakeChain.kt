package ee.schimke.terrazzo.testing

import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Minimal [Interceptor.Chain] for unit-testing a single OkHttp [Interceptor] in isolation.
 *
 * [proceed] records the request it was handed (after any rewrite the interceptor applied) and
 * returns a canned response; the timeout / connection members are stubbed since interceptors under
 * test don't consult them. Inspect [proceeded], [lastUrl], or [lastProceededRequest] afterwards.
 */
class FakeChain(private val request: Request, private val responseCode: Int = 200) :
  Interceptor.Chain {
  var lastProceededRequest: Request? = null
    private set

  /** True once the interceptor forwarded the request downstream. */
  val proceeded: Boolean
    get() = lastProceededRequest != null

  /** The forwarded request's URL, or null if the interceptor short-circuited. */
  val lastUrl: String?
    get() = lastProceededRequest?.url?.toString()

  override fun request(): Request = request

  override fun proceed(request: Request): Response {
    lastProceededRequest = request
    return Response.Builder()
      .request(request)
      .protocol(Protocol.HTTP_1_1)
      .code(responseCode)
      .message("OK")
      .body("".toResponseBody("text/plain".toMediaType()))
      .build()
  }

  override fun call(): Call = error("not used")

  override fun connectTimeoutMillis(): Int = 0

  override fun connection(): Connection? = null

  override fun readTimeoutMillis(): Int = 0

  override fun withConnectTimeout(timeout: Int, unit: TimeUnit) = this

  override fun withReadTimeout(timeout: Int, unit: TimeUnit) = this

  override fun withWriteTimeout(timeout: Int, unit: TimeUnit) = this

  override fun writeTimeoutMillis(): Int = 0
}
