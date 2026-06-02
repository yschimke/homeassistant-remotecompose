package ee.schimke.terrazzo.core.network

import ee.schimke.terrazzo.testing.FakeChain
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import okhttp3.Request

class LanConnectionPolicyInterceptorTest {

  @Test
  fun allow_passes_request_through() {
    val interceptor = LanConnectionPolicyInterceptor { LanConnectionPolicy.Verdict.Allow }
    val chain = FakeChain(Request.Builder().url("http://192.168.1.50:8123/api").build())

    val response = interceptor.intercept(chain)

    assertEquals(200, response.code)
    assertTrue(chain.proceeded)
  }

  @Test
  fun deny_throws_ioexception_with_reason() {
    val reason = "Refusing plaintext HTTP over cellular"
    val interceptor = LanConnectionPolicyInterceptor { LanConnectionPolicy.Verdict.Deny(reason) }
    val chain = FakeChain(Request.Builder().url("http://192.168.1.50:8123/api").build())

    val ex = assertFailsWith<IOException> { interceptor.intercept(chain) }

    assertTrue(ex.message!!.contains(reason))
    assertEquals(false, chain.proceeded)
  }

  @Test
  fun interceptor_passes_request_url_to_policy() {
    var seenUrl: String? = null
    val interceptor = LanConnectionPolicyInterceptor {
      seenUrl = it
      LanConnectionPolicy.Verdict.Allow
    }
    val chain = FakeChain(Request.Builder().url("https://nabu.casa/api/x").build())

    interceptor.intercept(chain)

    assertEquals("https://nabu.casa/api/x", seenUrl)
  }
}
