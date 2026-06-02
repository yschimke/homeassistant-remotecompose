package ee.schimke.terrazzo.image

import ee.schimke.terrazzo.testing.FakeChain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import okhttp3.Request

class HaBearerAuthInterceptorTest {

  @Test
  fun attaches_bearer_for_matching_host() {
    val interceptor = HaBearerAuthInterceptor({ "ha.example.test" }, { "tok-123" })
    val chain =
      FakeChain(Request.Builder().url("https://ha.example.test/api/image_proxy/x").build())

    interceptor.intercept(chain)

    assertEquals("Bearer tok-123", chain.lastProceededRequest!!.header("Authorization"))
  }

  @Test
  fun matches_host_case_insensitively() {
    // OkHttp lowercases hosts internally, but explicit case-insensitive
    // matching keeps the contract resilient to upstream changes.
    val interceptor = HaBearerAuthInterceptor({ "HA.Example.Test" }, { "tok-x" })
    val chain = FakeChain(Request.Builder().url("https://ha.example.test/api/state").build())

    interceptor.intercept(chain)

    assertEquals("Bearer tok-x", chain.lastProceededRequest!!.header("Authorization"))
  }

  @Test
  fun does_not_attach_bearer_to_different_host() {
    // The HA dashboard may reference an external CDN icon. Sending
    // the HA bearer to that CDN would leak the user's credentials —
    // this test pins the host-only behaviour.
    val interceptor = HaBearerAuthInterceptor({ "ha.example.test" }, { "tok-x" })
    val chain = FakeChain(Request.Builder().url("https://cdn.example.test/icon.png").build())

    interceptor.intercept(chain)

    assertNull(chain.lastProceededRequest!!.header("Authorization"))
  }

  @Test
  fun does_not_attach_bearer_to_subdomain_of_ha_host() {
    val interceptor = HaBearerAuthInterceptor({ "ha.example.test" }, { "tok-x" })
    val chain = FakeChain(Request.Builder().url("https://api.ha.example.test/icon.png").build())

    interceptor.intercept(chain)

    assertNull(chain.lastProceededRequest!!.header("Authorization"))
  }

  @Test
  fun reads_token_provider_per_request() {
    // The provider lambda is the single seam that lets one ImageLoader
    // serve multiple sessions: a rotated token shows up on the very
    // next request without rebuilding the OkHttp client.
    var token: String? = "first"
    val interceptor = HaBearerAuthInterceptor({ "ha.example.test" }, { token })
    val urlBuilder = Request.Builder().url("https://ha.example.test/api/state")
    val chain1 = FakeChain(urlBuilder.build())
    interceptor.intercept(chain1)
    assertEquals("Bearer first", chain1.lastProceededRequest!!.header("Authorization"))

    token = "second"
    val chain2 = FakeChain(urlBuilder.build())
    interceptor.intercept(chain2)
    assertEquals("Bearer second", chain2.lastProceededRequest!!.header("Authorization"))

    token = null
    val chain3 = FakeChain(urlBuilder.build())
    interceptor.intercept(chain3)
    assertNull(chain3.lastProceededRequest!!.header("Authorization"))
  }
}
