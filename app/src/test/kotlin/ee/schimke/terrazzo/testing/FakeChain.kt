package ee.schimke.terrazzo.testing

import java.net.Proxy
import java.net.ProxySelector
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Authenticator
import okhttp3.Cache
import okhttp3.Call
import okhttp3.CertificatePinner
import okhttp3.Connection
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Minimal [Interceptor.Chain] for unit-testing a single OkHttp [Interceptor] in isolation.
 *
 * [proceed] records the request it was handed (after any rewrite the interceptor applied) and
 * returns a canned response; the timeout / connection members are stubbed since interceptors under
 * test don't consult them. Inspect [proceeded], [lastUrl], or [lastProceededRequest] afterwards.
 */
class FakeChain(private val request: Request, private val responseCode: Int = 200) :
  Interceptor.Chain {
  var lastProceededRequest: Request? = null
    private set

  /** True once the interceptor forwarded the request downstream. */
  val proceeded: Boolean
    get() = lastProceededRequest != null

  /** The forwarded request's URL, or null if the interceptor short-circuited. */
  val lastUrl: String?
    get() = lastProceededRequest?.url?.toString()

  override fun request(): Request = request

  override fun proceed(request: Request): Response {
    lastProceededRequest = request
    return Response.Builder()
      .request(request)
      .protocol(Protocol.HTTP_1_1)
      .code(responseCode)
      .message("OK")
      .body("".toResponseBody("text/plain".toMediaType()))
      .build()
  }

  override fun call(): Call = error("not used")

  override fun connectTimeoutMillis(): Int = 0

  override fun connection(): Connection? = null

  override fun readTimeoutMillis(): Int = 0

  override fun withConnectTimeout(timeout: Int, unit: TimeUnit) = this

  override fun withReadTimeout(timeout: Int, unit: TimeUnit) = this

  override fun withWriteTimeout(timeout: Int, unit: TimeUnit) = this

  override fun writeTimeoutMillis(): Int = 0

  override val followRedirects: Boolean = true

  override val followSslRedirects: Boolean = true

  override val dns: Dns = Dns.SYSTEM

  override val socketFactory: SocketFactory = SocketFactory.getDefault()

  override val retryOnConnectionFailure: Boolean = true

  override val authenticator: Authenticator = Authenticator.NONE

  override val cookieJar: CookieJar = CookieJar.NO_COOKIES

  override val cache: Cache? = null

  override val proxy: Proxy? = null

  override val proxySelector: ProxySelector = ProxySelector.getDefault()

  override val proxyAuthenticator: Authenticator = Authenticator.NONE

  override val sslSocketFactoryOrNull: SSLSocketFactory? = null

  override val x509TrustManagerOrNull: X509TrustManager? = null

  override val hostnameVerifier: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()

  override val certificatePinner: CertificatePinner = CertificatePinner.DEFAULT

  override val connectionPool: ConnectionPool = ConnectionPool()

  override val eventListener: EventListener = EventListener.NONE

  override fun withDns(dns: Dns) = this

  override fun withSocketFactory(socketFactory: SocketFactory) = this

  override fun withRetryOnConnectionFailure(retryOnConnectionFailure: Boolean) = this

  override fun withAuthenticator(authenticator: Authenticator) = this

  override fun withCookieJar(cookieJar: CookieJar) = this

  override fun withCache(cache: Cache?) = this

  override fun withProxy(proxy: Proxy?) = this

  override fun withProxySelector(proxySelector: ProxySelector) = this

  override fun withProxyAuthenticator(proxyAuthenticator: Authenticator) = this

  override fun withSslSocketFactory(
    sslSocketFactory: SSLSocketFactory?,
    x509TrustManager: X509TrustManager?,
  ) = this

  override fun withHostnameVerifier(hostnameVerifier: HostnameVerifier) = this

  override fun withCertificatePinner(certificatePinner: CertificatePinner) = this

  override fun withConnectionPool(connectionPool: ConnectionPool) = this
}
