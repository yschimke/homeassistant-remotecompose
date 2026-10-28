package ee.schimke.ha.designserver

import ee.schimke.ha.client.design.validateCardDraft
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import java.net.URI
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
) {
  // The Pages/Wasm app calls these anonymous JSON APIs from a different origin.
  // Browser-authorized create/share routes below deliberately remain same-origin.
  for (path in listOf("/api/studio/seed", "/api/studio/handoffs")) {
    options(path) {
      call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
      call.response.header(HttpHeaders.AccessControlAllowMethods, "POST, OPTIONS")
      call.response.header(HttpHeaders.AccessControlAllowHeaders, "Content-Type")
      call.respondText("", status = HttpStatusCode.NoContent)
    }
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
    var cardType: String? = null
    val document =
      try {
        val text = call.receiveText()
        require(text.length <= 128_000)
        val document = Json.parseToJsonElement(text).jsonObject
        val id = document["id"]!!.jsonPrimitive.content
        val original =
          seeds.get(id.removePrefix("card-"))
            ?: return@post call.respondText(
              "Preview expired; reload the card",
              status = HttpStatusCode.Gone,
            )
        validateCardDraft(JsonObject(original + ("id" to JsonPrimitive(id))), document)
        cardType = original["title"]?.jsonPrimitive?.contentOrNull
        document
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        return@post call.respondText("Invalid card draft", status = HttpStatusCode.BadRequest)
      }
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
    var created: String? = null
    val location =
      try {
        // New id on every attempt: no existing design can be overwritten, including retries.
        val draft =
          JsonObject(
            document + ("id" to JsonPrimitive("card-${UiBuilderConfig.randomToken()}")) - "template"
          )
        val id = builder.client.create(draft, resolveCatalog = true)
        created = id
        val url =
          if (editor.login == null) builder.config.editorUrl(id)
          else {
            builder.client.share(id, "github:${editor.login}", editor = true)
            // Theirs to publish from the app (`/api/my/studio`), though UI Builder records the
            // server as its owner.
            made?.recordMade(
              editor.login,
              id,
              cardType = (claim as CardStudioDrafts.Claim.Ready).cardType,
              handoff = token,
            )
            builder.config.designUrl(id)
          }
        handoffs.remove(token)
        url
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        call.application.environment.log.warn("Card studio handoff failed", e)
        // Says which step failed, never the builder's own response, which may echo the draft.
        val reason =
          when {
            e is CatalogUnavailableException ->
              "UI Builder isn't serving the <code>${studioEscape(e.systemId)}</code> catalog " +
                "right now, so it couldn't open this draft. Nothing was created."
            created == null ->
              "UI Builder refused this draft. Nothing was created. Try again; if it keeps " +
                "failing, refresh the preview data in the app and open it again."
            else -> "UI Builder made the design but couldn't give your GitHub account access to it."
          }
        return@post call.respondStudioPage(
          heading = "UI Builder couldn't open this draft",
          body =
            notice(reason) +
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
