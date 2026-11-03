package ee.schimke.ha.client.dashboard

import ee.schimke.ha.client.HaClient
import ee.schimke.ha.client.agent.AgentGrantStore
import ee.schimke.ha.client.agent.AgentLiveControls
import ee.schimke.ha.client.agent.AgentRelayClient
import ee.schimke.ha.client.agent.AgentRelayState
import ee.schimke.ha.client.agent.AgentSessionEndedException
import ee.schimke.ha.client.design.studioServerUrl
import ee.schimke.ha.client.networkErrorsAsExceptions
import ee.schimke.ha.client.platformHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * Relays upstream drafts into HA edit sessions as the signed-in person.
 *
 * With the design server's agent relay, the person gives the agent a short code instead of a
 * capability, the screen long-polls for drafts, presence and the agent's messages (no "check for
 * draft"), and comments reach an agent waiting in `await_feedback` at once. Against an older design
 * server it falls back to a pasted prompt and polling.
 */
class CloudDashboardAgentEdits(
  private val home: HaClient,
  server: String,
  engine: HttpClientEngine? = null,
  private val grants: AgentGrantStore = AgentGrantStore.memory(),
) : AgentEdits, AgentLiveControls {
  private val base = studioServerUrl(server)
  override val relayServer: String = base
  private val http =
    if (engine == null)
      platformHttpClient {
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
        networkErrorsAsExceptions()
      }
    else
      HttpClient(engine) {
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
        networkErrorsAsExceptions()
      }
  private val relay = AgentRelayClient(base, engine)
  private val delegate = home.agentEdits

  @Serializable
  private data class Access(
    val owner: String,
    val agent: String,
    var revision: Long = 0,
    /** The server runs the relay: a code joins the agent and the screen long-polls. */
    val relay: Boolean = false,
    val server: String = "",
  )

  private val json = Json { ignoreUnknownKeys = true }

  private val sessions: MutableMap<String, Access> =
    grants
      .load()
      ?.let { runCatching { json.decodeFromString<Map<String, Access>>(it) }.getOrNull() }
      .orEmpty()
      // A grant for another design server can't be used with this one.
      .filterValues { it.server.isEmpty() || it.server == base }
      .toMutableMap()

  private val states = mutableMapOf<String, MutableStateFlow<AgentRelayState?>>()

  private fun persist() {
    grants.save(if (sessions.isEmpty()) null else json.encodeToString(sessions.toMap()))
  }

  private fun forget(sessionId: String) {
    sessions.remove(sessionId)
    persist()
  }

  override fun live(sessionId: String): StateFlow<AgentRelayState?> =
    states.getOrPut(sessionId) { MutableStateFlow(null) }

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
          relay = access["joinCode"] != null,
          server = base,
        )
      sessions[started.session.id] = grant
      persist()
      // The code shows at once, before the screen starts watching.
      if (grant.relay) refresh(started.session.id, grant)
      return StartedAgentEdit(started.session, prompt(grant))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Avoid leaving an orphan HA request if the upstream rejected creation.
      runCatching { delegate.discard(started.session.id) }
      throw e
    }
  }

  /** With the relay the screen shows a code instead; the prompt is only for older servers. */
  private fun prompt(access: Access) =
    if (access.relay) ""
    else
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
    val live = states.getOrPut(sessionId) { MutableStateFlow(null) }
    var since: Long? = null
    while (true) {
      val revision: Long
      val ended: Boolean
      if (access.relay) {
        // Held by the server until something changes (or 25 s pass): no fixed polling.
        val state =
          try {
            relay.state(access.owner, since)
          } catch (e: AgentSessionEndedException) {
            null
          }
        ended = state == null
        state?.let {
          live.value = it
          since = it.version
        }
        revision = state?.revision ?: access.revision
      } else {
        val remote = http.get("$base/api/studio/dashboard-session") { bearer(access.owner) }
        ended = remote.status == HttpStatusCode.NotFound
        if (!ended) check(remote.status.isSuccess()) { "The upstream edit session is unavailable." }
        revision =
          if (ended) access.revision
          else
            Json.parseToJsonElement(remote.bodyAsText())
              .jsonObject
              .getValue("revision")
              .jsonPrimitive
              .long
      }
      if (ended) {
        forget(sessionId)
        throw IllegalStateException("The upstream edit session expired or was revoked.")
      }
      if (revision > access.revision) relayDraft(sessionId, access)
      emit(delegate.get(sessionId))
      if (!access.relay) delay(3000)
    }
  }

  /**
   * Fetch the agent's latest draft and hand it to Home Assistant, which checks it as this person.
   */
  private suspend fun relayDraft(sessionId: String, access: Access) {
    val remote = http.get("$base/api/studio/dashboard-session") { bearer(access.owner) }
    check(remote.status.isSuccess()) { "The upstream edit session expired or is unavailable." }
    val data = Json.parseToJsonElement(remote.bodyAsText()).jsonObject
    val revision = data.getValue("revision").jsonPrimitive.long
    if (revision <= access.revision) return
    home.relayAgentDraft(
      sessionId,
      data.getValue("dashboard").jsonObject,
      data["summary"]?.jsonPrimitive?.contentOrNull.orEmpty(),
      "cloud:$revision",
    )
    access.revision = revision
    persist()
  }

  override suspend fun note(sessionId: String, text: String, target: String?): AgentEditSession {
    comment(sessionId, text, target)
    return delegate.note(sessionId, text, target)
  }

  override suspend fun comment(sessionId: String, text: String, target: String?) {
    val access = requireNotNull(sessions[sessionId])
    if (access.relay) {
      relay.comment(access.owner, text, target)
      return
    }
    val response =
      http.post("$base/api/studio/dashboard-session/note") {
        bearer(access.owner)
        contentType(ContentType.Application.Json)
        setBody(
          buildJsonObject { put("text", target?.let { "About $it: $text" } ?: text) }.toString()
        )
      }
    check(response.status.isSuccess()) { "The upstream didn't receive your note." }
  }

  override suspend fun newJoinCode(sessionId: String) {
    val access = requireNotNull(sessions[sessionId])
    relay.newJoinCode(access.owner)
    refresh(sessionId, access)
  }

  override suspend fun claim(sessionId: String, code: String) {
    val access = requireNotNull(sessions[sessionId])
    relay.claim(access.owner, code)
    refresh(sessionId, access)
  }

  private suspend fun refresh(sessionId: String, access: Access) {
    val state = runCatching { relay.state(access.owner) }.getOrNull() ?: return
    states.getOrPut(sessionId) { MutableStateFlow(null) }.value = state
  }

  override suspend fun discard(sessionId: String): AgentEditSession {
    sessions[sessionId]?.let { access ->
      if (access.relay) relay.revoke(access.owner)
      else {
        val response = http.delete("$base/api/studio/dashboard-session") { bearer(access.owner) }
        check(response.status.isSuccess() || response.status == HttpStatusCode.NotFound) {
          "The upstream couldn't revoke agent access; try again."
        }
      }
    }
    val result = delegate.discard(sessionId)
    forget(sessionId)
    states.remove(sessionId)
    return result
  }

  override suspend fun apply(sessionId: String, draftId: String): AgentEditSession {
    val applied = delegate.apply(sessionId, draftId)
    // Tell a waiting agent it's done; the apply itself already happened in Home Assistant.
    sessions[sessionId]
      ?.takeIf { it.relay }
      ?.let { runCatching { relay.decide(it.owner, accepted = true) } }
    return applied
  }

  override suspend fun undo(sessionId: String) = delegate.undo(sessionId)

  fun close() {
    http.close()
    relay.close()
  }

  private fun HttpRequestBuilder.bearer(token: String) {
    header(HttpHeaders.Authorization, "Bearer $token")
  }
}
