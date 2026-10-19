package ee.schimke.terrazzo.image

import ee.schimke.ha.client.haBearerAllowed
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that attaches `Authorization: Bearer …` to requests on the HA server's origin.
 * Reads both the base URL and the token through provider lambdas, so the OkHttp client can stay a
 * process-singleton: when [HaImageStack.setAuth] swaps the active session, the next request picks
 * up the new credentials with no client rebuild.
 *
 * Everything else (external CDN icons, third-party thumbnails, another service on the HA host's
 * other ports, plain http to an https instance) passes through unmodified so the bearer doesn't
 * leak: see [haBearerAllowed].
 */
internal class HaBearerAuthInterceptor(
  private val haBaseUrl: () -> String?,
  private val accessToken: () -> String?,
) : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    val base = haBaseUrl()
    val token = accessToken()
    val outgoing =
      if (base != null && token != null && haBearerAllowed(base, request.url.toString())) {
        request.newBuilder().header("Authorization", "Bearer $token").build()
      } else {
        request
      }
    return chain.proceed(outgoing)
  }
}
