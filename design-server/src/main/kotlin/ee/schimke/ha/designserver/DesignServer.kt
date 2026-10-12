package ee.schimke.ha.designserver

import ee.schimke.ha.design.DesignCatalog
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.io.File
import kotlinx.serialization.json.Json

/**
 * The library a running server serves: reloaded when `manifest.json` changes on disk (checked at
 * most every [checkIntervalMs]), so a regenerated library goes live without a restart. A library
 * that fails to load keeps the previous one serving.
 */
class LibraryHolder(
  private val dir: File,
  private val checkIntervalMs: Long = 5_000,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  @Volatile private var library: DesignLibrary = DesignLibrary.load(dir)
  @Volatile private var loadedModified = manifestModified()
  @Volatile private var checkedAt = clock()

  fun current(): DesignLibrary {
    val now = clock()
    if (now - checkedAt >= checkIntervalMs) {
      checkedAt = now
      val modified = manifestModified()
      if (modified != loadedModified) {
        runCatching { DesignLibrary.load(dir) }
          .onSuccess {
            library = it
            loadedModified = modified
          }
          .onFailure {
            System.err.println("design library reload failed, keeping the old one: ${it.message}")
          }
      }
    }
    return library
  }

  private fun manifestModified(): Long = File(dir, DesignLibrary.MANIFEST).lastModified()
}

/**
 * Routes:
 * - `/designs/v1/manifest.json`, `/designs/v1/blobs/<sha256>.rc` — the client protocol
 *   (`DesignRepository`): point the app's design server URL at `<host>/designs/v1`. The manifest
 *   revalidates by ETag; blobs are content-addressed and immutable.
 * - `/designs/v1/catalog.json`, `/designs/v1/previews/...` — the browser's data.
 * - `/`, `/designs/<id>` — the template browser; `/api/designs[/<id>]` — the same as JSON.
 * - `/healthz`.
 */
fun Application.designServer(holder: LibraryHolder) {
  install(DefaultHeaders)
  install(AutoHeadResponse)
  install(StatusPages) {
    exception<Throwable> { call, cause ->
      call.application.environment.log.error("request failed", cause)
      call.respondText("internal error", status = HttpStatusCode.InternalServerError)
    }
  }
  routing {
    get("/healthz") { call.respondText("ok") }

    get("$API_BASE/manifest.json") {
      val library = holder.current()
      call.response.header(HttpHeaders.ETag, library.manifestEtag)
      // Clients revalidate on every refresh; a 304 costs nothing.
      call.response.header(HttpHeaders.CacheControl, "no-cache")
      if (
        call.request.header(HttpHeaders.IfNoneMatch)?.let { library.manifestEtag in it } == true
      ) {
        call.respondBytes(ByteArray(0), status = HttpStatusCode.NotModified)
      } else {
        call.respondBytes(library.manifestBytes, ContentType.Application.Json)
      }
    }

    get("$API_BASE/blobs/{name}") {
      val sha = BLOB_NAME.matchEntire(call.parameters["name"].orEmpty())?.groupValues?.get(1)
      val bytes = sha?.let { holder.current().blob(it) } ?: return@get call.notFound()
      call.response.header(HttpHeaders.ETag, "\"$sha\"")
      call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
      call.respondBytes(bytes, ContentType.Application.OctetStream)
    }

    get("$API_BASE/${DesignCatalog.PATH}") {
      val catalog = holder.current().catalog ?: return@get call.notFound()
      call.response.header(HttpHeaders.CacheControl, "no-cache")
      call.respondText(
        json.encodeToString(DesignCatalog.serializer(), catalog),
        ContentType.Application.Json,
      )
    }

    get("$API_BASE/previews/{name}") {
      val name = call.parameters["name"].orEmpty()
      if (!PREVIEW_NAME.matches(name)) return@get call.notFound()
      val bytes = holder.current().preview("previews/$name") ?: return@get call.notFound()
      // Named by design, not content: a regenerated library may redraw them.
      call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
      call.respondBytes(bytes, ContentType.Image.PNG)
    }

    get("/api/designs") {
      val library = holder.current()
      val filter = BrowserFilter.from(call.request.queryParameters, library, defaults = false)
      call.respondText(Api.list(library, filter.apply(library)), ContentType.Application.Json)
    }

    get("/api/designs/{id}") {
      val library = holder.current()
      val design = library.design(call.parameters["id"].orEmpty()) ?: return@get call.notFound()
      call.respondText(Api.detail(library, design), ContentType.Application.Json)
    }

    get("/") {
      val library = holder.current()
      val filter = BrowserFilter.from(call.request.queryParameters, library, defaults = true)
      call.respondText(Pages.browser(library, filter), ContentType.Text.Html)
    }

    get("/designs/{id}") {
      val library = holder.current()
      val design = library.design(call.parameters["id"].orEmpty()) ?: return@get call.notFound()
      call.respondText(Pages.detail(library, design), ContentType.Text.Html)
    }
  }
}

/** The client protocol's base path: the app's design server URL is `<host>$API_BASE`. */
const val API_BASE = "/designs/v1"

private val BLOB_NAME = Regex("([0-9a-f]{64})\\.rc")
private val PREVIEW_NAME = Regex("[0-9a-f]{16}-(example|template)\\.png")

private val json = Json { prettyPrint = true }

private suspend fun ApplicationCall.notFound() =
  respondText("not found", status = HttpStatusCode.NotFound)
