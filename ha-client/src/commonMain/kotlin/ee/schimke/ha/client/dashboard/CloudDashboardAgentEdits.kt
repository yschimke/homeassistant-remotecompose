package ee.schimke.ha.client.dashboard

import ee.schimke.ha.client.HaClient
import ee.schimke.ha.client.design.studioServerUrl
import ee.schimke.ha.client.platformHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*

/** Relays upstream drafts into HA edit sessions as the signed-in person. */
class CloudDashboardAgentEdits(
  private val home: HaClient,
  server: String,
  engine: HttpClientEngine? = null,
) : AgentEdits {
  private val base = studioServerUrl(server)
  private val http =
    if (engine == null)
      platformHttpClient { install(HttpTimeout) { requestTimeoutMillis = 30_000 } }
    else HttpClient(engine) { install(HttpTimeout) { requestTimeoutMillis = 30_000 } }
  private val delegate = home.agentEdits

  private data class Access(val owner: String, val agent: String, var revision: Long = 0)

  private val sessions = mutableMapOf<String, Access>()

  override suspend fun start(urlPath: String?, request: String): StartedAgentEdit {
    val started = delegate.start(urlPath, request)
    try {
      val baseline = requireNotNull(started.session.baseline)
      val response =
        http.post("$base/api/studio/dashboard-sessions") {
          contentType(ContentType.Application.Json)
          setBody(
            buildJsonObject {
              put("request", request)
              put("dashboard", baseline)
            }
              .toString()
          )
        }
      check(response.status.isSuccess()) { "The upstream couldn't create the agent request." }
      val access = Json.parseToJsonElement(response.bodyAsText()).jsonObject
      val grant =
        Access(
          access.getValue("ownerToken").jsonPrimitive.content,
          access.getValue("agentToken").jsonPrimitive.content,
        )
      sessions[started.session.id] = grant
      return StartedAgentEdit(started.session, prompt(grant))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Avoid leaving an orphan HA request if the upstream rejected creation.
      runCatching { delegate.discard(started.session.id) }
      throw e
    }
  }

  private fun prompt(access: Access) =
    "Connect to $base/api/studio/dashboard-mcp with Authorization: Bearer ${access.agent}. " +
      "Call get_dashboard_request and submit_dashboard_draft. If MCP isn't available, " +
      "edit the dashboard request at $base/api/studio/dashboard-session. " +
      "Use Authorization: Bearer ${access.agent}. GET the request, dashboard, revision and notes. " +
      "POST JSON to the same URL with revision, the complete dashboard, and a short summary. " +
      "Keep unrelated views and cards. A person will review and apply it in Home Assistant."

  override suspend fun open(urlPath: String?) = delegate.open(urlPath).filter { it.id in sessions }

  override suspend fun get(sessionId: String) = delegate.get(sessionId)

  override suspend fun resume(sessionId: String): StartedAgentEdit =
    StartedAgentEdit(delegate.get(sessionId), prompt(requireNotNull(sessions[sessionId])))

  override fun watch(sessionId: String) = flow {
    val access = requireNotNull(sessions[sessionId])
    while (true) {
      val remote = http.get("$base/api/studio/dashboard-session") { bearer(access.owner) }
      check(remote.status.isSuccess()) { "The upstream edit session expired or is unavailable." }
      val data = Json.parseToJsonElement(remote.bodyAsText()).jsonObject
      val revision = data.getValue("revision").jsonPrimitive.long
      if (revision > access.revision) {
        home.relayAgentDraft(
          sessionId,
          data.getValue("dashboard").jsonObject,
          data["summary"]?.jsonPrimitive?.contentOrNull.orEmpty(),
          "cloud:$revision",
        )
        access.revision = revision
      }
      emit(delegate.get(sessionId))
      delay(3000)
    }
  }

  override suspend fun note(sessionId: String, text: String): AgentEditSession {
    val access = requireNotNull(sessions[sessionId])
    val response =
      http.post("$base/api/studio/dashboard-session/note") {
        bearer(access.owner)
        contentType(ContentType.Application.Json)
        setBody(buildJsonObject { put("text", text) }.toString())
      }
    check(response.status.isSuccess()) { "The upstream didn't receive your note." }
    return delegate.note(sessionId, text)
  }

  override suspend fun discard(sessionId: String): AgentEditSession {
    sessions[sessionId]?.let { access ->
      val response = http.delete("$base/api/studio/dashboard-session") { bearer(access.owner) }
      check(response.status.isSuccess() || response.status == HttpStatusCode.NotFound) {
        "The upstream couldn't revoke agent access; try again."
      }
    }
    val result = delegate.discard(sessionId)
    sessions.remove(sessionId)
    return result
  }

  override suspend fun apply(sessionId: String, draftId: String) =
    delegate.apply(sessionId, draftId)

  override suspend fun undo(sessionId: String) = delegate.undo(sessionId)

  fun close() = http.close()

  private fun HttpRequestBuilder.bearer(token: String) {
    header(HttpHeaders.Authorization, "Bearer $token")
  }
}
