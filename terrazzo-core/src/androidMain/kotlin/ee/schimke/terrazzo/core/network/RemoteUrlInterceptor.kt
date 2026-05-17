package ee.schimke.terrazzo.core.network

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Rewrites outgoing requests from an HA instance's internal (LAN) URL to its public
 * (`external_url`) URL when the LAN destination isn't usable from the current network.
 *
 * Examples:
 *  * On home Wi-Fi, the session base is `http://homeassistant.local:8123`.
 *    [LanConnectionPolicy] allows it → request goes through unchanged.
 *  * On cellular, the same `http://homeassistant.local:8123` is denied (plaintext
 *    LAN over cellular). If the store knows the public URL HA reports
 *    (`https://abc.ui.nabu.casa`), this interceptor rewrites the request to
 *    `https://abc.ui.nabu.casa/...`. HA accepts the same OAuth bearer over either
 *    URL, so headers (including `Authorization`) ride along unchanged.
 *
 * Sits before [LanConnectionPolicyInterceptor] so the rewrite happens first and the
 * downstream policy gate sees an already-public, allow-listed URL.
 *
 * Does nothing when:
 *  * The request URL is already on the public host (e.g. the user manually entered the
 *    Nabu Casa URL at login).
 *  * No public URL is on file for the request host.
 *  * The LAN policy would already allow the request.
 */
class RemoteUrlInterceptor(
  private val check: (String) -> LanConnectionPolicy.Verdict,
  private val externalFor: (host: String) -> RemoteUrlStore.ExternalTarget?,
) : Interceptor {

  constructor(
    policy: LanConnectionPolicy,
    store: RemoteUrlStore,
  ) : this(policy::check, store::externalHostFor)

  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    val url = request.url
    if (check(url.toString()) is LanConnectionPolicy.Verdict.Allow) {
      return chain.proceed(request)
    }
    val target = externalFor(url.host) ?: return chain.proceed(request)
    val rewritten = url.rewriteTo(target)
    return chain.proceed(request.newBuilder().url(rewritten).build())
  }

  private fun HttpUrl.rewriteTo(target: RemoteUrlStore.ExternalTarget): HttpUrl =
    newBuilder()
      .scheme(target.scheme)
      .host(target.host)
      .port(target.port)
      .build()
}
