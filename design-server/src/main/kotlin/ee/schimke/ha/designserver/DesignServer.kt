package ee.schimke.ha.designserver

import ee.schimke.ha.design.DesignCatalog
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.queryString
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

  /**
   * The library's version on disk: the manifest's and the catalog's times, so a catalog written
   * after its manifest is still picked up. The generator replaces the whole directory at once.
   */
  private fun manifestModified(): Long =
    maxOf(
      File(dir, DesignLibrary.MANIFEST).lastModified(),
      File(dir, DesignCatalog.PATH).lastModified(),
    )
}

/**
 * Routes:
 * - `/designs/v1/manifest.json`, `/designs/v1/blobs/<sha256>.rc` ? the client protocol
 *   (`DesignRepository`): point the app's design server URL at `<host>/designs/v1`. The manifest
 *   revalidates by ETag; blobs are content-addressed and immutable.
 * - `/designs/v1/designs?cardType=&style=&variant=&author=` (and `surface`, `shape`, `theme`) ? the
 *   designs matching those tags ([DesignQuery]) and every tag's values, so a client can fetch what
 *   it needs instead of the whole manifest.
 * - `/designs/v1/catalog.json`, `/designs/v1/previews/...` ? the browser's data.
 * - `/` ? the app's front page ([Landing]); `/site/<name>` ? its pictures.
 * - `/designs`, `/designs/<id>` ? the template browser; `/api/designs[/<id>]` ? the same as JSON.
 * - `/embed/v1/ha-card.js`, `/embed/v1/rc-player.js` ? the card embed and its player;
 *   `/embed/v1/demo.html` ? its demo page (docs/architecture/card-embedding.md). The embed reads
 *   the manifest and blobs from other origins, so those, like the scripts, allow any origin: they
 *   hold no user data.
 * - `/healthz`.
 */
fun Application.designServer(
  holder: LibraryHolder,
  uiBuilder: UiBuilderIntegration? = null,
  github: GithubAuth? = null,
  site: SiteConfig = SiteConfig(),
  /** Designs people published; with GitHub sign-in it also serves `/my` and `/api/my/...`. */
  userDesigns: UserDesignStore? = null,
  /** Anonymous template usage: counts fetches and takes reports ([UsageStats]). */
  usage: UsageStats? = null,
  /** GitHub logins that may read `/stats/templates`; empty serves it to nobody. */
  statsUsers: Set<String> = emptySet(),
  /** GitHub logins that may edit the library's card designs ([LibrarySources]). */
  libraryEditors: Set<String> = emptySet(),
) {
  install(DefaultHeaders)
  install(AutoHeadResponse)
  // Refuses a body over the cap before it is buffered: by its Content-Length up front, or while it
  // streams in. Routes still apply their own, smaller caps.
  install(RequestBodyLimit) { bodyLimit { MAX_REQUEST_BODY_BYTES } }
  install(StatusPages) {
    exception<PayloadTooLargeException> { call, _ ->
      call.respondText("request body too large", status = HttpStatusCode.PayloadTooLarge)
    }
    exception<Throwable> { call, cause ->
      call.application.environment.log.error("request failed", cause)
      call.respondText("internal error", status = HttpStatusCode.InternalServerError)
    }
  }
  if (usage != null) {
    val flusher = launch {
      while (true) {
        delay(USAGE_FLUSH_MS)
        runCatching { usage.flush() }
      }
    }
    monitor.subscribe(ApplicationStopping) {
      flusher.cancel()
      runCatching { usage.flush() }
    }
  }
  val librarySources = LibrarySources(holder, uiBuilder, libraryEditors)
  routing {
    if (usage != null) usageRoutes(usage, github, statsUsers)
    librarySourceRoutes(librarySources, uiBuilder, github)
    dashboardStudioRoutes(uiBuilder, github)
    demoSite(File(System.getenv("WASM_SITE_DIR") ?: "/app/wasm"))
    cardStudioRoutes(holder, uiBuilder, github)
    if (userDesigns != null && github != null) {
      userDesignRoutes(holder, userDesigns, uiBuilder, github)
    }
    get("/healthz") { call.respondText("ok") }

    get("$API_BASE/manifest.json") {
      // A device token adds its owner's private designs; everyone gets the shared ones.
      val login = github?.user(call)
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

    get("$API_BASE/blobs/{name}") {
      val sha = BLOB_NAME.matchEntire(call.parameters["name"].orEmpty())?.groupValues?.get(1)
      // Blobs are content-addressed: a published design's hash is as good as a key to it.
      val bytes =
        sha?.let { holder.current().blob(it) ?: userDesigns?.blob(it) }
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

    get("/embed/v1/{name}") {
      val name = call.parameters["name"].orEmpty()
      val type = EMBED_FILES[name] ?: return@get call.notFound()
      val bytes = embedResource("embed/v1/$name") ?: return@get call.notFound()
      call.allowAnyOrigin()
      // Not content-addressed: a page keeps loading the same URL across releases.
      call.response.header(HttpHeaders.CacheControl, "public, max-age=300")
      call.respondBytes(bytes, type)
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
      // The browser lived here, and its filter links still arrive with its query keys; any other
      // query (`?utm_source=?`) is the front page's.
      if (call.request.queryParameters.names().any { it in BrowserFilter.KEYS }) {
        return@get call.respondRedirect("/designs?${call.request.queryString()}", permanent = true)
      }
      call.respondText(Landing.page(holder.current(), site), ContentType.Text.Html)
    }

    get("/site/{name}") {
      val name = call.parameters["name"].orEmpty()
      if (name !in Landing.SCREENSHOTS.map { it.file }) return@get call.notFound()
      val bytes = embedResource("site/$name") ?: return@get call.notFound()
      call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
      call.respondBytes(bytes, ContentType.Image.PNG)
    }

    get("/designs") {
      val library = holder.current()
      val filter = BrowserFilter.from(call.request.queryParameters, library, defaults = true)
      call.respondText(Pages.browser(library, filter), ContentType.Text.Html)
    }

    get("/designs/{id}") {
      val library = holder.current()
      val design = library.design(call.parameters["id"].orEmpty()) ?: return@get call.notFound()
      call.respondText(
        call.editor(uiBuilder, github).let { editor ->
          Pages.detail(library, design, editor, librarySources.mayEdit(editor))
        },
        ContentType.Text.Html,
      )
    }

    get("/api/designs/{id}/schema") {
      val library = holder.current()
      val design = library.design(call.parameters["id"].orEmpty()) ?: return@get call.notFound()
      call.respondText(
        json.encodeToString(CardSchema.serializer(), CardSchema.fromDesign(library, design)),
        ContentType.Application.Json,
      )
    }

    // A library design's editable source, to fork in UI Builder: its card type's real design when
    // it has one (LibrarySources), else HaCardsSeed's reconstruction.
    get("/api/designs/{id}/source.uid") {
      val library = holder.current()
      val design = library.design(call.parameters["id"].orEmpty()) ?: return@get call.notFound()
      call.response.header(
        HttpHeaders.ContentDisposition,
        ContentDisposition.Attachment.withParameter(
            ContentDisposition.Parameters.FileName,
            "${design.id}.uid",
          )
          .toString(),
      )
      call.respondText(
        json.encodeToString(
          JsonObject.serializer(),
          library.sources[design.key.cardType]?.let { LibrarySources.forkOf(it, design) }
            ?: HaCardsSeed.source(library, design),
        ) + "\n",
        ContentType.Application.Json,
      )
    }

    // The library design a card renders as, and the values to push into it (CardResolve). Called
    // cross-origin by players that have no card converters: UI Builder's canvas, the embed.
    options("/api/cards/resolve") {
      call.allowAnyOrigin()
      call.response.header(HttpHeaders.AccessControlAllowMethods, "POST, OPTIONS")
      call.response.header(HttpHeaders.AccessControlAllowHeaders, "Content-Type")
      call.respondText("", status = HttpStatusCode.NoContent)
    }
    post("/api/cards/resolve") {
      call.allowAnyOrigin()
      val request = runCatching {
        ResolveRequest.parse(Json.parseToJsonElement(call.receiveText()))
      }
        .getOrElse {
          return@post call.respondText(
            "not a card: ${it.message}",
            status = HttpStatusCode.BadRequest,
          )
        }
      val match =
        CardResolve.resolve(holder.current(), request)
          ?: return@post call.respondText(
            "no library design draws this card in ${request.cardStyle}",
            status = HttpStatusCode.NotFound,
          )
      call.respondText(
        json.encodeToString(CardMatch.serializer(), match),
        ContentType.Application.Json,
      )
    }

    // Any card's schema, from its Lovelace config (and optionally its entities' states).
    post("/api/schema") {
      val schema = runCatching {
        val (card, states) = CardSchema.parseRequest(Json.parseToJsonElement(call.receiveText()))
        CardSchema.fromCard(card, states, holder.current())
      }
        .getOrElse {
          return@post call.respondText(
            "not a card: ${it.message}",
            status = HttpStatusCode.BadRequest,
          )
        }
      call.respondText(
        json.encodeToString(CardSchema.serializer(), schema),
        ContentType.Application.Json,
      )
    }

    get("/schema") {
      call.respondText(
        Pages.schema(null, null, null, call.editor(uiBuilder, github)),
        ContentType.Text.Html,
      )
    }

    post("/schema") {
      val text = call.receiveParameters()["card"].orEmpty()
      val result = runCatching {
        val (card, states) = CardSchema.parseRequest(Json.parseToJsonElement(text))
        CardSchema.fromCard(card, states, holder.current())
      }
      call.respondText(
        Pages.schema(
          text,
          result.getOrNull(),
          result.exceptionOrNull()?.let { it.message ?: "not a card" },
          call.editor(uiBuilder, github),
        ),
        ContentType.Text.Html,
        status = if (result.isSuccess) HttpStatusCode.OK else HttpStatusCode.BadRequest,
      )
    }

    github?.routes(this)

    // Unlocks editing for this browser: the link the server prints at startup. Only without
    // GitHub sign-in: with it, editing is per person, and a shared link would be nobody.
    get("/edit") {
      val integration = uiBuilder ?: return@get call.notFound()
      if (github != null) return@get call.notFound()
      if (!tokensMatch(call.request.queryParameters["token"], integration.config.token)) {
        return@get call.respondText("wrong edit token", status = HttpStatusCode.Forbidden)
      }
      call.response.cookies.append(
        Cookie(
          EDIT_COOKIE,
          integration.config.token,
          httpOnly = true,
          path = "/",
          extensions = mapOf("SameSite" to "Lax"),
        )
      )
      call.respondRedirect("/designs")
    }

    // Seeds a UI Builder design from a library design (`design`, a fork of its source) or a card's
    // JSON (`card`), then opens it in the editor.
    post("/ui-builder/designs") {
      val integration = uiBuilder ?: return@post call.notFound()
      val editor = call.editor(uiBuilder, github)
      if (!editor.authorized) {
        return@post call.respondText(
          if (github != null) "sign in with GitHub to design cards"
          else "editing needs the edit link the server printed at startup",
          status = HttpStatusCode.Forbidden,
        )
      }
      val params = call.receiveParameters()
      val library = holder.current()
      // A library design forks from its source (HaCardsSeed.source), in the served `ha-cards`
      // catalog; a pasted card gets the bound starting layout.
      val (document, resolveCatalog) =
        params["design"]?.let { id ->
          val design = library.design(id) ?: return@post call.notFound()
          // A migrated card type forks its real design, the one the library is drawn from;
          // the others still fork HaCardsSeed's reconstruction of the converter's card.
          val source =
            library.sources[design.key.cardType]?.let { LibrarySources.forkOf(it, design) }
              ?: HaCardsSeed.source(library, design)
          JsonObject(source + ("id" to JsonPrimitive(HaCardsSeed.forkId(design)))) to true
        }
          ?: params["card"]?.let { text ->
            val (card, states) =
              runCatching { CardSchema.parseRequest(Json.parseToJsonElement(text)) }
                .getOrElse {
                  return@post call.respondText(
                    "not a card: ${it.message}",
                    status = HttpStatusCode.BadRequest,
                  )
                }
            val schema = CardSchema.fromCard(card, states, holder.current())
            UiBuilderSeed.document(schema, UiBuilderSeed.designId(schema), schema.cardType) to false
          }
          ?: return@post call.respondText(
            "needs design or card",
            status = HttpStatusCode.BadRequest,
          )
      val location = runCatching {
        val designId = integration.client.create(document, resolveCatalog)
        val login = editor.login
        if (login == null) integration.config.editorUrl(designId)
        else {
          // Created by the server, then handed to the person who asked: they edit it signed in to
          // UI Builder as themselves, and nobody else sees it.
          integration.client.share(designId, "github:$login", editor = true)
          integration.config.designUrl(designId)
        }
      }
        .getOrElse {
          return@post call.respondText(
            "UI Builder: ${it.message}",
            status = HttpStatusCode.BadGateway,
          )
        }
      call.respondRedirect(location)
    }
  }
}

/** UI Builder for the server: where it is, and the client that creates designs there. */
class UiBuilderIntegration(val config: UiBuilderConfig, val client: UiBuilderClient)

/**
 * Whether pages offer editing, and whether this browser may use it.
 * - [login]: the GitHub login it is signed in as, with GitHub sign-in.
 * - [signInPath]: where to sign in, with GitHub sign-in and nobody signed in.
 */
data class EditorState(
  val enabled: Boolean,
  val authorized: Boolean,
  val login: String? = null,
  val signInPath: String? = null,
) {
  companion object {
    val Off = EditorState(enabled = false, authorized = false)
  }
}

private const val EDIT_COOKIE = "design-server-edit"

internal fun ApplicationCall.editor(
  uiBuilder: UiBuilderIntegration?,
  github: GithubAuth?,
): EditorState =
  when {
    uiBuilder == null -> EditorState.Off
    github != null -> {
      val login = github.login(this)
      EditorState(
        enabled = true,
        authorized = login != null,
        login = login,
        signInPath = if (login == null) github.signInPath(request.local.uri) else null,
      )
    }
    else ->
      EditorState(
        enabled = true,
        authorized = tokensMatch(request.cookies[EDIT_COOKIE], uiBuilder.config.token),
      )
  }

/**
 * Whether [presented] is [expected], in time that doesn't depend on where they differ: a secret
 * compared with `==` leaks its prefix to someone timing the answers.
 */
internal fun tokensMatch(presented: String?, expected: String): Boolean =
  presented != null &&
    MessageDigest.isEqual(presented.encodeToByteArray(), expected.encodeToByteArray())

/**
 * The largest request body any route reads. The biggest legitimate ones are a card's JSON with its
 * entities' states (`/api/schema`, `/schema`, `/api/cards/resolve`): a large Home Assistant's whole
 * state snapshot is a few hundred KB to ~1 MB. Studio drafts are capped lower still (128-256 KB),
 * and publishing sends only ids. 4 MiB leaves headroom for those without letting a client make the
 * server buffer an arbitrary body.
 */
internal const val MAX_REQUEST_BODY_BYTES: Long = 4L * 1024 * 1024

/** How often counted usage is written to disk. */
private const val USAGE_FLUSH_MS: Long = 60_000

/** The client protocol's base path: the app's design server URL is `<host>$API_BASE`. */
const val API_BASE = "/designs/v1"

/** The embed's files, served from the classpath (`:design-server:embedResources`). */
private val EMBED_FILES =
  mapOf(
    "ha-card.js" to ContentType.Text.JavaScript,
    "rc-player.js" to ContentType.Text.JavaScript,
    "demo.html" to ContentType.Text.Html,
  )

private fun embedResource(path: String): ByteArray? =
  LibraryHolder::class.java.classLoader.getResourceAsStream(path)?.use { it.readBytes() }

internal fun ApplicationCall.allowAnyOrigin() {
  response.header(HttpHeaders.AccessControlAllowOrigin, "*")
}

internal val BLOB_NAME = Regex("([0-9a-f]{64})\\.rc")
private val PREVIEW_NAME = Regex("[0-9a-f]{16}-(example|template)\\.png")

private val json = Json { prettyPrint = true }

internal suspend fun ApplicationCall.notFound() =
  respondText("not found", status = HttpStatusCode.NotFound)
