package ee.schimke.terrazzo.shared

import ee.schimke.ha.model.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

/**
 * The public renderer consumes only a dashboard and a snapshot, never an authenticated HA client.
 */
data class DemoData(val dashboard: Dashboard, val snapshot: HaSnapshot)

enum class DemoDataStatus {
  CONNECTING,
  LIVE,
  UNAVAILABLE,
}

data class DemoFrame(val data: DemoData?, val status: DemoDataStatus)

/** Read-only boundary shared by the demo host and backend adapter. No action/write API. */
fun interface DemoDataSource {
  suspend fun read(): DemoData
}

/** Only publish HA-owned data; clear unavailable snapshots and retry without substituting data. */
fun demoFrames(source: DemoDataSource): Flow<DemoFrame> = flow {
  emit(DemoFrame(null, DemoDataStatus.CONNECTING))
  while (true) {
    val frame =
      try {
        DemoFrame(withTimeout(4_000) { source.read() }, DemoDataStatus.LIVE)
      } catch (e: CancellationException) {
        // A request timeout is recoverable; cancellation of the host is not.
        if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
        DemoFrame(null, DemoDataStatus.UNAVAILABLE)
      } catch (_: Throwable) {
        // Browser fetch failures are JsException (Throwable, not Exception).
        DemoFrame(null, DemoDataStatus.UNAVAILABLE)
      }
    emit(frame)
    delay(if (frame.status == DemoDataStatus.LIVE) 5_000 else 15_000)
  }
}

/** Fixed public gateway only. No OAuth, stored credentials or private HA URL. */
class PublicDemoDataSource(baseUrl: String) : DemoDataSource {
  private val base = baseUrl.trimEnd('/')
  private val http = HttpClient()
  private val json = Json { ignoreUnknownKeys = true }

  override suspend fun read(): DemoData {
    suspend fun readJson(path: String): JsonElement {
      val response = http.get("$base/$path")
      check(response.status.value == 200) { "Public demo unavailable" }
      return json.parseToJsonElement(response.bodyAsText())
    }
    val dashboard = json.decodeFromJsonElement<Dashboard>(readJson("dashboard"))
    return DemoData(dashboard, decodeDemoStates(readJson("api/states").jsonArray))
  }

  suspend fun toggle(entityId: String) {
    require(entityId in setOf("light.demo_lamp", "switch.demo_coffee"))
    val domain = entityId.substringBefore('.')
    val response =
      http.post("$base/api/services/$domain/toggle") {
        contentType(ContentType.Application.Json)
        setBody(buildJsonObject { put("entity_id", entityId) }.toString())
      }
    check(response.status.value == 200) { "Demo control unavailable" }
  }

  fun close() = http.close()
}

internal fun decodeDemoStates(states: JsonArray): HaSnapshot {
  val json = Json { ignoreUnknownKeys = true }
  return HaSnapshot(
    states.associate { value ->
      val state = value.jsonObject
      val normalized = buildJsonObject {
        state.forEach { (key, value) ->
          put(
            when (key) {
              "entity_id" -> "entityId"
              "last_changed" -> "lastChanged"
              "last_updated" -> "lastUpdated"
              else -> key
            },
            value,
          )
        }
      }
      val entity = json.decodeFromJsonElement<EntityState>(normalized)
      entity.entityId to entity
    }
  )
}
