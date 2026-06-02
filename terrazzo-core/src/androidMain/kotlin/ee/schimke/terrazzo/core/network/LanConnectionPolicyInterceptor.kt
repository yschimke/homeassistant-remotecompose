package ee.schimke.terrazzo.core.network

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that re-checks [LanConnectionPolicy] for every outgoing request.
 *
 * Defense in depth: the connect-time check in `TerrazzoApp` blocks the *initial* sign-in to a bad
 * URL, but the session keeps making requests for the lifetime of the app — across network changes
 * (Wi-Fi → cellular when the user leaves home), token refreshes, dashboard reloads, and addon card
 * fetches. Without this interceptor a session signed in on Wi-Fi would happily keep talking
 * cleartext over LTE to a private IP that doesn't resolve to anything on the carrier.
 *
 * Cheap by design — the policy's transport answer is cached and refreshed by a `NetworkCallback`,
 * so this runs in constant time per request.
 *
 * Constructed with a check function rather than the policy class so tests don't need to spin up a
 * `ConnectivityManager` to exercise it.
 */
class LanConnectionPolicyInterceptor(private val check: (String) -> LanConnectionPolicy.Verdict) :
  Interceptor {

  constructor(policy: LanConnectionPolicy) : this(policy::check)

  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    val url = request.url.toString()
    return when (val verdict = check(url)) {
      LanConnectionPolicy.Verdict.Allow -> chain.proceed(request)
      is LanConnectionPolicy.Verdict.Deny ->
        throw IOException("Blocked by LanConnectionPolicy: ${verdict.reason}")
    }
  }
}
