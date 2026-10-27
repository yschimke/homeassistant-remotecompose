package ee.schimke.ha.designserver

import ee.schimke.ha.design.DesignCatalog
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.GeneratedDesign
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * One [DesignGenerator] per library holder: a key captured on request is kept beside the library of
 * the channel it was asked of, so a pull request's preview never answers with `main`'s capture.
 */
class DesignGenerators {
  private val byHolder =
    Collections.synchronizedMap(IdentityHashMap<LibraryHolder, DesignGenerator>())

  fun of(holder: LibraryHolder): DesignGenerator =
    byHolder.getOrPut(holder) { DesignGenerator(holder) }

  /** Forgets the generator of a channel that was deleted. */
  fun forget(holder: LibraryHolder) {
    byHolder.remove(holder)
  }
}

/**
 * The client protocol (`DesignRepository`) for the library [holderOf] picks at [base]: the
 * manifest, blobs, designs by key, and the browser's catalog and previews. Mounted at `/designs/v1`
 * for `main` and at `/designs/v1/channels/{channel}` for each channel ([LibraryChannels]), so an
 * app pointed at a pull request's channel plays that pull request's designs. Only `main` adds a
 * signed-in person's own designs ([userDesigns]) and counts usage.
 */
internal fun Route.libraryProtocolRoutes(
  base: String,
  generators: DesignGenerators,
  userDesigns: UserDesignStore?,
  github: GithubAuth?,
  usage: UsageStats?,
  holderOf: (ApplicationCall) -> LibraryHolder?,
) {
  get("$base/manifest.json") {
    // A device token adds its owner's private designs; everyone gets the shared ones.
    val login = github?.user(call)
    val holder = holderOf(call) ?: return@get call.notFound()
    val served = holder.current().manifestFor(userDesigns, login)
    usage?.manifestFetched(call.request.header(HttpHeaders.UserAgent))
    call.response.header(HttpHeaders.ETag, served.etag)
    call.allowAnyOrigin()
    call.response.header(HttpHeaders.Vary, HttpHeaders.Authorization)
    // Clients revalidate on every refresh; a 304 costs nothing.
    call.response.header(
      HttpHeaders.CacheControl,
      if (login != null) "private, no-cache" else "no-cache",
    )
    if (call.request.header(HttpHeaders.IfNoneMatch)?.let { served.etag in it } == true) {
      call.respondBytes(ByteArray(0), status = HttpStatusCode.NotModified)
    } else {
      call.respondBytes(served.bytes, ContentType.Application.Json)
    }
  }

  get("$base/blobs/{name}") {
    val holder = holderOf(call) ?: return@get call.notFound()
    val generator = generators.of(holder)
    val sha = BLOB_NAME.matchEntire(call.parameters["name"].orEmpty())?.groupValues?.get(1)
    // Blobs are content-addressed: a published design's hash is as good as a key to it.
    val bytes =
      sha?.let { holder.current().blob(it) ?: generator.blob(it) ?: userDesigns?.blob(it) }
        ?: return@get call.notFound()
    (holder.current().entry(sha!!) ?: userDesigns?.entry(sha))?.let {
      usage?.blobFetched(
        it,
        ClientAgent.of(
          call.request.header(HttpHeaders.UserAgent),
          call.request.queryParameters["client"],
        ),
      )
    }
    call.response.header(HttpHeaders.ETag, "\"$sha\"")
    call.allowAnyOrigin()
    call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
    call.respondBytes(bytes, ContentType.Application.OctetStream)
  }

  // The design for a key (DesignGenerator): the library's, or captured now when the library has
  // none. A client that computes its cards' keys itself asks by key, so no card's config or
  // states leave it: a key holds generation properties, never values.
  options("$base/generate") {
    call.allowAnyOrigin()
    call.response.header(HttpHeaders.AccessControlAllowMethods, "POST, OPTIONS")
    call.response.header(HttpHeaders.AccessControlAllowHeaders, "Content-Type")
    call.respondText("", status = HttpStatusCode.NoContent)
  }
  post("$base/generate") {
    call.allowAnyOrigin()
    val holder = holderOf(call) ?: return@post call.notFound()
    val generator = generators.of(holder)
    val key = runCatching {
      protocolKeyJson.decodeFromString(DesignKey.serializer(), call.receiveText())
    }
      .getOrElse {
        return@post call.respondText(
          "not a design key: ${it.message}",
          status = HttpStatusCode.BadRequest,
        )
      }
    val design =
      try {
        generator.design(key)
      } catch (e: DesignGenerator.UnsupportedKey) {
        return@post call.respondText(
          "no design for this key: ${e.message}",
          status = HttpStatusCode.NotFound,
        )
      }
    call.respondText(
      protocolJson.encodeToString(GeneratedDesign.serializer(), design),
      ContentType.Application.Json,
    )
  }

  get("$base/${DesignCatalog.PATH}") {
    val holder = holderOf(call) ?: return@get call.notFound()
    val catalog = holder.current().catalog ?: return@get call.notFound()
    call.response.header(HttpHeaders.CacheControl, "no-cache")
    call.respondText(
      protocolJson.encodeToString(DesignCatalog.serializer(), catalog),
      ContentType.Application.Json,
    )
  }

  get("$base/previews/{name}") {
    val name = call.parameters["name"].orEmpty()
    if (!PROTOCOL_PREVIEW_NAME.matches(name)) return@get call.notFound()
    val holder = holderOf(call) ?: return@get call.notFound()
    val bytes = holder.current().preview("previews/$name") ?: return@get call.notFound()
    // Named by design, not content: a publish or a live edit redraws them, so not for long.
    call.response.header(HttpHeaders.CacheControl, "public, max-age=60")
    call.respondBytes(bytes, ContentType.Image.PNG)
  }
}

/**
 * Publishing libraries to [channels] without redeploying the server:
 * - `PUT $LIBRARY_CHANNELS_PATH/<channel>`, a zip of a library directory as
 *   `:card-designs:generateCardDesigns` writes it, with `Authorization: Bearer <publishToken>`:
 *   installs it on the channel (`main`, or `pr-<n>` for a pull request's preview). Optional query
 *   parameters describe where it came from: `commit`, and for a pull request `title`, `url`,
 *   `branch` and `author`, which the browser shows.
 * - `DELETE $LIBRARY_CHANNELS_PATH/<channel>` with the same token: drops a pull request's channel.
 * - `GET $LIBRARY_CHANNELS_PATH`: every channel and what it serves; public, like the libraries.
 *
 * Without a [publishToken] (or a store to publish into) the write routes answer 404.
 */
internal fun Route.libraryChannelRoutes(
  channels: LibraryChannels,
  generators: DesignGenerators,
  publishToken: String?,
) {
  get(LIBRARY_CHANNELS_PATH) {
    call.allowAnyOrigin()
    call.response.header(HttpHeaders.CacheControl, "no-cache")
    call.respondText(
      channelJson.encodeToString(
        ListSerializer(LibraryChannels.ChannelInfo.serializer()),
        channels.list(),
      ),
      ContentType.Application.Json,
    )
  }

  put("$LIBRARY_CHANNELS_PATH/{channel}") {
    if (publishToken == null || !channels.publishable) return@put call.notFound()
    if (!call.bearerMatches(publishToken)) {
      return@put call.respondText("wrong publish token", status = HttpStatusCode.Unauthorized)
    }
    val channel = call.parameters["channel"].orEmpty()
    if (!LibraryPaths.CHANNEL.matches(channel)) {
      return@put call.respondText("not a channel: $channel", status = HttpStatusCode.BadRequest)
    }
    val params = call.request.queryParameters
    val pullRequest =
      channel
        .removePrefix("pr-")
        .toIntOrNull()
        ?.takeIf { channel.startsWith("pr-") }
        ?.let {
          LibraryChannels.PullRequest(
            number = it,
            title = params["title"],
            url = params["url"],
            branch = params["branch"],
            author = params["author"],
          )
        }
    val info =
      try {
        withContext(Dispatchers.IO) {
          val staging = channels.unpack(call.receiveChannel().toInputStream())
          channels.install(
            channel,
            staging,
            origin = "ci",
            commit = params["commit"],
            pullRequest = pullRequest,
          )
        }
      } catch (e: IllegalArgumentException) {
        return@put call.respondText(
          "not a library: ${e.message}",
          status = HttpStatusCode.BadRequest,
        )
      } catch (e: IllegalStateException) {
        return@put call.respondText(
          "not a library: ${e.message}",
          status = HttpStatusCode.BadRequest,
        )
      }
    call.respondText(
      channelJson.encodeToString(LibraryChannels.ChannelInfo.serializer(), info),
      ContentType.Application.Json,
    )
  }

  // Which of a library's files (by SHA-256) this server lacks: a publisher uploads only those.
  post("$LIBRARY_CHANNELS_PATH/missing") {
    if (publishToken == null || !channels.publishable) return@post call.notFound()
    if (!call.bearerMatches(publishToken)) {
      return@post call.respondText("wrong publish token", status = HttpStatusCode.Unauthorized)
    }
    val hashes = runCatching {
      channelJson.decodeFromString(ListSerializer(String.serializer()), call.receiveText())
    }
      .getOrElse {
        return@post call.respondText(
          "not a list of hashes: ${it.message}",
          status = HttpStatusCode.BadRequest,
        )
      }
    call.respondText(
      channelJson.encodeToString(
        ListSerializer(String.serializer()),
        channels.missing(hashes).toList(),
      ),
      ContentType.Application.Json,
    )
  }

  delete("$LIBRARY_CHANNELS_PATH/{channel}") {
    if (publishToken == null || !channels.publishable) return@delete call.notFound()
    if (!call.bearerMatches(publishToken)) {
      return@delete call.respondText("wrong publish token", status = HttpStatusCode.Unauthorized)
    }
    val channel = call.parameters["channel"].orEmpty()
    if (channel == LibraryPaths.MAIN) {
      return@delete call.respondText("main can't be deleted", status = HttpStatusCode.BadRequest)
    }
    val holder = channels.holder(channel)
    if (!channels.delete(channel)) return@delete call.notFound()
    holder?.let(generators::forget)
    call.respondText("", status = HttpStatusCode.NoContent)
  }
}

/** Whether the request carries `Authorization: Bearer <token>`. */
private fun ApplicationCall.bearerMatches(token: String): Boolean =
  tokensMatch(request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim(), token)

/** Where libraries are published and listed: `/api/library/channels`. */
const val LIBRARY_CHANNELS_PATH = "/api/library/channels"

private val channelJson = Json {
  prettyPrint = true
  encodeDefaults = false
}

private val protocolJson = Json { prettyPrint = true }

private val protocolKeyJson = Json { ignoreUnknownKeys = true }

private val PROTOCOL_PREVIEW_NAME = Regex("[0-9a-f]{16}-(example|template)\\.png")
