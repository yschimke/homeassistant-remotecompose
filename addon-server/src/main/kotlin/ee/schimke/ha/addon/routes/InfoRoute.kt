package ee.schimke.ha.addon.routes

import ee.schimke.ha.client.AddonCapabilities
import ee.schimke.ha.client.AddonInfo
import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

/**
 * `GET /v1/info`: which endpoints this build serves, in the shape of the integration's
 * `terrazzo/info` ([AddonInfo]). A client asks it first instead of trying each endpoint and reading
 * a 404 or 501, which can't tell an older add-on from one that is failing.
 *
 * `/v1/cards/{key}.rc` is left out: it answers 501 until the JVM card encoder is wired (PLAN.md,
 * M3). Raise a revision here when an endpoint gains something a client may look for.
 */
fun Routing.infoRoute(version: String?) {
  val body =
    AddonInfo.json.encodeToString(
      AddonInfo.serializer(),
      AddonInfo(
        protocol = AddonInfo.PROTOCOL,
        minClient = AddonInfo.MIN_CLIENT,
        version = version,
        capabilities = AddonCapabilities(dashboards = 1, snapshot = 1, stream = 1),
      ),
    )
  get("/v1/info") { call.respondText(body, ContentType.Application.Json) }
}
