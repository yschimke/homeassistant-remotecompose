package ee.schimke.ha.designserver

import ee.schimke.composeai.rcplayer.edit.RcTargetProfile
import ee.schimke.composeai.rcplayer.edit.RcTargetProfiles
import ee.schimke.composeai.rcplayer.edit.namedVariables
import ee.schimke.composeai.rcplayer.edit.validateFor
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignManifest
import ee.schimke.ha.design.DesignSurface
import ee.schimke.ha.design.DeviceMatch
import ee.schimke.ha.design.HaSharedValues
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
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A manifest as one caller sees it: its bytes and their ETag. Clients revalidate by ETag, so a
 * signed-in device and an anonymous one each get the version they were sent.
 */
class ServedManifest(val bytes: ByteArray, val etag: String)

/**
 * The manifest [login] (null for anyone) is served: the library's, plus every design its author
 * shared, plus [login]'s own private ones. Its serial adds a store revision to the library's (the
 * shared-set revision for anyone, every change for a signed-in caller), so it rises whenever either
 * changes and never falls, even once the last shared design is unshared: a client never takes it
 * for a rollback. Only while the store has never changed what the caller sees does the library's
 * manifest go out byte for byte, signature and all.
 */
fun DesignLibrary.manifestFor(store: UserDesignStore?, login: String?): ServedManifest {
  val offset = store?.let { if (login == null) it.sharedRevision else it.revision } ?: 0L
  if (store == null || offset == 0L) return ServedManifest(manifestBytes, manifestEtag)
  val extra = (store.shared() + login?.let(store::designs).orEmpty()).distinctBy { d -> d.entry }
  val merged: DesignManifest =
    manifest.copy(
      serial = manifest.serial + offset,
      designs = manifest.designs + extra.map { it.served },
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
 * Why [bytes] can't stand in for [base], or null when they can: they must decode, play on [profile]
 * (the app's Remote Compose profile, or a Wear widget's), and bind only values the card provides
 * (what [base] binds, or a value every home shares: [HaSharedValues]). Named values without a dot
 * are the design's own state, which a card never pushes, so they are allowed; so are the colours a
 * Wear widget host supplies (`WearM3.*`) when [profile] is the Wear widgets'.
 *
 * With [likeBase], ops outside [profile] that [base] uses too are allowed: for a template this
 * server captured as it captures the library, which the app already plays with them.
 */
fun userTemplateProblem(
  bytes: ByteArray,
  base: DecodedTemplate,
  profile: RcTargetProfile = RcTargetProfiles.ANDROIDX_EXPERIMENTAL,
  likeBase: Boolean = false,
): String? {
  val document = runCatching {
    RcDocumentCodec.decode(bytes, RcWireLimits())
  }
    .getOrElse {
      return "it isn't a Remote Compose document (${it.message ?: it.javaClass.simpleName})"
    }
  val tolerated = if (likeBase) base.violations.toSet() else emptySet()
  document
    .validateFor(profile)
    .firstOrNull { it.message !in tolerated }
    ?.let {
      return "it uses something the app can't play: ${it.message}"
    }
  val provided = base.bindings.map { it.name }.toSet()
  val read = document.namedVariables.map { it.name.removePrefix(DesignLibrary.USER_PREFIX) }
  val unshared =
    read.filter { HaSharedValues.isShared(it) && HaSharedValues.valueOf(it) == null }.distinct()
  if (unshared.isNotEmpty()) {
    return "it reads ${unshared.sorted().joinToString()}, which no home shares. The shared " +
      "values are ${HaSharedValues.values.joinToString { it.name }}"
  }
  // The player drops a pushed value whose type the document declares differently.
  document.namedVariables
    .firstOrNull { variable ->
      HaSharedValues.valueOf(variable.name)?.let { variable.type != playerType(it.kind) } == true
    }
    ?.let {
      val value = HaSharedValues.valueOf(it.name)!!
      return "it reads ${value.name} as the wrong type: it's a ${value.kind.name.lowercase()}"
    }
  val unknown =
    read
      .filter { '.' in it && it !in provided }
      // A watch doesn't push shared values yet: a Wear widget reading one would never update.
      .filterNot { profile != RcTargetProfiles.WEAR_WIDGETS && HaSharedValues.valueOf(it) != null }
      .filterNot {
        profile == RcTargetProfiles.WEAR_WIDGETS &&
          (it.startsWith(WEAR_HOST_COLOURS) || it in WEAR_HOST_FRAME)
      }
      .distinct()
      .sorted()
  if (unknown.isNotEmpty()) {
    return "it reads values this card doesn't provide: ${unknown.joinToString()}"
  }
  return null
}

/** The named variable type a host pushes a shared value of [kind] as: a flag is an int. */
private fun playerType(kind: HaSharedValues.Kind): Int =
  when (kind) {
    HaSharedValues.Kind.Text -> RcNamedVariable.STRING_TYPE
    HaSharedValues.Kind.Bool -> RcNamedVariable.INT_TYPE
    HaSharedValues.Kind.Float -> RcNamedVariable.FLOAT_TYPE
  }

/** The named colours a Glance Wear widget host supplies (Remote Material 3's). */
private const val WEAR_HOST_COLOURS = "WearM3."

/**
 * The container frame a Wear widget exported with `hostFrame: device` leaves to the watch: exactly
 * the values the watch's `WearWidgetTemplate` writes its own host's into, and nothing else under
 * their prefix.
 */
private val WEAR_HOST_FRAME =
  setOf(
    "wear.container.horizontal_padding",
    "wear.container.vertical_padding",
    "wear.container.corner_radius",
  )

private val VARIANT = Regex("[a-z0-9][a-z0-9-]{0,39}")

/**
 * A person's own designs, signed in with GitHub (a browser session, or the app's device token):
 * - `GET /api/my/designs` lists them; `POST /api/my/designs` publishes a UI Builder design as a
 *   private variant of a library design; `POST /api/my/designs/<sha>/share` shares or unshares one
 *   (`{"shared": true}`). Only a design they own or edit in UI Builder, or one this server made for
 *   them, publishes; it publishes for its card in each theme of its card style.
 * - `GET /api/my/studio` lists the UI Builder designs this server made for them and the ones they
 *   own or edit in UI Builder: what the app's card studio publishes.
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
  /** Answers 403 to a POST another host's page sent ([isCrossOrigin]); true when it did. */
  suspend fun ApplicationCall.refusedCrossOrigin(): Boolean =
    isCrossOrigin().also {
      if (it) respondText("same-origin request required", status = HttpStatusCode.Forbidden)
    }

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

  // The UI Builder designs this server opened for them (the app's card studio, a seeded schema or
  // fork) and the ones they own or edit there themselves, newest first: what the app offers to
  // publish for a card. A design in both is listed once, as this server recorded it.
  get("/api/my/studio") {
    val login = call.requireUser() ?: return@get
    val made = store.made(login)
    val builder =
      uiBuilder?.client?.designsFor(login)?.getOrElse {
        return@get call.respondText(
          "UI Builder couldn't list your designs: ${it.message}",
          status = HttpStatusCode.BadGateway,
        )
      }
    val designs =
      (made + builder.orEmpty())
        .distinctBy { it.designId }
        .sortedByDescending { it.createdAtEpochMs }
    call.respondJson(apiJson.encodeToString(ListSerializer(MadeDesign.serializer()), designs))
  }

  post("/api/my/designs") {
    if (call.refusedCrossOrigin()) return@post
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
    if (call.refusedCrossOrigin()) return@post
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
    if (call.refusedCrossOrigin()) return@post
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
    if (call.refusedCrossOrigin()) return@post
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
    if (call.refusedCrossOrigin()) return@post
    val login = github.login(call) ?: return@post call.respondRedirect(github.signInPath("/my"))
    call.response.header(HttpHeaders.CacheControl, "no-store")
    call.respondText(
      MyPage.html(login, store.designs(login), token = github.deviceToken(login)),
      ContentType.Text.Html,
    )
  }

  post("/my/designs/{sha}/share") {
    if (call.refusedCrossOrigin()) return@post
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
        val card = key.cardType + (key.container?.let { " (Wear widget, $it)" } ?: "")
        append("<tr><td>${esc(card)}</td><td>${esc(key.variant)}</td>")
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
 * Publish [request] as [login]'s design, private: check they may edit the UI Builder design
 * ([mayPublish]), turn it into a template ([templates]) for the library design it varies and for
 * that design in the other themes of its card style (light and dark; Classic in every app palette),
 * check each can stand in for its base ([userTemplateProblem]) and store them. One design id covers
 * them all, so a card switched to it keeps it when the dashboard changes mode or palette.
 */
private suspend fun publish(
  library: DesignLibrary,
  store: UserDesignStore,
  uiBuilder: UiBuilderIntegration?,
  login: String,
  request: PublishRequest,
): Published {
  val integration =
    uiBuilder
      ?: return Published.Refused(
        HttpStatusCode.ServiceUnavailable,
        "This server has no UI Builder.",
      )
  val client = integration.client
  if (!VARIANT.matches(request.variant) || request.variant == DesignKey.DEFAULT_VARIANT) {
    return Published.Refused(
      HttpStatusCode.BadRequest,
      "A variant is 1-40 lowercase letters, digits and dashes, and not \"default\".",
    )
  }
  if (request.device != null && request.device.normalized().specificity == 0) {
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
  mayPublish(client, store, login, request.designId)?.let {
    return Published.Refused(HttpStatusCode.Forbidden, it)
  }
  // The same card in each theme of its card style (light and dark; Classic in every app palette),
  // the requested one first: what a dashboard switches between without picking another style.
  val bases =
    listOf(base) +
      library.designs.filter {
        it !== base &&
          !it.entry.revoked &&
          it.key.cardStyle == baseKey.cardStyle &&
          it.key.copy(theme = baseKey.theme) == baseKey
      }
  val templates =
    templates(integration, request.designId, request.revision, bases.map { it.key }).getOrElse {
      return Published.Refused(HttpStatusCode.UnprocessableEntity, it.message ?: "Export failed.")
    }
  userTemplateProblem(
      templates.bytes.getValue(baseKey),
      library.decode(base),
      likeBase = templates.compiled,
    )
    ?.let {
      return Published.Refused(
        HttpStatusCode.UnprocessableEntity,
        "That design can't be published: $it",
      )
    }
  // Stored canonical, so a republish that only changes case replaces its earlier design.
  val device = request.device?.normalized()
  val title =
    templates.source?.get("title")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
  val published = bases.mapNotNull { theme ->
    val bytes = templates.bytes[theme.key] ?: return@mapNotNull null
    // Another theme's base binds what the requested one does; one that doesn't is left out.
    if (
      theme !== base &&
        userTemplateProblem(bytes, library.decode(theme), likeBase = templates.compiled) != null
    ) {
      return@mapNotNull null
    }
    store.publish(
      login = login,
      key = theme.key.copy(variant = request.variant),
      bytes = bytes,
      slots = theme.entry.slots,
      sourceDesignId = request.designId,
      revision = templates.revision,
      device = device,
      title = title,
    )
  }
  val design = published.first()
  // Its Wear widgets, from the same UI Builder revision. Best effort: a card that can't be a Wear
  // widget still publishes, and the watch draws the card's own design instead.
  val wear =
    if (design.entry.key.surface != DesignSurface.MobileApp) emptyMap()
    else templates.source?.let { wearWidgets(client, it, library.decode(base)) }.orEmpty()
  store.publishWear(login, design, wear)
  return Published.Done(design)
}

/**
 * Why [login] may not publish UI Builder design [designId], or null when they may: this server made
 * it for them (the app's card studio, a seeded schema or fork), or UI Builder says they own it or
 * edit it. The design server reads designs with UI Builder's operator token, which opens every
 * design, so this is what keeps one person's private design (their photos, say) out of another's
 * manifest.
 */
private suspend fun mayPublish(
  client: UiBuilderClient,
  store: UserDesignStore,
  login: String,
  designId: String,
): String? {
  if (store.madeFor(designId).equals(login, ignoreCase = true)) return null
  val actor = UserDesignStore.authorOf(login)
  val editors =
    client.editors(designId).getOrElse {
      return "UI Builder couldn't say who edits that design, so it can't be published."
    }
  if (editors.any { it.equals(actor, ignoreCase = true) }) return null
  return "Only a design you own or edit in UI Builder can be published."
}

/** One compile at a time: each is a composition, and a burst shouldn't starve requests. */
private val compiles = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

/** A UI Builder design as templates by key, from one revision of it ([source]). */
private class Templates(
  val bytes: Map<DesignKey, ByteArray>,
  val revision: Long?,
  val source: JsonObject?,
  /** Captured here, as the library is, rather than exported by UI Builder. */
  val compiled: Boolean = false,
)

/**
 * [designId] as templates for [keys] (one card in each of its themes): UI Builder's own Remote
 * Compose export when it serves one (the same bytes for every key), otherwise compiled here
 * ([UiBuilderDesignCompiler]) from the design and its pictures, once per key so each theme's
 * colours are its own. A failure says why, from UI Builder and from the compiler.
 */
private suspend fun templates(
  integration: UiBuilderIntegration,
  designId: String,
  revision: Long?,
  keys: List<DesignKey>,
): Result<Templates> {
  val client = integration.client
  val exported = client.exportRc(designId, revision)
  exported.getOrNull()?.let { document ->
    // The document, for its title (what the app's design picker shows) and the Wear widgets. Best
    // effort: without it the design publishes untitled.
    val source = client.design(designId, document.revision).getOrNull()
    return Result.success(
      Templates(keys.associateWith { document.bytes }, document.revision, source)
    )
  }
  val source =
    client.design(designId, revision).getOrElse {
      return Result.failure(it)
    }
  val assets =
    source["assets"]?.jsonObject.orEmpty().keys.associateWith { key ->
      client.asset(designId, key).getOrElse {
        return Result.failure(it)
      }
    }
  val compiler = integration.compiler
  val problems = compiler.problems(source, assets.keys)
  if (problems.isNotEmpty()) {
    return Result.failure(
      IllegalStateException(
        "UI Builder couldn't export it (${exported.exceptionOrNull()?.message}), and it can't be " +
          "built here: ${problems.joinToString("; ")}"
      )
    )
  }
  val bytes =
    try {
      withContext(compiles) { keys.associateWith { compiler.compile(source, assets, it) } }
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      return Result.failure(
        IllegalStateException("it couldn't be built here: ${e.message ?: e.javaClass.simpleName}")
      )
    }
  return Result.success(
    Templates(
      bytes,
      source["revision"]?.jsonPrimitive?.longOrNull ?: revision,
      source,
      compiled = true,
    )
  )
}

/**
 * The Wear widget designs of UI Builder design [source] (at the revision published), by container:
 * the design rooted on each container ([WearWidgetDesigns]), exported, and kept when it plays as a
 * Wear widget and binds what [base] does. A container whose design fails is left out.
 */
private suspend fun wearWidgets(
  client: UiBuilderClient,
  source: JsonObject,
  base: DecodedTemplate,
): Map<String, ByteArray> {
  return DesignKey.WEAR_CONTAINERS.mapNotNull { container ->
      val document = WearWidgetDesigns.document(source, container) ?: return@mapNotNull null
      val bytes = client.exportRc(document).getOrNull() ?: return@mapNotNull null
      bytes
        .takeIf { userTemplateProblem(it, base, RcTargetProfiles.WEAR_WIDGETS) == null }
        ?.let { container to it }
    }
    .toMap()
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
