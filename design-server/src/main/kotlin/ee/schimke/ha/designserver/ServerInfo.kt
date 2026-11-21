package ee.schimke.ha.designserver

import ee.schimke.ha.client.design.DesignChannelsCapability
import ee.schimke.ha.client.design.DesignLibraryCapabilities
import ee.schimke.ha.client.design.DesignLibraryInfo
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET <base>/info`: which parts of the design protocol this server serves, in the shape of the
 * integration's `terrazzo/info` ([DesignLibraryInfo]). A client asks it instead of trying each
 * endpoint, which can't tell an older server, or a static host, from one that is failing.
 *
 * The answer describes the server, not the caller, so it is public like the manifest: a device
 * token changes which designs the manifest lists, never what is offered. Raise a revision here when
 * an endpoint gains something a client may look for.
 */
internal object ServerInfo {
  fun of(
    version: String?,
    privateDesigns: Boolean,
    publish: Boolean,
    watchFaces: Boolean,
  ): DesignLibraryInfo =
    DesignLibraryInfo(
      protocol = DesignLibraryInfo.PROTOCOL,
      minClient = DesignLibraryInfo.MIN_CLIENT,
      version = version,
      capabilities =
        DesignLibraryCapabilities(
          manifest = 1,
          generate = 1,
          fonts = 1,
          glyphRanges = 1,
          bitmapFonts = 1,
          channels = DesignChannelsCapability(publish = publish),
          privateDesigns = if (privateDesigns) 1 else null,
          watchFaces = if (watchFaces) 1 else null,
        ),
    )
}

/**
 * Serves [info] at `$base/info`, for each library [holderOf] finds (a channel that isn't, 404s).
 */
internal fun Route.serverInfoRoutes(
  base: String,
  info: DesignLibraryInfo,
  holderOf: (ApplicationCall) -> LibraryHolder?,
) {
  val body = DesignLibraryInfo.json.encodeToString(DesignLibraryInfo.serializer(), info)
  get("$base/info") {
    holderOf(call) ?: return@get call.notFound()
    call.allowAnyOrigin()
    call.response.header(HttpHeaders.CacheControl, "no-cache")
    call.respondText(body, ContentType.Application.Json)
  }
}
