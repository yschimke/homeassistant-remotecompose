package ee.schimke.terrazzo.core.session

import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Home Assistant refused the stored refresh token (revoked, user deactivated, instance reset), so
 * no access token can be minted. Retrying can't fix it: the user has to sign in again.
 */
class HaReauthRequiredException(message: String, cause: Throwable? = null) :
  IllegalStateException(message, cause)

/** A freshly minted access token and how long Home Assistant says it lives, when it says. */
data class MintedAccessToken(val accessToken: String, val lifetime: Duration? = null)

/**
 * The access token a [LiveHaSession] hands to each WebSocket connect.
 *
 * Home Assistant's OAuth access tokens live about 30 minutes; the refresh token persisted at
 * sign-in lives until revoked. [get] returns the current token while it's fresh, and mints a new
 * one through [mint] (a `grant_type=refresh_token` exchange) once it's within [REFRESH_MARGIN] of
 * expiry or after [invalidate] reported HA answering `auth_invalid` for it. Without [mint] (tests,
 * a session built from a bare token) [get] always returns the initial token, as before.
 *
 * When [mint] throws [HaReauthRequiredException] the failure sticks: [needsReauth] flips to true
 * and every later [get] rethrows without another network round trip. Any other failure (no network,
 * a 5xx) is transient and the next [get] tries again.
 */
class HaAccessTokens(
  initialToken: String?,
  initialLifetime: Duration? = null,
  private val mint: (suspend () -> MintedAccessToken)? = null,
  private val timeSource: TimeSource = TimeSource.Monotonic,
) {
  private val mutex = Mutex()
  @Volatile private var token: String? = initialToken
  @Volatile private var expiry: TimeMark? = initialToken?.let { expiryAfter(initialLifetime) }
  @Volatile private var rejected: String? = null
  @Volatile private var reauthFailure: HaReauthRequiredException? = null

  private val _needsReauth = MutableStateFlow(false)

  /** True once the refresh token was refused; only signing in again recovers. */
  val needsReauth: StateFlow<Boolean> = _needsReauth.asStateFlow()

  /** The last token handed out (or the initial one), for bearer-authenticated HTTP fetches. */
  val current: String?
    get() = token

  /** Whether a rejected or expired token can be replaced; false for a session pinned to one. */
  val canRefresh: Boolean
    get() = mint != null

  suspend fun get(): String = mutex.withLock {
    reauthFailure?.let { throw it }
    val existing = token
    val refresh = mint ?: return existing ?: throw HaReauthRequiredException("No access token")
    if (existing != null && existing != rejected && expiry?.hasPassedNow() != true) {
      return existing
    }
    val minted =
      try {
        refresh()
      } catch (e: HaReauthRequiredException) {
        reauthFailure = e
        _needsReauth.value = true
        throw e
      }
    token = minted.accessToken
    expiry = expiryAfter(minted.lifetime)
    rejected = null
    minted.accessToken
  }

  /** Home Assistant answered `auth_invalid` for [stale]; the next [get] mints a replacement. */
  fun invalidate(stale: String) {
    rejected = stale
  }

  private fun expiryAfter(lifetime: Duration?): TimeMark =
    timeSource.markNow() + ((lifetime ?: DEFAULT_LIFETIME) - REFRESH_MARGIN).coerceAtLeast(ZERO)

  companion object {
    /** Home Assistant's access-token lifetime, assumed when the token response doesn't say. */
    val DEFAULT_LIFETIME: Duration = 30.minutes

    /** Refresh this long before expiry so a connect never races the token running out. */
    val REFRESH_MARGIN: Duration = 60.seconds

    private val ZERO = Duration.ZERO
  }
}

private val tokenErrorJson = Json { ignoreUnknownKeys = true }

/**
 * Whether a failed `/auth/token` refresh means the refresh token itself was refused — sign in again
 * — rather than a transient failure worth retrying. Home Assistant answers a revoked or unknown
 * refresh token with `400 {"error":"invalid_grant"}`, a client mismatch with `invalid_request`, and
 * a deactivated user with `403 access_denied`. A `400` without one of those OAuth errors (a proxy
 * page, say) stays transient.
 */
fun isRefreshTokenRejected(status: Int, body: String): Boolean =
  when (status) {
    401,
    403 -> true
    400 -> {
      val error = runCatching {
        tokenErrorJson.parseToJsonElement(body) as? JsonObject
      }
        .getOrNull()
        ?.get("error")
        .let { (it as? JsonPrimitive)?.content }
      error == "invalid_grant" || error == "invalid_request"
    }
    else -> false
  }

/**
 * Exponential backoff between automatic reconnect attempts: [initial], doubling each failure up to
 * [max]. [reset] once a connection succeeds. A brief network blip recovers within seconds; a host
 * that stays down (or keeps refusing us) is retried at most every [max] instead of hammered.
 */
class ReconnectBackoff(
  private val initial: Duration = 2.seconds,
  private val max: Duration = 5.minutes,
) {
  private var current: Duration? = null

  fun next(): Duration {
    val delay = current?.let { (it * 2).coerceAtMost(max) } ?: initial.coerceAtMost(max)
    current = delay
    return delay
  }

  fun reset() {
    current = null
  }
}
