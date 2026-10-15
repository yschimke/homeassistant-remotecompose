package ee.schimke.ha.designserver

import ee.schimke.composeai.rcplayer.edit.RcTargetProfiles
import ee.schimke.composeai.rcplayer.edit.namedVariables
import ee.schimke.composeai.rcplayer.edit.validateFor
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignManifest
import ee.schimke.ha.design.DeviceMatch
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A manifest as one caller sees it: its bytes and their ETag. Clients revalidate by ETag, so a
 * signed-in device and an anonymous one each get the version they were sent.
 */
class ServedManifest(val bytes: ByteArray, val etag: String)

/**
 * The manifest [login] (null for anyone) is served: the library's, plus every design its author
 * shared, plus [login]'s own private ones. Its serial adds the store's revision to the library's,
 * so it rises whenever either changes and a client never takes it for a rollback. With nothing to
 * add, the library's manifest goes out byte for byte.
 */
fun DesignLibrary.manifestFor(store: UserDesignStore?, login: String?): ServedManifest {
  val extra =
    store
      ?.let { (it.shared() + login?.let(it::designs).orEmpty()).distinctBy { d -> d.entry } }
      .orEmpty()
  if (extra.isEmpty()) return ServedManifest(manifestBytes, manifestEtag)
  val merged: DesignManifest =
    manifest.copy(
      serial = manifest.serial + store!!.revision,
      designs = manifest.designs + extra.map { it.entry },
      // The library's signature covers the library alone.
      signature = null,
    )
  val bytes = manifestJson.encodeToString(DesignManifest.serializer(), merged).encodeToByteArray()
  return ServedManifest(bytes, "\"${Sha256.hex(bytes)}\"")
}

private val manifestJson = Json { prettyPrint = true }
private val apiJson = Json {
  prettyPrint = true
  ignoreUnknownKeys = true
}

/** What `POST /api/my/designs` takes: a UI Builder design to publish as a variant of [key]. */
@Serializable
data class PublishRequest(
  val designId: String,
  /** The card design it varies: a library design's key; its own variant is ignored. */
  val key: DesignKey,
  /** The variant name it publishes under: lowercase letters, digits and dashes. */
  val variant: String,
  /** A UI Builder revision to pin, or null for the latest. */
  val revision: Long? = null,
  /** The devices it is made for (a boiler's dial), or null for every card of [key]. */
  val device: DeviceMatch? = null,
)

/**
 * Why [bytes] can't stand in for [base], or null when they can: they must decode, play on the app's
 * Remote Compose profile, and bind only values the card provides (what [base] binds). Named values
 * without a dot are the design's own state, which a card never pushes, so they are allowed.
 */
fun userTemplateProblem(bytes: ByteArray, base: DecodedTemplate): String? {
  val document = runCatching {
    RcDocumentCodec.decode(bytes, RcWireLimits())
  }
    .getOrElse {
      return "it isn't a Remote Compose document (${it.message ?: it.javaClass.simpleName})"
    }
  document.validateFor(RcTargetProfiles.ANDROIDX_EXPERIMENTAL).firstOrNull()?.let {
    return "it uses something the app can't play: ${it.message}"
  }
  val provided = base.bindings.map { it.name }.toSet()
  val unknown =
    document.namedVariables
      .map { it.name.removePrefix(DesignLibrary.USER_PREFIX) }
      .filter { '.' in it && it !in provided }
      .distinct()
      .sorted()
  if (unknown.isNotEmpty()) {
    return "it reads values this card doesn't provide: ${unknown.joinToString()}"
  }
  return null
}

private val VARIANT = Regex("[a-z0-9][a-z0-9-]{0,39}")

/**
 * A person's own designs, signed in with GitHub (a browser session, or the app's device token):
 * - `GET /api/my/designs` lists them; `POST /api/my/designs` publishes a UI Builder design as a
 *   private variant of a library design; `POST /api/my/designs/<sha>/share` shares or unshares one
 *   (`{"shared": true}`).
 * - `POST /api/my/device-token` mints the token the app sends as `Authorization: Bearer`.
 * - `/my` is the same for a browser: the designs, share buttons and a device token to copy.
 *
 * Shared designs join everyone's manifest; private ones only their author's.
 */
fun Route.userDesignRoutes(
  holder: LibraryHolder,
  store: UserDesignStore,
  uiBuilder: UiBuilderIntegration?,
  github: GithubAuth,
) {
  suspend fun ApplicationCall.requireUser(): String? =
    github.user(this)
      ?: run {
        respondText("sign in with GitHub first", status = HttpStatusCode.Unauthorized)
        null
      }

  get("/api/my/designs") {
    val login = call.requireUser() ?: return@get
    call.respondJson(
      apiJson.encodeToString(ListSerializer(UserDesign.serializer()), store.designs(login))
    )
  }

  post("/api/my/designs") {
    val login = call.requireUser() ?: return@post
    val request = runCatching {
      apiJson.decodeFromString(PublishRequest.serializer(), call.receiveText())
    }
      .getOrElse {
        return@post call.respondText(
          "bad request: ${it.message}",
          status = HttpStatusCode.BadRequest,
        )
      }
    when (val result = publish(holder.current(), store, uiBuilder, login, request)) {
      is Published.Done ->
        call.respondJson(
          apiJson.encodeToString(UserDesign.serializer(), result.design),
          HttpStatusCode.Created,
        )
      is Published.Refused -> call.respondText(result.message, status = result.status)
    }
  }

  post("/my/designs") {
    val login = github.login(call) ?: return@post call.respondRedirect(github.signInPath("/my"))
    val form = call.receiveParameters()
    val library = holder.current()
    val base = library.design(form["base"].orEmpty().trim())
    val result =
      if (base == null) {
        Published.Refused(HttpStatusCode.UnprocessableEntity, "No card design with that id.")
      } else {
        publish(
          library,
          store,
          uiBuilder,
          login,
          PublishRequest(
            designId = form["designId"].orEmpty().trim(),
            key = base.key,
            variant = form["variant"].orEmpty().trim(),
            device = deviceMatchOf(form::get),
          ),
        )
      }
    call.response.header(HttpHeaders.CacheControl, "no-store")
    when (result) {
      is Published.Done -> call.respondRedirect("/my")
      is Published.Refused ->
        call.respondText(
          MyPage.html(login, store.designs(login), token = null, problem = result.message),
          ContentType.Text.Html,
          result.status,
        )
    }
  }

  post("/api/my/designs/{sha}/share") {
    val login = call.requireUser() ?: return@post
    val shared = runCatching {
      apiJson.decodeFromString(ShareRequest.serializer(), call.receiveText()).shared
    }
      .getOrElse {
        return@post call.respondText("bad request", status = HttpStatusCode.BadRequest)
      }
    val design =
      store.share(login, call.parameters["sha"].orEmpty(), shared) ?: return@post call.notFound()
    call.respondJson(apiJson.encodeToString(UserDesign.serializer(), design))
  }

  post("/api/my/device-token") {
    // Only a browser session mints one: a device token can't mint its successor.
    val login =
      github.login(call)
        ?: return@post call.respondText(
          "sign in with GitHub first",
          status = HttpStatusCode.Unauthorized,
        )
    call.respondJson("""{"token":"${github.deviceToken(login)}"}""")
  }

  get("/my") {
    val login = github.login(call) ?: return@get call.respondRedirect(github.signInPath("/my"))
    call.response.header(HttpHeaders.CacheControl, "no-store")
    call.respondText(MyPage.html(login, store.designs(login), token = null), ContentType.Text.Html)
  }

  post("/my/device-token") {
    val login = github.login(call) ?: return@post call.respondRedirect(github.signInPath("/my"))
    call.response.header(HttpHeaders.CacheControl, "no-store")
    call.respondText(
      MyPage.html(login, store.designs(login), token = github.deviceToken(login)),
      ContentType.Text.Html,
    )
  }

  post("/my/designs/{sha}/share") {
    val login = github.login(call) ?: return@post call.respondRedirect(github.signInPath("/my"))
    val shared = call.receiveParameters()["shared"] == "true"
    store.share(login, call.parameters["sha"].orEmpty(), shared) ?: return@post call.notFound()
    call.respondRedirect("/my")
  }
}

@Serializable private data class ShareRequest(val shared: Boolean)

private suspend fun ApplicationCall.respondJson(
  text: String,
  status: HttpStatusCode = HttpStatusCode.OK,
) {
  response.header(HttpHeaders.CacheControl, "no-store")
  respondText(text, ContentType.Application.Json, status)
}

/** `/my`: a signed-in person's designs, with share buttons and a device token for the app. */
object MyPage {
  fun html(
    login: String,
    designs: List<UserDesign>,
    token: String?,
    problem: String? = null,
  ): String = buildString {
    val esc = Pages::esc
    append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
    append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
    append("<title>My designs</title></head><body>")
    append("<h1>My designs</h1><p>Signed in as ${esc(login)}.</p>")
    append("<h2>The app</h2>")
    if (token == null) {
      append("<p>Your private designs show in harc once it has a device token.</p>")
      append(
        "<form method=\"post\" action=\"/my/device-token\"><button>Create a device token</button></form>"
      )
    } else {
      append("<p>Paste this into harc's settings, under the design server. Keep it private.</p>")
      append("<pre id=\"device-token\">${esc(token)}</pre>")
    }
    append("<h2>Publish</h2>")
    problem?.let { append("<p role=\"alert\">${esc(it)}</p>") }
    append("<form method=\"post\" action=\"/my/designs\">")
    append("<label>UI Builder design id <input name=\"designId\" required></label> ")
    append(
      "<label>Card design it varies (from /designs/&lt;id&gt;) <input name=\"base\" required></label> "
    )
    append(
      "<label>Variant name <input name=\"variant\" pattern=\"[a-z0-9][a-z0-9-]{0,39}\" required></label> "
    )
    append("<fieldset><legend>Only for one kind of device (optional)</legend>")
    append("<label>Integration <input name=\"integration\" placeholder=\"vaillant\"></label> ")
    append("<label>Manufacturer <input name=\"manufacturer\"></label> ")
    append("<label>Model <input name=\"model\" placeholder=\"ecoTEC*\"></label></fieldset>")
    append("<button>Publish privately</button></form>")
    append("<h2>Published</h2>")
    if (designs.isEmpty()) {
      append("<p>Nothing yet. Publish a design from UI Builder or the app's card studio.</p>")
    } else {
      append(
        "<table><tr><th>Card</th><th>Variant</th><th>Device</th><th>Theme</th><th>Shared</th><th></th></tr>"
      )
      for (design in designs) {
        val key = design.entry.key
        val next = !design.shared
        val device =
          design.entry.device?.let {
            listOfNotNull(it.integration, it.manufacturer, it.model).joinToString(" ")
          } ?: "any"
        append("<tr><td>${esc(key.cardType)}</td><td>${esc(key.variant)}</td>")
        append("<td>${esc(device)}</td>")
        append("<td>${esc(key.theme)}</td><td>${if (design.shared) "yes" else "no"}</td><td>")
        append("<form method=\"post\" action=\"/my/designs/${design.entry.sha256}/share\">")
        append("<input type=\"hidden\" name=\"shared\" value=\"$next\">")
        append("<button>${if (next) "Share" else "Stop sharing"}</button></form></td></tr>")
      }
      append("</table>")
    }
    append("</body></html>")
  }
}

private sealed interface Published {
  class Done(val design: UserDesign) : Published

  class Refused(val status: HttpStatusCode, val message: String) : Published
}

/**
 * Publish [request] as [login]'s design: export it from UI Builder, check it can stand in for the
 * library design it varies ([userTemplateProblem]) and store it, private.
 */
private suspend fun publish(
  library: DesignLibrary,
  store: UserDesignStore,
  uiBuilder: UiBuilderIntegration?,
  login: String,
  request: PublishRequest,
): Published {
  val client =
    uiBuilder?.client
      ?: return Published.Refused(
        HttpStatusCode.ServiceUnavailable,
        "This server has no UI Builder.",
      )
  if (!VARIANT.matches(request.variant) || request.variant == DesignKey.DEFAULT_VARIANT) {
    return Published.Refused(
      HttpStatusCode.BadRequest,
      "A variant is 1-40 lowercase letters, digits and dashes, and not \"default\".",
    )
  }
  if (request.device != null && request.device.specificity == 0) {
    return Published.Refused(HttpStatusCode.BadRequest, "A device match needs at least one field.")
  }
  if (request.designId.isBlank()) {
    return Published.Refused(HttpStatusCode.BadRequest, "Name the UI Builder design to publish.")
  }
  val baseKey = request.key.copy(variant = DesignKey.DEFAULT_VARIANT)
  val base =
    library.designs.firstOrNull { it.key == baseKey }
      ?: return Published.Refused(
        HttpStatusCode.UnprocessableEntity,
        "No library design for that key to vary.",
      )
  val exported =
    client.exportRc(request.designId, request.revision).getOrElse {
      return Published.Refused(HttpStatusCode.BadGateway, it.message ?: "Export failed.")
    }
  userTemplateProblem(exported.bytes, library.decode(base))?.let {
    return Published.Refused(
      HttpStatusCode.UnprocessableEntity,
      "That design can't be published: $it",
    )
  }
  return Published.Done(
    store.publish(
      login = login,
      key = baseKey.copy(variant = request.variant),
      bytes = exported.bytes,
      slots = base.entry.slots,
      sourceDesignId = request.designId,
      revision = exported.revision,
      device = request.device,
    )
  )
}

/** The device match a form names (`integration`, `manufacturer`, `model`), or null for none. */
private fun deviceMatchOf(field: (String) -> String?): DeviceMatch? {
  fun value(name: String) = field(name)?.trim()?.takeIf { it.isNotEmpty() }
  return DeviceMatch(
      integration = value("integration"),
      manufacturer = value("manufacturer"),
      model = value("model"),
    )
    .takeIf { it.specificity > 0 }
}
