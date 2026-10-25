package ee.schimke.ha.client

import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HistoryPoint
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlin.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Reads chosen entities' states from Home Assistant's REST API (`GET /api/states/<entity_id>`), one
 * request each. For a client that needs a handful of entities and can't afford every state a large
 * home has (`get_states`): a watch reading its cards' values, and one entity's recent history
 * ([history]) for its details screen.
 */
class HaEntityStates(
  private val baseUrl: String,
  private val accessToken: String,
  engine: HttpClientEngine? = null,
) : AutoCloseable {
  private val httpClient = if (engine != null) HttpClient(engine) else platformHttpClient()
  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  /**
   * The states of [entityIds], by entity id. An entity Home Assistant doesn't have is left out, and
   * so is an id that isn't an entity id. Throws [HaAuthInvalidException] when Home Assistant
   * refuses the token, and an [IllegalStateException] for any other failed request.
   */
  suspend fun fetch(entityIds: Collection<String>): Map<String, EntityState> = coroutineScope {
    val permits = Semaphore(MAX_PARALLEL)
    entityIds
      .distinct()
      .filter { ENTITY_ID.matches(it) }
      .map { id -> async { permits.withPermit { id to fetchOne(id) } } }
      .awaitAll()
      .mapNotNull { (id, state) -> state?.let { id to it } }
      .toMap()
  }

  private suspend fun fetchOne(entityId: String): EntityState? {
    val response =
      httpClient.get("${baseUrl.removeSuffix("/")}/api/states/$entityId") {
        bearerAuth(accessToken)
      }
    return when {
      response.status == HttpStatusCode.NotFound -> null
      response.status == HttpStatusCode.Unauthorized ->
        throw HaAuthInvalidException("Home Assistant refused the token")
      !response.status.isSuccess() ->
        error("Home Assistant answered ${response.status.value} for $entityId")
      else ->
        json.decodeFromJsonElement(
          EntityState.serializer(),
          camelizeState(json.parseToJsonElement(response.bodyAsText()).jsonObject),
        )
    }
  }

  /**
   * [entityId]'s state changes from [start] to [end] (now, when null), oldest first, from the
   * recorder (`GET /api/history/period/<start>`). Asked for with `minimal_response` and
   * `no_attributes`, so each point is just a state and when it changed, and with every change kept
   * (`significant_changes_only=0`), so an on/off timeline keeps every flip. Empty for an id that
   * isn't an entity id or that the recorder has nothing for. Throws as [fetch] does.
   */
  suspend fun history(entityId: String, start: Instant, end: Instant? = null): List<HistoryPoint> {
    if (!ENTITY_ID.matches(entityId)) return emptyList()
    val response =
      httpClient.get("${baseUrl.removeSuffix("/")}/api/history/period/$start") {
        bearerAuth(accessToken)
        parameter("filter_entity_id", entityId)
        end?.let { parameter("end_time", it.toString()) }
        parameter("minimal_response", "")
        parameter("no_attributes", "")
        parameter("significant_changes_only", "0")
      }
    return when {
      response.status == HttpStatusCode.Unauthorized ->
        throw HaAuthInvalidException("Home Assistant refused the token")
      !response.status.isSuccess() ->
        error("Home Assistant answered ${response.status.value} for $entityId's history")
      else -> parseHistory(json.parseToJsonElement(response.bodyAsText()), entityId)
    }
  }

  override fun close() = httpClient.close()

  /**
   * One entity's points from a history response: a list of lists, one per entity. With
   * `minimal_response` only the first point of a list names its entity and carries `last_updated`;
   * the rest have `state` and `last_changed`. A point missing either is dropped rather than
   * guessed.
   */
  private fun parseHistory(
    element: kotlinx.serialization.json.JsonElement,
    entityId: String,
  ): List<HistoryPoint> {
    val lists = (element as? JsonArray).orEmpty().mapNotNull { it as? JsonArray }
    val points =
      lists.firstOrNull { list ->
        ((list.firstOrNull() as? JsonObject)?.get("entity_id") as? JsonPrimitive)?.contentOrNull ==
          entityId
      } ?: lists.singleOrNull() ?: return emptyList()
    return points.mapNotNull { point ->
      val obj = point as? JsonObject ?: return@mapNotNull null
      val state = (obj["state"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
      val at =
        ((obj["last_changed"] ?: obj["last_updated"]) as? JsonPrimitive)?.contentOrNull
          ?: return@mapNotNull null
      runCatching { HistoryPoint(ts = Instant.parse(at), state = state) }.getOrNull()
    }
  }

  private companion object {
    /** `domain.object_id`, as Home Assistant validates it: nothing else goes into the path. */
    val ENTITY_ID = Regex("[a-z0-9_]+\\.[a-z0-9_]+")
    const val MAX_PARALLEL = 4
  }
}
