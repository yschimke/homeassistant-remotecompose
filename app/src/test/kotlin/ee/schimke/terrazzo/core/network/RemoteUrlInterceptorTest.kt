package ee.schimke.terrazzo.core.network

import ee.schimke.terrazzo.testing.FakeChain
import kotlin.test.Test
import kotlin.test.assertEquals
import okhttp3.Request

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
    val original = Request.Builder().url("http://192.168.1.5:8123/api/states?domain=light").build()
    val chain = FakeChain(original)

    interceptor.intercept(chain)

    assertEquals("https://remote.example:8443/api/states?domain=light", chain.lastUrl)
  }
}
