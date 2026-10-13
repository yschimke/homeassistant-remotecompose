package ee.schimke.ha.designserver

import ee.schimke.ha.client.design.validateCardDraft
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/**
 * Bounded, expiring app-to-browser handoffs. Nothing is persisted or added to the public library.
 */
class CardStudioDrafts(private val clock: () -> Long = System::currentTimeMillis) {
  private data class Entry(val document: JsonObject, val expiresAt: Long)

  private val entries = LinkedHashMap<String, Entry>()

  @Synchronized
  fun put(document: JsonObject): String {
    expire()
    check(entries.size < 128) { "Too many pending card previews. Try again later." }
    val token = UiBuilderConfig.randomToken()
    entries[token] = Entry(document, clock() + 10 * 60_000)
    return token
  }

  @Synchronized
  fun get(token: String): JsonObject? {
    expire()
    return entries[token]?.document
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
  seeds: CardStudioDrafts = CardStudioDrafts(),
  handoffs: CardStudioDrafts = CardStudioDrafts(),
) {
  post("/api/studio/seed") {
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
    if (integration == null)
      return@post call.respondText(
        "UI Builder is not configured",
        status = HttpStatusCode.ServiceUnavailable,
      )
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
        document
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        return@post call.respondText("Invalid card draft", status = HttpStatusCode.BadRequest)
      }
    val token =
      try {
        handoffs.put(document)
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
        ?: return@get call.respondText(
          "This card preview has expired. Return to the app to open it again.",
          status = HttpStatusCode.Gone,
        )
    val editor = call.editor(integration, github)
    call.response.header(HttpHeaders.CacheControl, "no-store")
    call.response.header("Referrer-Policy", "no-referrer")
    call.response.header("X-Robots-Tag", "noindex, nofollow")
    call.response.header(
      "Content-Security-Policy",
      "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'self'",
    )
    val action =
      when {
        editor.authorized ->
          "<form method=\"post\"><button>Open this draft in UI Builder</button></form>"
        editor.signInPath != null ->
          "<a href=\"${studioEscape(editor.signInPath)}\">Sign in to design this card</a>"
        else -> "<p>Open the design server's edit link in this browser first, then return here.</p>"
      }
    call.respondText(
      """
      <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
      <title>Card design preview</title><style>body{font:16px system-ui;max-width:900px;margin:24px auto;padding:16px}pre{white-space:pre-wrap;overflow-wrap:anywhere}button,a{font:inherit}</style>
      <h1>Card design preview</h1><p>This draft uses the selected card's data. Review it in UI Builder before saving or exporting. The preview link expires after ten minutes.</p>
      $action<details><summary>Draft document</summary><pre>${studioEscape(document.toString())}</pre></details></html>
    """
        .trimIndent(),
      ContentType.Text.Html,
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
    val document =
      handoffs.get(token)
        ?: return@post call.respondText("Preview expired", status = HttpStatusCode.Gone)
    val location =
      try {
        // New id on every attempt: no existing design can be overwritten, including retries.
        val draft =
          JsonObject(
            document + ("id" to JsonPrimitive("card-${UiBuilderConfig.randomToken()}")) - "template"
          )
        val id = builder.client.create(draft)
        if (editor.login == null) builder.config.editorUrl(id)
        else {
          builder.client.share(id, "github:${editor.login}", editor = true)
          builder.config.designUrl(id)
        }
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        return@post call.respondText(
          "UI Builder could not open this draft. Check the catalog and try again.",
          status = HttpStatusCode.BadGateway,
        )
      }
    handoffs.remove(token)
    call.response.header(HttpHeaders.CacheControl, "no-store")
    call.respondRedirect(location)
  }
}

private fun studioEscape(text: String): String =
  text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")
