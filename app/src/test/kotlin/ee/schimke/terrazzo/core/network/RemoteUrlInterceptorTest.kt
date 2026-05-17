package ee.schimke.terrazzo.core.network

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteUrlInterceptorTest {

    @Test
    fun allow_passes_request_through_unchanged() {
        val interceptor =
            RemoteUrlInterceptor(
                check = { LanConnectionPolicy.Verdict.Allow },
                externalFor = { error("should not be consulted when LAN is allowed") },
            )
        val original = Request.Builder().url("http://homeassistant.local:8123/api").build()
        val chain = FakeChain(original)

        interceptor.intercept(chain)

        assertEquals("http://homeassistant.local:8123/api", chain.lastUrl)
    }

    @Test
    fun deny_with_external_target_rewrites_scheme_host_and_port() {
        val target =
            RemoteUrlStore.ExternalTarget(scheme = "https", host = "abc.ui.nabu.casa", port = 443)
        val interceptor =
            RemoteUrlInterceptor(
                check = { LanConnectionPolicy.Verdict.Deny("cellular") },
                externalFor = { host ->
                    assertEquals("homeassistant.local", host)
                    target
                },
            )
        val original = Request.Builder().url("http://homeassistant.local:8123/api/x").build()
        val chain = FakeChain(original)

        interceptor.intercept(chain)

        assertEquals("https://abc.ui.nabu.casa/api/x", chain.lastUrl)
    }

    @Test
    fun deny_without_external_target_passes_through_for_lan_gate_to_reject() {
        // No public URL on file → leave the request as-is. The downstream
        // [LanConnectionPolicyInterceptor] is responsible for failing
        // closed; this interceptor only rewrites when it has a target.
        val interceptor =
            RemoteUrlInterceptor(
                check = { LanConnectionPolicy.Verdict.Deny("cellular") },
                externalFor = { null },
            )
        val original = Request.Builder().url("http://homeassistant.local:8123/api/x").build()
        val chain = FakeChain(original)

        interceptor.intercept(chain)

        assertEquals("http://homeassistant.local:8123/api/x", chain.lastUrl)
    }

    @Test
    fun rewrite_preserves_path_and_query() {
        val target =
            RemoteUrlStore.ExternalTarget(scheme = "https", host = "remote.example", port = 8443)
        val interceptor =
            RemoteUrlInterceptor(
                check = { LanConnectionPolicy.Verdict.Deny("cellular") },
                externalFor = { target },
            )
        val original =
            Request.Builder().url("http://192.168.1.5:8123/api/states?domain=light").build()
        val chain = FakeChain(original)

        interceptor.intercept(chain)

        assertEquals("https://remote.example:8443/api/states?domain=light", chain.lastUrl)
    }

    private class FakeChain(private val request: Request) : Interceptor.Chain {
        var lastUrl: String? = null
            private set

        override fun request(): Request = request

        override fun proceed(request: Request): Response {
            lastUrl = request.url.toString()
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
