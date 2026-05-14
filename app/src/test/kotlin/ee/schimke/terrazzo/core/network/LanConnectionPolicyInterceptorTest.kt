package ee.schimke.terrazzo.core.network

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
        val interceptor =
            LanConnectionPolicyInterceptor { LanConnectionPolicy.Verdict.Deny(reason) }
        val chain = FakeChain(Request.Builder().url("http://192.168.1.50:8123/api").build())

        val ex = assertFailsWith<IOException> { interceptor.intercept(chain) }

        assertTrue(ex.message!!.contains(reason))
        assertEquals(false, chain.proceeded)
    }

    @Test
    fun interceptor_passes_request_url_to_policy() {
        var seenUrl: String? = null
        val interceptor =
            LanConnectionPolicyInterceptor {
                seenUrl = it
                LanConnectionPolicy.Verdict.Allow
            }
        val chain = FakeChain(Request.Builder().url("https://nabu.casa/api/x").build())

        interceptor.intercept(chain)

        assertEquals("https://nabu.casa/api/x", seenUrl)
    }

    private class FakeChain(private val request: Request) : Interceptor.Chain {
        var proceeded: Boolean = false
            private set

        override fun request(): Request = request

        override fun proceed(request: Request): Response {
            proceeded = true
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("".toResponseBody("text/plain".toMediaType()))
                .build()
        }

        // Stubs — unused by this interceptor.
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
