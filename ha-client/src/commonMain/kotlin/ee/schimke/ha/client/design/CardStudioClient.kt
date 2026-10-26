package ee.schimke.ha.client.design

import ee.schimke.ha.client.platformHttpClient
import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.design.DesignEntry
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.*

/** An app session's authoring client. It never receives a Home Assistant access token. */
class CardStudioClient(serverUrl: String, engine: HttpClientEngine? = null) {
  private val base = studioServerUrl(serverUrl)
  private val http =
    if (engine == null) platformHttpClient { configure() } else HttpClient(engine) { configure() }

  suspend fun seed(card: CardConfig, snapshot: HaSnapshot): JsonObject =
    request("/api/studio/seed", cardPreviewData(card, snapshot))

  /** A short-lived capability link; permanent creation still requires browser sign-in. */
  suspend fun handoff(document: JsonObject): String {
    val response = request("/api/studio/handoffs", document)
    val path = response["path"]?.jsonPrimitive?.content ?: error("Missing editor link")
    require(Regex("/studio/[a-zA-Z0-9_-]{32}").matches(path)) { "Invalid editor link" }
    return base + path
  }

  /** Recover an expired preview contract without losing the person's design edits. */
  suspend fun openDraft(
    document: JsonObject,
    card: CardConfig,
    snapshot: HaSnapshot,
  ): CardStudioHandoff {
    try {
      return CardStudioHandoff(document, handoff(document))
    } catch (e: CardStudioException) {
      if (e.status != HttpStatusCode.Gone.value) throw e
    }
    val refreshed = refreshCardDraft(document, seed(card, snapshot))
    return CardStudioHandoff(refreshed, handoff(refreshed))
  }

  /**
   * The UI Builder designs the design server opened for the person [token] (the design server's
   * device token) belongs to, newest first: what they can publish. Those seeded for another card
   * type are left out when [cardType] is given.
   */
  suspend fun studioDesigns(token: String, cardType: String? = null): List<StudioDesign> {
    val response = http.get(base + "/api/my/studio") { bearer(token) }
    if (!response.status.isSuccess()) throw CardPublishException(response.status.value)
    return Json.parseToJsonElement(response.bodyAsText()).jsonArray.mapNotNull { element ->
      val item = element.jsonObject
      StudioDesign(
          designId = item["designId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
          cardType = item["cardType"]?.jsonPrimitive?.contentOrNull,
          handoff = item["handoff"]?.jsonPrimitive?.contentOrNull,
        )
        .takeIf { cardType == null || it.cardType == null || it.cardType == cardType }
    }
  }

  /**
   * Publish UI Builder design [designId] privately for [key]'s card, in each theme of its card
   * style, under [variant] (one of its own by default, so another card's design for the same key
   * stays): only the person [token] belongs to sees it. Returns its stable design id
   * (`user:<designId>`), the skin a card is switched to.
   */
  suspend fun publishPrivately(
    token: String,
    designId: String,
    key: DesignKey,
    variant: String = privateVariant(designId),
  ): String {
    val response =
      http.post(base + "/api/my/designs") {
        bearer(token)
        contentType(ContentType.Application.Json)
        setBody(
          buildJsonObject {
            put("designId", designId)
            put("key", Json.encodeToJsonElement(DesignKey.serializer(), key))
            put("variant", variant)
          }
            .toString()
        )
      }
    if (!response.status.isSuccess()) {
      // A refusal for the design itself names what in it the app can't play: the design
      // server's own words about its structure, never preview values.
      val reason =
        response.bodyAsText().take(300).takeIf {
          response.status == HttpStatusCode.UnprocessableEntity
        }
      throw CardPublishException(response.status.value, reason)
    }
    val entry = Json.parseToJsonElement(response.bodyAsText()).jsonObject["entry"]?.jsonObject
    return entry?.get("designId")?.jsonPrimitive?.contentOrNull
      ?: DesignEntry.userDesignId(designId)
  }

  private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String) {
    header(HttpHeaders.Authorization, "Bearer ${token.trim()}")
  }

  private suspend fun request(path: String, body: JsonObject): JsonObject {
    val response =
      http.post(base + path) {
        contentType(ContentType.Application.Json)
        setBody(body.toString())
      }
    if (!response.status.isSuccess()) throw CardStudioException(response.status.value)
    return Json.parseToJsonElement(response.bodyAsText()).jsonObject
  }

  fun close() = http.close()
}

data class CardStudioHandoff(val document: JsonObject, val url: String)

/**
 * A UI Builder design the design server opened for this person, for [cardType]'s card, from card
 * studio handoff [handoff] (the token at the end of [CardStudioClient.handoff]'s link).
 */
data class StudioDesign(val designId: String, val cardType: String?, val handoff: String? = null)

/**
 * Which of [designs] (newest first) to publish for a card whose studio opened [opened] handoffs:
 * the newest opened from this card, else the only one there is. Null when it isn't clear, rather
 * than another card's design.
 */
fun designForCard(designs: List<StudioDesign>, opened: Collection<String>): StudioDesign? =
  designs.firstOrNull { it.handoff != null && it.handoff in opened } ?: designs.singleOrNull()

/** The handoff token in a link [CardStudioClient.handoff] returned. */
fun handoffToken(url: String): String = url.substringAfterLast('/')

/**
 * The variant a card studio design publishes under: its own (`mine-` and a hash of its UI Builder
 * id), so designs for two cards of the same key sit side by side, and republishing one replaces
 * only it.
 */
fun privateVariant(designId: String): String =
  "mine-" + Sha256.hex(designId.encodeToByteArray()).take(12)

/** Why a private publish failed, in words for the person; never echoes preview data. */
class CardPublishException(val status: Int, reason: String? = null) :
  IllegalStateException(
    when (status) {
      401 ->
        "The design server didn't accept this device's token. Create a new one on its /my page."
      403 -> "Only a design you made or edit in UI Builder can be published."
      404 -> "This design server can't publish designs. Update it or choose another server."
      422 -> reason ?: "That design can't be used for this card."
      503 -> "UI Builder is not configured on this design server."
      else -> "Publishing failed ($status). Check the design server and try again."
    }
  )

/** Safe, actionable errors; server response bodies may contain private preview text. */
class CardStudioException(val status: Int) :
  IllegalStateException(
    when (status) {
      404 -> "This design server does not support Card Studio. Update it or choose another server."
      410 -> "This preview expired. Refresh preview data and try again."
      429 -> "Too many pending previews. Wait a little and try again."
      503 -> "UI Builder is not configured on this design server."
      400 -> "The card draft was rejected. Reset the draft and try again."
      else -> "Design server request failed ($status). Check server access and try again."
    }
  )

/** Replace preview values and the expiring id while preserving all design/layout edits. */
fun refreshCardDraft(document: JsonObject, freshSeed: JsonObject): JsonObject {
  val oldState = document["stateVariables"]!!.jsonObject
  val freshState = freshSeed["stateVariables"]!!.jsonObject
  require(
    oldState.keys == freshState.keys &&
      oldState.all { (name, value) ->
        value.jsonObject - "initialValue" == freshState[name]!!.jsonObject - "initialValue"
      }
  ) {
    "The card's bindings changed. Reset the draft to use its new contract."
  }
  require(
    document["schema"] == freshSeed["schema"] && document["catalogPin"] == freshSeed["catalogPin"]
  ) {
    "The card's catalog changed. Reset the draft to use the new catalog."
  }
  val refreshed =
    JsonObject(document + mapOf("id" to freshSeed["id"]!!, "stateVariables" to freshState))
  validateCardDraft(freshSeed, refreshed)
  return refreshed
}

private fun io.ktor.client.HttpClientConfig<*>.configure() {
  followRedirects = false
  install(HttpTimeout) { requestTimeoutMillis = 30_000 }
}

/** Settings also accept the existing library URL, which ends in /designs/v1. */
fun studioServerUrl(value: String): String {
  val base = value.trim().trimEnd('/').removeSuffix("/designs/v1")
  require(!base.any(Char::isWhitespace)) { "The design server URL must not contain whitespace." }
  val url = Url(base)
  require(
    url.protocol == URLProtocol.HTTPS &&
      url.host.isNotBlank() &&
      url.user == null &&
      url.password == null &&
      url.fragment.isEmpty() &&
      url.encodedQuery.isEmpty()
  ) {
    "Use an HTTPS design server URL without credentials or query parameters."
  }
  return base
}

/**
 * Preview only the selected card. Entity ids become positional slots, and attributes are explicitly
 * limited to presentation values. Entity-picture URLs, signed image attributes and unrelated
 * entities stay local.
 */
fun cardPreviewData(card: CardConfig, snapshot: HaSnapshot): JsonObject {
  val slots = CardDesignSlots.slotMap(card)
  fun clean(value: JsonElement): JsonElement =
    when (value) {
      is JsonObject ->
        JsonObject(
          value
            .filterKeys { it in CARD_FIELDS }
            .mapValues { (key, v) ->
              if (key == "entity" && v is JsonPrimitive)
                JsonPrimitive(slots[v.content] ?: v.content)
              else if (key == "entities" && v is JsonArray)
                JsonArray(
                  v.map { row ->
                    if (row is JsonPrimitive) JsonPrimitive(slots[row.content] ?: row.content)
                    else clean(row)
                  }
                )
              else clean(v)
            }
        )
      is JsonArray -> JsonArray(value.map(::clean))
      else -> value
    }
  return buildJsonObject {
    put("card", clean(card.raw))
    putJsonObject("states") {
      slots.forEach { (id, slot) ->
        snapshot.states[id]?.let { state ->
          putJsonObject(slot) {
            put("state", state.state)
            put("attributes", JsonObject(state.attributes.filterKeys { it in PREVIEW_ATTRIBUTES }))
          }
        }
      }
    }
  }
}

private val PREVIEW_ATTRIBUTES = setOf("friendly_name", "unit_of_measurement", "device_class")
private val CARD_FIELDS =
  setOf(
    "type",
    "entity",
    "entities",
    "cards",
    "elements",
    "name",
    "title",
    "heading",
    "content",
    "min",
    "max",
    "unit",
    "icon",
    "show_header_toggle",
    "columns",
    "rows",
  )
