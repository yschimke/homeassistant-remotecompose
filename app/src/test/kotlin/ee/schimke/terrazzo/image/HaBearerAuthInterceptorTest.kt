package ee.schimke.terrazzo.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class HaBearerAuthInterceptorTest {

  @Test
  fun attaches_bearer_for_matching_host() {
    val interceptor = HaBearerAuthInterceptor(haHost = "ha.example.test", accessToken = "tok-123")
    val chain = FakeChain(Request.Builder().url("https://ha.example.test/api/image_proxy/x").build())

    interceptor.intercept(chain)

    assertEquals("Bearer tok-123", chain.lastProceededRequest!!.header("Authorization"))
  }

  @Test
  fun matches_host_case_insensitively() {
    // OkHttp lowercases hosts internally, but explicit case-insensitive
    // matching keeps the contract resilient to upstream changes.
    val interceptor = HaBearerAuthInterceptor(haHost = "HA.Example.Test", accessToken = "tok-x")
    val chain = FakeChain(Request.Builder().url("https://ha.example.test/api/state").build())

    interceptor.intercept(chain)

    assertEquals("Bearer tok-x", chain.lastProceededRequest!!.header("Authorization"))
  }

  @Test
  fun does_not_attach_bearer_to_different_host() {
    // The HA dashboard may reference an external CDN icon. Sending
    // the HA bearer to that CDN would leak the user's credentials —
    // this test pins the host-only behaviour.
    val interceptor = HaBearerAuthInterceptor(haHost = "ha.example.test", accessToken = "tok-x")
    val chain = FakeChain(Request.Builder().url("https://cdn.example.test/icon.png").build())

    interceptor.intercept(chain)

    assertNull(chain.lastProceededRequest!!.header("Authorization"))
  }

  @Test
  fun does_not_attach_bearer_to_subdomain_of_ha_host() {
    val interceptor = HaBearerAuthInterceptor(haHost = "ha.example.test", accessToken = "tok-x")
    val chain =
      FakeChain(Request.Builder().url("https://api.ha.example.test/icon.png").build())

    interceptor.intercept(chain)

    assertNull(chain.lastProceededRequest!!.header("Authorization"))
  }

  private class FakeChain(private val request: Request) : Interceptor.Chain {
    var lastProceededRequest: Request? = null
      private set

    override fun request(): Request = request

    override fun proceed(request: Request): Response {
      lastProceededRequest = request
      return Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body("".toResponseBody("text/plain".toMediaType()))
        .build()
    }

    override fun call(): okhttp3.Call = error("not used")

    override fun connectTimeoutMillis(): Int = 0

    override fun connection(): okhttp3.Connection? = null

    override fun readTimeoutMillis(): Int = 0

    override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this

    override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this

    override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this

    override fun writeTimeoutMillis(): Int = 0
  }
}
