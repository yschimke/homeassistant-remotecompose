package ee.schimke.ha.designserver

import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.client.design.validateCardDraft
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import java.net.URI
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/**
 * Bounded, expiring app-to-browser handoffs. Nothing is persisted or added to the public library.
 */
class CardStudioDrafts(private val clock: () -> Long = System::currentTimeMillis) {
  private data class Entry(
    val document: JsonObject,
    val expiresAt: Long,
    /** The card type the draft was seeded for: its seed's title, which the draft may rename. */
    val cardType: String? = null,
    var claimed: Boolean = false,
  )

  private val entries = LinkedHashMap<String, Entry>()

  @Synchronized
  fun put(document: JsonObject, cardType: String? = null): String {
    expire()
    check(entries.size < 128) { "Too many pending card previews. Try again later." }
    val token = UiBuilderConfig.randomToken()
    entries[token] = Entry(document, clock() + 10 * 60_000, cardType)
    return token
  }

  @Synchronized
  fun get(token: String): JsonObject? {
    expire()
    return entries[token]?.document
  }

  sealed interface Claim {
    data class Ready(val document: JsonObject, val cardType: String? = null) : Claim

    data object Busy : Claim

    data object Missing : Claim
  }

  @Synchronized
  fun claim(token: String): Claim {
    expire()
    val entry = entries[token] ?: return Claim.Missing
    if (entry.claimed) return Claim.Busy
    entry.claimed = true
    return Claim.Ready(entry.document, entry.cardType)
  }

  @Synchronized
  fun release(token: String) {
    entries[token]?.claimed = false
  }

  @Synchronized
  fun remove(token: String) {
    entries.remove(token)
  }

  private fun expire() {
    entries.entries.removeIf { it.value.expiresAt <= clock() }
  }
}

fun Route.cardStudioRoutes(
  holder: LibraryHolder,
  integration: UiBuilderIntegration?,
  github: GithubAuth?,
  /** Where a design opened for a signed-in person is recorded as theirs to publish. */
  made: UserDesignStore? = null,
  seeds: CardStudioDrafts = CardStudioDrafts(),
  handoffs: CardStudioDrafts = CardStudioDrafts(),
  /** The live relay (join codes, presence, conversation) around [agentSessions], when served. */
  relay: AgentRelay? = null,
  agentSessions: AgentDesignSessions = relay?.cards ?: AgentDesignSessions(),
  upstream: String? = System.getenv("LIBRARY_UPSTREAM"),
) {
  get("/api/studio/upstream") {
    call.response.header(HttpHeaders.CacheControl, "no-store")
    val configured =
      upstream?.takeIf {
        runCatching {
            val uri = URI(it)
            uri.scheme == "https" &&
              !uri.host.isNullOrBlank() &&
              uri.userInfo == null &&
              uri.query == null &&
              uri.fragment == null
          }
          .getOrDefault(false)
      }
        ?: return@get call.respondText(
          "No upstream design server configured",
          status = HttpStatusCode.NotFound,
        )
    call.respondText(
      buildJsonObject { put("url", configured) }.toString(),
      ContentType.Application.Json,
    )
  }
  // The Pages/Wasm app calls these anonymous JSON APIs from a different origin.
  // Browser-authorized create/share routes below deliberately remain same-origin.
  for (path in listOf("/api/studio/seed", "/api/studio/handoffs", "/api/studio/agent-sessions")) {
    options(path) {
      call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
      call.response.header(HttpHeaders.AccessControlAllowMethods, "POST, OPTIONS")
      call.response.header(HttpHeaders.AccessControlAllowHeaders, "Content-Type")
      call.respondText("", status = HttpStatusCode.NoContent)
    }
  }
  for (path in listOf("/api/studio/agent-session", "/api/studio/agent-asset")) options(path) {
    call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
    call.response.header(HttpHeaders.AccessControlAllowMethods, "GET, POST, DELETE, OPTIONS")
    call.response.header(HttpHeaders.AccessControlAllowHeaders, "Authorization, Content-Type")
    call.respondText("", status = HttpStatusCode.NoContent)
  }
  get("/api/studio/agent-asset") {
    call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
    call.response.header(HttpHeaders.CacheControl, "no-store")
    val key = call.request.queryParameters["key"].orEmpty()
    val asset =
      agentSessions.asset(call.agentSessionToken(), key)
        ?: return@get call.respondText("Image unavailable", status = HttpStatusCode.NotFound)
    call.respondBytes(asset.bytes, ContentType.parse(asset.mediaType))
  }
  post("/api/studio/agent-sessions") {
    call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
    call.response.header(HttpHeaders.CacheControl, "no-store")
    if (integration == null)
      return@post call.respondText(
        "UI Builder is not configured",
        status = HttpStatusCode.ServiceUnavailable,
      )
    val input =
      try {
        val text = call.receiveText()
        require(text.length <= 132_000)
        Json.parseToJsonElement(text).jsonObject
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        return@post call.respondText("Invalid design request", status = HttpStatusCode.BadRequest)
      }
    val document =
      input["document"] as? JsonObject
        ?: return@post call.respondText("Missing document", status = HttpStatusCode.BadRequest)
    val instruction = (input["instruction"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val id = (document["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    if (!id.startsWith("card-"))
      return@post call.respondText("Invalid card draft", status = HttpStatusCode.BadRequest)
    val seed =
      seeds.get(id.removePrefix("card-"))
        ?: return@post call.respondText(
          "Preview expired; reload the card",
          status = HttpStatusCode.Gone,
        )
    try {
      validateCardDraft(JsonObject(seed + ("id" to JsonPrimitive(id))), document)
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      return@post call.respondText("Invalid design request", status = HttpStatusCode.BadRequest)
    }
    val access =
      try {
        agentSessions.create(instruction, document)
      } catch (e: IllegalStateException) {
        return@post call.respondText(
          "Design session capacity reached",
          status = HttpStatusCode.TooManyRequests,
        )
      } catch (e: IllegalArgumentException) {
        return@post call.respondText("Invalid design request", status = HttpStatusCode.BadRequest)
      }
    val joinCode = relay?.register(AgentRelay.Kind.Card, access.owner, access.agent, instruction)
    call.respondText(
      buildJsonObject {
        put("ownerToken", access.owner)
        put("agentToken", access.agent)
        put("expiresInSeconds", 3600)
        joinCode?.let {
          put("joinCode", it)
          put("relay", true)
        }
      }
        .toString(),
      ContentType.Application.Json,
    )
  }

  get("/api/studio/agent-session") {
    call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
    call.response.header(HttpHeaders.CacheControl, "no-store")
    val session =
      agentSessions.read(call.agentSessionToken())
        ?: return@get call.respondText(
          "Design session unavailable",
          status = HttpStatusCode.NotFound,
        )
    call.respondText(
      buildJsonObject {
        put("instruction", session.instruction)
        put("document", session.document)
        put("revision", session.revision)
      }
        .toString(),
      ContentType.Application.Json,
    )
  }

  post("/api/studio/agent-session") {
    call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
    call.response.header(HttpHeaders.CacheControl, "no-store")
    val body =
      try {
        val text = call.receiveText()
        require(text.length <= 132_000)
        Json.parseToJsonElement(text).jsonObject
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        return@post call.respondText("Invalid design draft", status = HttpStatusCode.BadRequest)
      }
    val revision =
      (body["revision"] as? JsonPrimitive)?.longOrNull
        ?: return@post call.respondText("Missing revision", status = HttpStatusCode.BadRequest)
    val document =
      body["document"] as? JsonObject
        ?: return@post call.respondText("Missing document", status = HttpStatusCode.BadRequest)
    try {
      if (!agentSessions.submit(call.agentSessionToken(), revision, document))
        return@post call.respondText("Design session unavailable", status = HttpStatusCode.NotFound)
      relay?.seen(call.agentSessionToken(), "Draft submitted")
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      return@post call.respondText(
        "Invalid or stale design draft",
        status = HttpStatusCode.Conflict,
      )
    }
    call.respondText("Draft submitted; the person must review and publish it.")
  }

  delete("/api/studio/agent-session") {
    call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
    call.response.header(HttpHeaders.CacheControl, "no-store")
    if (
      !(relay?.revoke(call.agentSessionToken()) ?: agentSessions.revoke(call.agentSessionToken()))
    )
      return@delete call.respondText("Design session unavailable", status = HttpStatusCode.NotFound)
    call.respondText("Design session revoked")
  }

  post("/api/studio/agent-mcp") {
    call.response.header(HttpHeaders.CacheControl, "no-store")
    if (call.request.headers[HttpHeaders.Origin] != null)
      return@post call.respondText(
        "Browser origins are not supported",
        status = HttpStatusCode.Forbidden,
      )
    val token = call.agentSessionToken()
    if (!agentSessions.isAgent(token))
      return@post call.respondText(
        "Design session unavailable",
        status = HttpStatusCode.Unauthorized,
      )
    val message =
      try {
        val text = call.receiveText()
        require(text.length <= 1_500_000)
        Json.parseToJsonElement(text).jsonObject
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        return@post call.respondText("Invalid MCP request", status = HttpStatusCode.BadRequest)
      }
    relay?.seen(token)
    val response = AgentDesignMcp.handle(message, token, agentSessions)
    if (response == null) call.respondText("", status = HttpStatusCode.Accepted)
    else call.respondText(response.toString(), ContentType.Application.Json)
  }
  post("/api/studio/seed") {
    call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
    if (integration == null)
      return@post call.respondText(
        "UI Builder is not configured",
        status = HttpStatusCode.ServiceUnavailable,
      )
    val seed =
      try {
        val text = call.receiveText()
        require(text.length <= 128_000)
        val (card, states) = CardSchema.parseRequest(Json.parseToJsonElement(text))
        val schema = CardSchema.fromCard(card, states, holder.current())
        JsonObject(
          UiBuilderSeed.document(
            schema,
            "card-preview",
            schema.cardType,
            HaCardsSeed.card("app-card"),
          ) - "template"
        )
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        return@post call.respondText("Invalid card preview", status = HttpStatusCode.BadRequest)
      }
    val token =
      try {
        seeds.put(seed)
      } catch (e: IllegalStateException) {
        return@post call.respondText(
          "Preview capacity reached",
          status = HttpStatusCode.TooManyRequests,
        )
      }
    // The random id identifies the original contract for the subsequent draft gate.
    val document = JsonObject(seed + ("id" to JsonPrimitive("card-$token")))
    call.response.header(HttpHeaders.CacheControl, "no-store")
    call.respondText(document.toString(), ContentType.Application.Json)
  }

  post("/api/studio/handoffs") {
    call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
    if (integration == null)
      return@post call.respondText(
        "UI Builder is not configured",
        status = HttpStatusCode.ServiceUnavailable,
      )
    val (document, cardType) = call.receiveDraft(seeds) ?: return@post
    val token =
      try {
        handoffs.put(document, cardType)
      } catch (e: IllegalStateException) {
        return@post call.respondText(
          "Preview capacity reached",
          status = HttpStatusCode.TooManyRequests,
        )
      }
    call.response.header(HttpHeaders.CacheControl, "no-store")
    call.respondText(
      buildJsonObject { put("path", "/studio/$token") }.toString(),
      ContentType.Application.Json,
    )
  }

  // The app's own way in: its device token says who is designing, so nobody signs in to a web page
  // here. The draft is checked against its seed as a handoff is, then opened in UI Builder, shared
  // with them, and recorded as theirs to publish.
  post("/api/studio/open") {
    call.response.header(HttpHeaders.CacheControl, "no-store")
    val builder =
      integration
        ?: return@post call.respondText(
          "UI Builder is not configured",
          status = HttpStatusCode.ServiceUnavailable,
        )
    if (call.isCrossOrigin())
      return@post call.respondText(
        "same-origin request required",
        status = HttpStatusCode.Forbidden,
      )
    val login =
      github?.user(call)
        ?: return@post call.respondText(
          "Sign in to the design server first",
          status = HttpStatusCode.Unauthorized,
        )
    val (document, cardType, assets) = call.receiveDraft(seeds, allowAssets = true) ?: return@post
    // Stands in for a handoff token: what the app matches its published design by.
    val handoff = UiBuilderConfig.randomToken()
    val url =
      try {
        builder.openInBuilder(document, login, cardType, handoff, made, assets)
      } catch (e: StudioOpenException) {
        call.application.environment.log.warn("Card studio open failed", e)
        return@post call.respondText(e.message!!, status = HttpStatusCode.BadGateway)
      }
    call.respondText(
      buildJsonObject {
        put("url", url)
        put("handoff", handoff)
      }
        .toString(),
      ContentType.Application.Json,
    )
  }

  get("/studio/{token}") {
    val token = call.parameters["token"].orEmpty()
    val document =
      handoffs.get(token)
        ?: return@get call.respondStudioPage(
          heading = "This preview has expired",
          body =
            "<p>Card previews last ten minutes. Go back to the app and choose " +
              "<b>Design in UI Builder</b> again; your layout edits there are kept.</p>",
          status = HttpStatusCode.Gone,
          integration = integration,
        )
    val editor = call.editor(integration, github)
    // Checked before asking anyone to sign in: a builder without the card catalog can't open it.
    val served = integration?.client?.servesCatalog(document.catalogSystem()) ?: true
    val action =
      when {
        !served ->
          notice(
            "UI Builder on this server can't open card drafts right now: it isn't serving the " +
              "<code>${studioEscape(document.catalogSystem())}</code> catalog. Your draft is kept " +
              "for ten minutes, so reload this page to try again. If it keeps happening, the " +
              "design server's operator needs to publish that catalog."
          )
        editor.authorized -> OPEN_FORM
        editor.signInPath != null ->
          "<ol><li>Sign in with GitHub, so the design is saved as yours.</li>" +
            "<li>Open the draft in UI Builder and change the layout.</li>" +
            "<li>Back in the app, choose <b>Publish privately and use it</b>.</li></ol>" +
            "<p><a class=\"button\" href=\"${studioEscape(editor.signInPath)}\">" +
            "Sign in with GitHub to continue</a></p>"
        else -> "<p>Open the design server's edit link in this browser first, then return here.</p>"
      }
    call.respondStudioPage(
      heading = "Design this card",
      body = INTRO + action + draftDetails(document),
      integration = integration,
    )
  }

  post("/studio/{token}") {
    val builder =
      integration
        ?: return@post call.respondText(
          "UI Builder is not configured",
          status = HttpStatusCode.ServiceUnavailable,
        )
    val editor = call.editor(builder, github)
    if (!editor.authorized)
      return@post call.respondText("Sign in to design this card", status = HttpStatusCode.Forbidden)
    val token = call.parameters["token"].orEmpty()
    val claim = handoffs.claim(token)
    val document =
      when (claim) {
        is CardStudioDrafts.Claim.Ready -> claim.document
        CardStudioDrafts.Claim.Busy ->
          return@post call.respondText(
            "This draft is already opening",
            status = HttpStatusCode.Conflict,
          )
        CardStudioDrafts.Claim.Missing ->
          return@post call.respondStudioPage(
            heading = "This preview has expired",
            body =
              "<p>Card previews last ten minutes. Go back to the app and choose " +
                "<b>Design in UI Builder</b> again; your layout edits there are kept.</p>",
            status = HttpStatusCode.Gone,
            integration = integration,
          )
      }
    val location =
      try {
        builder
          .openInBuilder(
            document,
            editor.login,
            (claim as CardStudioDrafts.Claim.Ready).cardType,
            token,
            made,
          )
          .also { handoffs.remove(token) }
      } catch (e: StudioOpenException) {
        call.application.environment.log.warn("Card studio handoff failed", e)
        return@post call.respondStudioPage(
          heading = "UI Builder couldn't open this draft",
          body =
            notice(studioEscape(e.message!!)) +
              "<p>Your draft is kept for ten minutes, so you can try again.</p>" +
              OPEN_FORM.replace("Open this draft in UI Builder", "Try again") +
              draftDetails(document),
          status = HttpStatusCode.BadGateway,
          integration = integration,
        )
      } finally {
        // Failures and cancellation leave the handoff available for a deliberate retry.
        handoffs.release(token)
      }
    call.response.header(HttpHeaders.CacheControl, "no-store")
    call.respondRedirect(location)
  }
}

private fun ApplicationCall.agentSessionToken(): String =
  request.headers[HttpHeaders.Authorization]
    ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
    ?.substring(7)
    ?.trim()
    .orEmpty()

/**
 * The posted draft and the card type its seed was made for, once it is checked against that seed;
 * null once the refusal is sent (410 when the seed expired, 400 when the draft breaks its
 * contract).
 */
private suspend fun ApplicationCall.receiveDraft(
  seeds: CardStudioDrafts,
  allowAssets: Boolean = false,
): Triple<JsonObject, String?, Map<String, ByteArray>>? =
  try {
    val text = receiveText()
    require(text.length <= if (allowAssets) 3_000_000 else 128_000)
    val body = Json.parseToJsonElement(text).jsonObject
    val document = (body["document"] as? JsonObject) ?: body
    val encoded = (body["assetData"] as? JsonObject).orEmpty()
    val bindings = (document["assets"] as? JsonObject).orEmpty()
    require(
      (encoded.isEmpty() && bindings.isEmpty()) || (allowAssets && encoded.keys == bindings.keys)
    )
    val assets = encoded.mapValues { (key, value) ->
      val data = value.jsonPrimitive.content
      require(data.length <= 1_400_000)
      val bytes = Base64.getDecoder().decode(data)
      require(bytes.isNotEmpty() && bytes.size <= AgentDesignSessions.MAX_ASSET_BYTES)
      val type = requireNotNull(AgentDesignSessions.imageType(bytes))
      val binding = bindings.getValue(key).jsonObject
      require(
        binding["mediaType"]?.jsonPrimitive?.content == type &&
          binding["contentDigest"]?.jsonPrimitive?.content == "sha256:${Sha256.hex(bytes)}"
      )
      bytes
    }
    require(assets.values.sumOf { it.size } <= AgentDesignSessions.MAX_ASSET_TOTAL_BYTES)
    val id = document["id"]!!.jsonPrimitive.content
    val original = seeds.get(id.removePrefix("card-"))
    if (original == null) {
      respondText("Preview expired; reload the card", status = HttpStatusCode.Gone)
      null
    } else {
      validateCardDraft(JsonObject(original + ("id" to JsonPrimitive(id))), document)
      Triple(document, original["title"]?.jsonPrimitive?.contentOrNull, assets)
    }
  } catch (e: Exception) {
    if (e is CancellationException) throw e
    respondText("Invalid card draft", status = HttpStatusCode.BadRequest)
    null
  }

/**
 * Why a draft couldn't be opened in UI Builder, in words for the person: which step failed, never
 * the builder's own response, which may echo the draft.
 */
class StudioOpenException(message: String, cause: Throwable) : Exception(message, cause)

/**
 * Creates [document] as a new UI Builder design and returns the link to edit it. For [login] it is
 * shared with them and recorded as theirs to publish, opened from [handoff] for [cardType]; with no
 * login (an edit-link browser) the link carries the operator token instead.
 */
private suspend fun UiBuilderIntegration.openInBuilder(
  document: JsonObject,
  login: String?,
  cardType: String?,
  handoff: String,
  made: UserDesignStore?,
  assets: Map<String, ByteArray> = emptyMap(),
): String {
  var created: String? = null
  try {
    // New id on every attempt: no existing design can be overwritten, including retries.
    // Omit image dimensions from the initial bindings: upload then writes the bytes even when the
    // same digest was supplied, and the builder fills in the dimensions it actually decoded.
    val bindings =
      (document["assets"] as? JsonObject).orEmpty().mapValues { (_, value) ->
        JsonObject(value.jsonObject - "widthPx" - "heightPx")
      }
    val draft =
      JsonObject(
        document +
          ("id" to JsonPrimitive("card-${UiBuilderConfig.randomToken()}")) +
          ("assets" to JsonObject(bindings)) - "template"
      )
    val id = client.create(draft, resolveCatalog = true)
    created = id
    assets.forEach { (key, bytes) -> client.putAsset(id, key, bytes) }
    if (login == null) return config.editorUrl(id)
    client.share(id, "github:$login", editor = true)
    // Theirs to publish from the app (`/api/my/studio`), though UI Builder records the server as
    // its owner.
    made?.recordMade(login, id, cardType = cardType, handoff = handoff)
    return config.designUrl(id)
  } catch (e: Exception) {
    if (e is CancellationException) throw e
    throw StudioOpenException(
      when {
        e is CatalogUnavailableException ->
          "UI Builder isn't serving the ${e.systemId} catalog right now, so it couldn't open " +
            "this draft. Nothing was created."
        created == null ->
          "UI Builder refused this draft. Nothing was created. Try again; if it keeps failing, " +
            "refresh the preview data and open it again."
        else ->
          "UI Builder made the design but couldn't attach its images or grant access. Try again."
      },
      e,
    )
  }
}

private const val INTRO =
  "<p>This opens a private copy of the card in UI Builder, filled with the card's current " +
    "values. Nothing changes on your dashboard until you publish it from the app.</p>"

private const val OPEN_FORM =
  "<form method=\"post\"><button>Open this draft in UI Builder</button></form>"

private fun notice(html: String) = "<p class=\"notice\" role=\"alert\">$html</p>"

private fun draftDetails(document: JsonObject) =
  "<details><summary>Technical details</summary><pre>${studioEscape(document.toString())}</pre>" +
    "</details>"

/** The catalog the draft is pinned to: the one UI Builder must serve to open it. */
private fun JsonObject.catalogSystem(): String =
  (this["catalogPin"] as? JsonObject)?.get("systemId")?.jsonPrimitive?.contentOrNull ?: "ha-cards"

/**
 * A studio handoff page: no script, no referrer, and a form that may post only here or to the
 * builder (Chromium applies `form-action` to the redirect too, including a separately hosted one).
 */
private suspend fun ApplicationCall.respondStudioPage(
  heading: String,
  body: String,
  integration: UiBuilderIntegration?,
  status: HttpStatusCode = HttpStatusCode.OK,
) {
  val builderOrigin =
    integration
      ?.config
      ?.browserUrl
      ?.let { url ->
        val uri = URI(url)
        require(uri.scheme in setOf("http", "https") && uri.host != null)
        URI(uri.scheme, null, uri.host, uri.port, null, null, null).toASCIIString()
      }
      .orEmpty()
  response.header(HttpHeaders.CacheControl, "no-store")
  response.header("Referrer-Policy", "no-referrer")
  response.header("X-Robots-Tag", "noindex, nofollow")
  response.header(
    "Content-Security-Policy",
    "default-src 'none'; style-src 'unsafe-inline'; form-action 'self' $builderOrigin; frame-ancestors 'self'",
  )
  respondText(
    """
    <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <title>$heading</title><style>
    body{font:16px/1.5 system-ui,sans-serif;max-width:640px;margin:0 auto;padding:16px 16px 32px;color:#1b1c1f;background:#fafafc}
    h1{font-size:1.5rem;line-height:1.25;margin:8px 0 12px}ol{padding-left:1.25em}li{margin:4px 0}
    button,a.button{display:inline-block;font:inherit;font-weight:600;padding:10px 20px;border:0;border-radius:999px;background:#25628e;color:#fff;text-decoration:none;cursor:pointer}
    .notice{padding:12px 16px;border-radius:12px;background:#fde7e6;color:#5c1513}
    details{margin-top:24px;color:#555}pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px}
    @media (prefers-color-scheme:dark){body{background:#121316;color:#e3e2e6}.notice{background:#4f1d1b;color:#ffdad6}details{color:#aaa}button,a.button{background:#94ccf9;color:#00344f}}
    </style>
    <h1>$heading</h1>$body</html>
    """
      .trimIndent(),
    ContentType.Text.Html,
    status,
  )
}

private fun studioEscape(text: String): String =
  text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")
