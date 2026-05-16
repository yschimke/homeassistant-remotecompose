package ee.schimke.terrazzo.image

import android.content.Context
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import ee.schimke.terrazzo.core.network.LanConnectionPolicy
import ee.schimke.terrazzo.core.network.LanConnectionPolicyInterceptor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Build a Coil [ImageLoader] scoped to a single HA session.
 *
 * Two concerns the singleton `ImageLoader` doesn't cover:
 *
 *  - HA's `image_proxy` URLs, addon icons, and custom-integration
 *    paths need `Authorization: Bearer <accessToken>`. We attach it
 *    only when the request's host matches [baseUrl]'s host, so the
 *    token doesn't leak when an HA dashboard references an external
 *    CDN. `camera_proxy` paths carry HA's `?token=` signed-path
 *    token and don't need the header, but adding it on a same-host
 *    request is harmless either way.
 *  - LAN trust: image fetches off the HA server must observe the
 *    same [LanConnectionPolicy] as the WebSocket / REST stack —
 *    otherwise a session that signed in on Wi-Fi could keep talking
 *    cleartext to a private IP after the device switches to
 *    cellular.
 *
 * [accessToken] may be `null` for demo / offline sessions. In that
 * case we still install the LAN-policy interceptor, but skip the
 * bearer.
 */
fun haSessionImageLoader(
  context: Context,
  baseUrl: String,
  accessToken: String?,
  lanPolicy: LanConnectionPolicy,
): ImageLoader {
  val haHost = baseUrl.toHttpUrlOrNull()?.host
  val client =
    OkHttpClient.Builder()
      .addInterceptor(LanConnectionPolicyInterceptor(lanPolicy))
      .apply {
        if (accessToken != null && !haHost.isNullOrEmpty()) {
          addInterceptor(HaBearerAuthInterceptor(haHost, accessToken))
        }
      }
      .build()
  return ImageLoader.Builder(context)
    .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
    .build()
}

/**
 * OkHttp interceptor that attaches `Authorization: Bearer …` to requests
 * whose host matches the HA server's host. Other hosts (CDN icons,
 * external thumbnails) pass through unmodified so the bearer doesn't
 * leak off-server.
 */
internal class HaBearerAuthInterceptor(
  private val haHost: String,
  private val accessToken: String,
) : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    val outgoing =
      if (request.url.host.equals(haHost, ignoreCase = true)) {
        request.newBuilder().header("Authorization", "Bearer $accessToken").build()
      } else {
        request
      }
    return chain.proceed(outgoing)
  }
}
