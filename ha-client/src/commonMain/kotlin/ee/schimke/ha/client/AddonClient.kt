package ee.schimke.ha.client

import ee.schimke.ha.model.CardBytes
import ee.schimke.ha.model.CardKey
import ee.schimke.ha.model.CardSize
import ee.schimke.ha.model.ClientProfile
import ee.schimke.ha.model.Dashboard
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * REST client for the RemoteCompose HA add-on (`/v1/...`).
 *
 * Scope is intentionally narrow: structure + state come from HA Core directly via [HaClient], so
 * this client only needs to (a) probe the add-on's health and (b) fetch pre-rendered card bytes.
 * Listing / fetching dashboards is exposed for parity but the client should normally read those
 * from HA Core too — the add-on's copies are a cache, not the truth.
 *
 * Failure modes are explicit:
 *
 * - [health] returns false on any non-200 / network failure / timeout so callers can branch without
 *   a try/catch.
 * - [fetchCardBytes] returns null on 4xx/5xx (including the typed 501 the server returns until M3
 *   lands) so a [CardSource] chain can fall through to the next generator.
 *
 * Construction takes an [HttpClientEngine] so tests can inject a `MockEngine` and production code
 * can stay engine-agnostic.
 */
class AddonClient(
  val baseUrl: String,
  private val accessToken: String? = null,
  engine: HttpClientEngine? = null,
) {
  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  private val http: HttpClient =
    if (engine != null) {
      HttpClient(engine) { installPlugins() }
    } else {
      HttpClient { installPlugins() }
    }

  /**
   * Probes `/healthz` with a short timeout. Used by the runtime to decide whether to install
   * [AddonCardGenerator] in the chain at session-open time.
   */
  suspend fun health(timeoutMs: Long = PROBE_TIMEOUT_MS): Boolean =
    runCatching {
        val resp =
          http.get(url("/healthz")) {
            authHeader()
            timeout { requestTimeoutMillis = timeoutMs }
          }
        resp.status.isSuccess()
      }
      .getOrElse { false }

  /** Wraps the add-on's `/v1/dashboards` listing. */
  suspend fun listDashboards(): JsonArray {
    val resp = http.get(url("/v1/dashboards")) { authHeader() }
    if (!resp.status.isSuccess()) error("listDashboards: ${resp.status}")
    return json.parseToJsonElement(resp.bodyAsText()) as JsonArray
  }

  /** Wraps `/v1/dashboards/{path}`. `null` path = the default dashboard. */
  suspend fun fetchDashboard(urlPath: String?): Dashboard {
    val pathSegment = urlPath ?: "_default"
    val resp = http.get(url("/v1/dashboards/$pathSegment")) { authHeader() }
    if (!resp.status.isSuccess()) error("fetchDashboard($pathSegment): ${resp.status}")
    return json.decodeFromString(Dashboard.serializer(), resp.bodyAsText())
  }

  /**
   * Fetch pre-rendered card bytes for [card] at [size] / [profile]. Returns null on any HTTP error
   * or network failure — callers should treat that as "this generator can't help, try the next
   * one".
   *
   * URL layout: `/v1/cards/{cacheKey}.rc?w&h&density&profile`. The `cacheKey` is
   * [CardKey.toCacheKey] which already encodes dashboard / view / index / type — stable across
   * reorders only when no card moved.
   */
  suspend fun fetchCardBytes(card: CardKey, size: CardSize, profile: ClientProfile): CardBytes? =
    runCatching {
        val resp: HttpResponse =
          http.get(url("/v1/cards/${card.toCacheKey()}.rc")) {
            authHeader()
            parameter("w", size.widthPx)
            parameter("h", size.heightPx)
            parameter("density", size.densityDpi)
            parameter("profile", profile.wire)
          }
        when {
          resp.status.isSuccess() ->
            CardBytes(bytes = resp.bodyAsBytes(), widthPx = size.widthPx, heightPx = size.heightPx)
          // 501 (M3 not implemented yet), 404 (server doesn't have a
          // converter for this card), 503 (HA bridge not ready) — all
          // collapse into "no, fall through".
          resp.status == HttpStatusCode.NotImplemented -> null
          resp.status == HttpStatusCode.NotFound -> null
          resp.status == HttpStatusCode.ServiceUnavailable -> null
          else -> null
        }
      }
      .getOrElse { null }

  fun close() {
    http.close()
  }

  private fun url(path: String): String = baseUrl.trimEnd('/') + path

  private fun HttpRequestBuilder.authHeader() {
    if (!accessToken.isNullOrEmpty()) {
      header(HttpHeaders.Authorization, "Bearer $accessToken")
    }
  }

  companion object {
    const val PROBE_TIMEOUT_MS: Long = 1_500L
    const val DEFAULT_REQUEST_TIMEOUT_MS: Long = 10_000L
    const val DEFAULT_CONNECT_TIMEOUT_MS: Long = 5_000L
  }
}

private fun io.ktor.client.HttpClientConfig<*>.installPlugins() {
  install(HttpTimeout) {
    requestTimeoutMillis = AddonClient.DEFAULT_REQUEST_TIMEOUT_MS
    connectTimeoutMillis = AddonClient.DEFAULT_CONNECT_TIMEOUT_MS
  }
}
