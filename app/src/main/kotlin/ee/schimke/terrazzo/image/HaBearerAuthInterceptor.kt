package ee.schimke.terrazzo.image

import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that attaches `Authorization: Bearer …` to requests whose host matches the HA
 * server's. Reads both the host and the token through provider lambdas, so the OkHttp client can
 * stay a process-singleton: when [HaImageStack.setAuth] swaps the active session, the next request
 * picks up the new credentials with no client rebuild.
 *
 * Off-host requests (external CDN icons, third-party thumbnails) pass through unmodified so the
 * bearer doesn't leak.
 */
internal class HaBearerAuthInterceptor(
  private val haHost: () -> String?,
  private val accessToken: () -> String?,
) : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    val host = haHost()
    val token = accessToken()
    val outgoing =
      if (host != null && token != null && request.url.host.equals(host, ignoreCase = true)) {
        request.newBuilder().header("Authorization", "Bearer $token").build()
      } else {
        request
      }
    return chain.proceed(outgoing)
  }
}
