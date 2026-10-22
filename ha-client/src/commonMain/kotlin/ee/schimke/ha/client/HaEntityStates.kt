package ee.schimke.ha.client

import ee.schimke.ha.model.EntityState
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Reads chosen entities' states from Home Assistant's REST API (`GET /api/states/<entity_id>`), one
 * request each. For a client that needs a handful of entities and can't afford every state a large
 * home has (`get_states`): a watch reading its cards' values.
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

  override fun close() = httpClient.close()

  private companion object {
    /** `domain.object_id`, as Home Assistant validates it: nothing else goes into the path. */
    val ENTITY_ID = Regex("[a-z0-9_]+\\.[a-z0-9_]+")
    const val MAX_PARALLEL = 4
  }
}
