package ee.schimke.ha.addon.auth

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import org.slf4j.LoggerFactory

/**
 * Source address of the Home Assistant Supervisor's ingress gateway. Every ingress request is
 * proxied from this address, and add-on documentation tells add-ons to only accept ingress traffic
 * from it.
 */
const val SUPERVISOR_INGRESS_ADDRESS = "172.30.32.2"

class AddonAuthConfig {
  /**
   * Peer addresses trusted as the HA ingress proxy. The supervisor has already authenticated the
   * user before forwarding, so these requests need no token. Empty when not running under the
   * supervisor (no ingress exists there).
   */
  var trustedProxyAddresses: Set<String> = setOf(SUPERVISOR_INGRESS_ADDRESS)

  /** Validates `Authorization: Bearer <token>` on every other request. */
  var tokenValidator: TokenValidator = TokenValidator { TokenCheck.Invalid }

  /** Paths served without auth: supervisor/container health probes, which expose no HA data. */
  var publicPaths: Set<String> = setOf("/healthz", "/readyz")
}

/**
 * Gates every route (REST and the `/v1/stream` WebSocket upgrade) behind either the ingress proxy
 * address or a valid Home Assistant access token. The add-on drives HA with the supervisor token,
 * so without this anyone who could reach the optional direct port could call any HA service.
 *
 * The peer address is the TCP peer ([io.ktor.server.request.ApplicationRequest.local]), never a
 * forwarded header: `X-Forwarded-For` and friends are client-controlled on the direct port.
 */
val AddonAuth =
  createApplicationPlugin("AddonAuth", ::AddonAuthConfig) {
    val log = LoggerFactory.getLogger("ee.schimke.ha.addon.AddonAuth")
    val trusted = pluginConfig.trustedProxyAddresses.map(::normalizeAddress).toSet()
    val validator = pluginConfig.tokenValidator
    val publicPaths = pluginConfig.publicPaths

    onCall { call ->
      if (call.request.path() in publicPaths) return@onCall
      if (normalizeAddress(call.request.local.remoteAddress) in trusted) return@onCall

      val token = bearerToken(call.request.headers[HttpHeaders.Authorization])
      if (token == null) {
        call.unauthorized("missing bearer token")
        return@onCall
      }
      when (validator.validate(token)) {
        TokenCheck.Valid -> Unit
        TokenCheck.Invalid -> {
          log.info("rejected invalid token from {}", call.request.local.remoteAddress)
          call.unauthorized("invalid token")
        }
        TokenCheck.Unavailable ->
          call.respondText(
            """{"error":"cannot validate token with Home Assistant"}""",
            ContentType.Application.Json,
            HttpStatusCode.ServiceUnavailable,
          )
      }
    }
  }

private suspend fun ApplicationCall.unauthorized(reason: String) {
  response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"Home Assistant\"")
  respondText(
    """{"error":"$reason"}""",
    ContentType.Application.Json,
    HttpStatusCode.Unauthorized,
  )
}

internal fun bearerToken(header: String?): String? {
  if (header == null) return null
  val parts = header.trim().split(Regex("\\s+"), limit = 2)
  if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true)) return null
  return parts[1].takeIf { it.isNotBlank() }
}

/** `::ffff:172.30.32.2` (IPv4-mapped IPv6, as some stacks report it) → `172.30.32.2`. */
internal fun normalizeAddress(address: String): String =
  address.trim().removePrefix("/").removePrefix("::ffff:").removePrefix("::FFFF:")
