package ee.schimke.ha.client.design

import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.*

/** An app session's authoring client. It never receives a Home Assistant access token. */
class CardStudioClient(serverUrl: String, engine: HttpClientEngine? = null) {
  private val base = studioServerUrl(serverUrl)
  private val http =
    if (engine == null) HttpClient { configure() } else HttpClient(engine) { configure() }

  suspend fun seed(card: CardConfig, snapshot: HaSnapshot): JsonObject =
    request("/api/studio/seed", cardPreviewData(card, snapshot))

  /** A short-lived capability link; permanent creation still requires browser sign-in. */
  suspend fun handoff(document: JsonObject): String {
    val response = request("/api/studio/handoffs", document)
    val path = response["path"]?.jsonPrimitive?.content ?: error("Missing editor link")
    require(Regex("/studio/[a-zA-Z0-9_-]{32}").matches(path)) { "Invalid editor link" }
    return base + path
  }

  private suspend fun request(path: String, body: JsonObject): JsonObject {
    val response =
      http.post(base + path) {
        contentType(ContentType.Application.Json)
        setBody(body.toString())
      }
    check(response.status.isSuccess()) {
      "Design server request failed (${response.status.value})."
    }
    return Json.parseToJsonElement(response.bodyAsText()).jsonObject
  }

  fun close() = http.close()
}

private fun io.ktor.client.HttpClientConfig<*>.configure() {
  followRedirects = false
  install(HttpTimeout) { requestTimeoutMillis = 30_000 }
}

/** Settings also accept the existing library URL, which ends in /designs/v1. */
fun studioServerUrl(value: String): String {
  val base = value.trim().trimEnd('/').removeSuffix("/designs/v1")
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
