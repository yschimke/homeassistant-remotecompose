package ee.schimke.ha.addon.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

enum class TokenCheck {
  /** Home Assistant accepted the token. */
  Valid,
  /** Home Assistant rejected the token (401/403). */
  Invalid,
  /** Home Assistant couldn't be asked; the request fails without caching anything. */
  Unavailable,
}

/** Checks a caller-presented Home Assistant access token. */
fun interface TokenValidator {
  suspend fun validate(token: String): TokenCheck
}

/**
 * Validates a token by calling Home Assistant Core's `GET /api/` *with that token*: 200 means HA
 * accepts it. This must go to Core directly, not through the supervisor's `/core/api` proxy — that
 * proxy only accepts add-on supervisor tokens and substitutes its own credentials.
 *
 * [coreUrl] resolves Core's base URL (e.g. `http://homeassistant:8123`), or null when it can't be
 * determined, which reads as [TokenCheck.Unavailable].
 */
class HaTokenValidator(
  private val http: HttpClient,
  private val coreUrl: suspend () -> String?,
) : TokenValidator {
  private val log = LoggerFactory.getLogger(HaTokenValidator::class.java)

  override suspend fun validate(token: String): TokenCheck {
    val base = coreUrl() ?: return TokenCheck.Unavailable
    return try {
      val response = http.get("${base.trimEnd('/')}/api/") { bearerAuth(token) }
      when {
        response.status.isSuccess() -> TokenCheck.Valid
        response.status == HttpStatusCode.Unauthorized ||
          response.status == HttpStatusCode.Forbidden -> TokenCheck.Invalid
        else -> {
          log.warn("token check: HA answered {}", response.status)
          TokenCheck.Unavailable
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.warn("token check: HA unreachable at {}: {}", base, e.message)
      TokenCheck.Unavailable
    }
  }
}

/**
 * Caches [TokenCheck.Valid] results for [ttl] so a busy client doesn't cost an HA round trip per
 * request. Only positive results are cached: a rejected or unverifiable token is re-checked every
 * time, and a revoked token stops working within [ttl]. Tokens are keyed by SHA-256, so the cache
 * never holds a usable credential.
 */
class CachingTokenValidator(
  private val delegate: TokenValidator,
  private val ttl: Duration = 60.seconds,
  private val maxEntries: Int = 256,
  private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) : TokenValidator {
  private val validUntil = ConcurrentHashMap<String, ComparableTimeMark>()

  override suspend fun validate(token: String): TokenCheck {
    val key = sha256(token)
    val expiry = validUntil[key]
    if (expiry != null) {
      if (expiry.hasNotPassedNow()) return TokenCheck.Valid
      validUntil.remove(key, expiry)
    }
    val result = delegate.validate(token)
    if (result == TokenCheck.Valid) {
      if (validUntil.size >= maxEntries) evict()
      validUntil[key] = timeSource.markNow() + ttl
    }
    return result
  }

  private fun evict() {
    validUntil.entries.removeIf { it.value.hasPassedNow() }
    if (validUntil.size >= maxEntries) validUntil.clear()
  }

  private fun sha256(token: String): String =
    MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") {
      "%02x".format(it)
    }
}

/**
 * Resolves Home Assistant Core's base URL on the supervisor's internal network from `GET
 * http://supervisor/core/info` (needs `hassio_api`). The result is cached once resolved.
 *
 * Core serving TLS itself (`http: ssl_certificate:`) can't be validated against: its certificate
 * names the public host, not `homeassistant`. That case resolves to null, so direct-port requests
 * fail closed with 503 while ingress keeps working.
 */
class SupervisorCoreUrl(
  private val http: HttpClient,
  private val supervisorToken: String,
  private val supervisorUrl: String = "http://supervisor",
) : suspend () -> String? {
  private val log = LoggerFactory.getLogger(SupervisorCoreUrl::class.java)
  private val json = Json { ignoreUnknownKeys = true }
  @Volatile private var resolved: String? = null

  override suspend fun invoke(): String? {
    resolved?.let {
      return it
    }
    return try {
      val response = http.get("$supervisorUrl/core/info") { bearerAuth(supervisorToken) }
      if (!response.status.isSuccess()) {
        log.warn("supervisor /core/info answered {}", response.status)
        return null
      }
      val data = json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]?.jsonObject
      val port = data?.get("port")?.jsonPrimitive?.intOrNull ?: 8123
      val ssl = data?.get("ssl")?.jsonPrimitive?.booleanOrNull ?: false
      if (ssl) {
        log.warn(
          "Home Assistant serves TLS directly; direct-port token auth is unavailable " +
            "(use ingress, or terminate TLS in a proxy add-on)"
        )
        return null
      }
      "http://homeassistant:$port".also { resolved = it }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.warn("supervisor /core/info failed: {}", e.message)
      null
    }
  }
}
