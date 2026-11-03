package ee.schimke.ha.client.agent

import ee.schimke.ha.client.design.studioServerUrl
import ee.schimke.ha.client.networkErrorsAsExceptions
import ee.schimke.ha.client.platformHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The live side of a card or dashboard agent session, as the person's app sees it. */
@Serializable
data class AgentRelayState(
  val version: Long,
  val kind: String,
  val title: String = "",
  /** The draft revision: 0 until the agent submits, then one more per draft. */
  val revision: Long = 0,
  val expiresInSeconds: Long = 0,
  /** The code to give an agent, while one is unused and unexpired. */
  val joinCode: String? = null,
  val joinCodeExpiresInSeconds: Long = 0,
  val agent: AgentPresence = AgentPresence(),
  val messages: List<AgentRelayMessage> = emptyList(),
  /** `accepted` or `rejected`, once the person has decided. */
  val decision: String? = null,
  /** Sent to the person's linked agent: there is no code to hand over. */
  val linked: Boolean = false,
)

/** The person's lasting connection to their agent, as Settings show it. */
@Serializable
data class AgentLinkState(
  val version: Long = 0,
  /** An agent has connected with the setup code (or the person entered the agent's code). */
  val connected: Boolean = false,
  val name: String = "Agent",
  /** The agent is waiting for requests right now. */
  val listening: Boolean = false,
  val lastSeenSecondsAgo: Long? = null,
  /** The setup code, while unused. */
  val code: String? = null,
  val codeExpiresInSeconds: Long = 0,
)

@Serializable
data class AgentPresence(
  /** The agent has joined at some point. */
  val joined: Boolean = false,
  /** The agent was active within the last minute and a half. */
  val connected: Boolean = false,
  val name: String = "Agent",
  val status: String = "",
  val lastSeenSecondsAgo: Long? = null,
)

/** A comment or decision from the person, or a message or draft summary from the agent. */
@Serializable
data class AgentRelayMessage(
  val seq: Long,
  val from: String,
  val kind: String,
  val text: String,
  val target: String? = null,
) {
  val fromAgent: Boolean
    get() = from == "agent"
}

/** The session ended: revoked, expired, or the server restarted. */
class AgentSessionEndedException : Exception("The agent session ended or expired.")

class AgentRelayException(message: String) : Exception(message)

/**
 * The person's side of the design server's agent relay, with the session's owner capability as
 * bearer. Everything is an outbound HTTPS call to the design server, so it works the same from
 * Android, desktop and the browser (Wasm, Wasmo) behind any reverse proxy; Home Assistant is never
 * reached and no Home Assistant token is sent.
 */
class AgentRelayClient(server: String, engine: HttpClientEngine? = null) {
  val base = studioServerUrl(server)

  private val http =
    if (engine == null) platformHttpClient { configure() } else HttpClient(engine) { configure() }

  private fun HttpClientConfig<*>.configure() {
    // A long-poll is held for up to 25 s; leave room for slow networks on top.
    install(HttpTimeout) { requestTimeoutMillis = 45_000 }
    networkErrorsAsExceptions()
  }

  /** Open a dashboard session for [request] on [dashboard]: its owner capability. */
  suspend fun startDashboard(
    request: String,
    dashboard: JsonObject,
    /** The person's agent link: the request goes straight to their agent, with no code. */
    agentLink: String? = null,
  ): String {
    val response =
      http.post("$base/api/studio/dashboard-sessions") {
        contentType(ContentType.Application.Json)
        setBody(
          buildJsonObject {
            put("request", request)
            put("dashboard", dashboard)
            if (agentLink != null) put("agentLink", agentLink)
          }
            .toString()
        )
      }
    response.check("The design server couldn't open the agent request")
    return json.decodeFromString<Started>(response.bodyAsText()).ownerToken
  }

  /** The agent's latest dashboard draft: its revision, config and one-line summary. */
  suspend fun dashboardDraft(owner: String): DashboardDraft {
    val response = http.get("$base/api/studio/dashboard-session") { bearer(owner) }
    if (response.status == HttpStatusCode.NotFound) throw AgentSessionEndedException()
    response.check("The design server didn't answer")
    return json.decodeFromString(response.bodyAsText())
  }

  @Serializable private data class Started(val ownerToken: String)

  // ---- The agent link: connect once, then every request goes to that agent ----

  /** Start the setup: the link's capability (kept on this device) and the code for the agent. */
  suspend fun createLink(): Pair<String, String> {
    val response = http.post("$base/api/agent/links")
    response.check("Couldn't start connecting an agent")
    val created = json.decodeFromString<CreatedLink>(response.bodyAsText())
    return created.link to created.code
  }

  /** The link now or, with [since], once it changes (the server holds the request up to 25 s). */
  suspend fun linkState(link: String, since: Long? = null): AgentLinkState {
    val response =
      http.get("$base/api/agent/link" + (since?.let { "?since=$it" } ?: "")) { bearer(link) }
    if (response.status == HttpStatusCode.NotFound) throw AgentSessionEndedException()
    response.check("The design server didn't answer")
    return json.decodeFromString(response.bodyAsText())
  }

  /** The link now and after each change, until it is disconnected; network failures retry. */
  fun watchLink(link: String): Flow<AgentLinkState> = flow {
    var since: Long? = null
    var pause = 1_000L
    while (true) {
      val state =
        try {
          linkState(link, since)
        } catch (e: CancellationException) {
          throw e
        } catch (e: AgentSessionEndedException) {
          throw e
        } catch (e: Exception) {
          delay(pause)
          pause = (pause * 2).coerceAtMost(30_000)
          continue
        }
      pause = 1_000
      emit(state)
      since = state.version
    }
  }

  suspend fun newLinkCode(link: String) {
    val response = http.post("$base/api/agent/link/code") { bearer(link) }
    if (response.status == HttpStatusCode.NotFound) throw AgentSessionEndedException()
    response.check("Couldn't make a new code")
  }

  /** Connect the agent that showed the person [code] (its `request_access`). */
  suspend fun claimLink(link: String, code: String) {
    val response =
      http.post("$base/api/agent/link/claim") {
        bearer(link)
        contentType(ContentType.Application.Json)
        setBody(buildJsonObject { put("code", code.trim()) }.toString())
      }
    when (response.status) {
      HttpStatusCode.NotFound -> throw AgentRelayException("That code is unknown or expired.")
      HttpStatusCode.TooManyRequests ->
        throw AgentRelayException("Too many wrong codes. Wait a minute and try again.")
      HttpStatusCode.Gone -> throw AgentSessionEndedException()
      else -> response.check("Couldn't connect the agent")
    }
  }

  /** Share the draft at [revision] as the person's app drew it ([png]) with the agent. */
  @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
  suspend fun putRender(owner: String, png: ByteArray, revision: Long) {
    val response =
      http.post("$base/api/agent/session/render") {
        bearer(owner)
        contentType(ContentType.Application.Json)
        setBody(
          buildJsonObject {
            put("png", kotlin.io.encoding.Base64.encode(png))
            put("revision", revision)
          }
            .toString()
        )
      }
    if (response.status == HttpStatusCode.NotFound) throw AgentSessionEndedException()
    response.check("Couldn't share the draft with the agent")
  }

  suspend fun disconnect(link: String) {
    val response = http.delete("$base/api/agent/link") { bearer(link) }
    if (response.status == HttpStatusCode.NotFound) return
    response.check("Couldn't disconnect the agent; try again")
  }

  @Serializable private data class CreatedLink(val link: String, val code: String)

  @Serializable
  data class DashboardDraft(
    val revision: Long,
    val dashboard: JsonObject,
    val summary: String = "",
  )

  /**
   * The session now or, with [since], once it changes after that version (the server holds the
   * request up to 25 s and then answers with the unchanged state).
   */
  suspend fun state(owner: String, since: Long? = null): AgentRelayState {
    val response =
      http.get("$base/api/agent/session" + (since?.let { "?since=$it" } ?: "")) { bearer(owner) }
    if (response.status == HttpStatusCode.NotFound) throw AgentSessionEndedException()
    response.check("The design server didn't answer")
    return json.decodeFromString(response.bodyAsText())
  }

  /**
   * The state now, after every change, and at least every 25 s while nothing changes (so time-only
   * changes such as an agent going quiet show up), until the session ends
   * ([AgentSessionEndedException]). Network failures retry with a growing pause, so a phone
   * switching networks or a laptop waking picks the session back up by itself.
   */
  fun watch(owner: String): Flow<AgentRelayState> = flow {
    var since: Long? = null
    var pause = 1_000L
    while (true) {
      val state =
        try {
          state(owner, since)
        } catch (e: CancellationException) {
          throw e
        } catch (e: AgentSessionEndedException) {
          throw e
        } catch (e: Exception) {
          delay(pause)
          pause = (pause * 2).coerceAtMost(30_000)
          continue
        }
      pause = 1_000
      // Emit every answer, even at an unchanged version: presence ("connected") and the code's
      // remaining time change with the clock alone, without a version bump.
      emit(state)
      since = state.version
    }
  }

  /** Replace an unused code (or issue one after the last was redeemed). */
  suspend fun newJoinCode(owner: String): String {
    val response = http.post("$base/api/agent/session/join-code") { bearer(owner) }
    if (response.status == HttpStatusCode.NotFound) throw AgentSessionEndedException()
    response.check("Couldn't make a new code")
    return json.decodeFromString<JoinCode>(response.bodyAsText()).joinCode
  }

  /** Let in the agent that showed the person [code] (its `request_access`). */
  suspend fun claim(owner: String, code: String) {
    val response =
      http.post("$base/api/agent/claim") {
        bearer(owner)
        contentType(ContentType.Application.Json)
        setBody(buildJsonObject { put("code", code.trim()) }.toString())
      }
    when (response.status) {
      HttpStatusCode.NotFound -> throw AgentRelayException("That code is unknown or expired.")
      HttpStatusCode.TooManyRequests ->
        throw AgentRelayException("Too many wrong codes. Wait a minute and try again.")
      HttpStatusCode.Gone -> throw AgentSessionEndedException()
      else -> response.check("Couldn't connect the agent")
    }
  }

  /** Say something to the agent, optionally about one card ([target]: its title or position). */
  suspend fun comment(owner: String, text: String, target: String? = null) {
    val response =
      http.post("$base/api/agent/session/comment") {
        bearer(owner)
        contentType(ContentType.Application.Json)
        setBody(
          buildJsonObject {
            put("text", text.trim())
            target?.let { put("target", it) }
          }
            .toString()
        )
      }
    if (response.status == HttpStatusCode.NotFound) throw AgentSessionEndedException()
    response.check("The agent didn't receive your message")
  }

  /** Tell the agent the person accepted (applied, published) or rejected its draft. */
  suspend fun decide(owner: String, accepted: Boolean) {
    val response =
      http.post("$base/api/agent/session/decision") {
        bearer(owner)
        contentType(ContentType.Application.Json)
        setBody(
          buildJsonObject { put("decision", if (accepted) "accepted" else "rejected") }.toString()
        )
      }
    if (response.status == HttpStatusCode.NotFound) return // Already over: nothing to tell.
    response.check("Couldn't tell the agent")
  }

  suspend fun revoke(owner: String) {
    val response = http.delete("$base/api/agent/session") { bearer(owner) }
    if (response.status == HttpStatusCode.NotFound) return
    response.check("Couldn't revoke agent access; try again")
  }

  fun close() = http.close()

  private fun HttpRequestBuilder.bearer(token: String) {
    header(HttpHeaders.Authorization, "Bearer $token")
  }

  private fun HttpResponse.check(what: String) {
    if (!status.isSuccess()) throw AgentRelayException("$what (HTTP ${status.value}).")
  }

  @Serializable private data class JoinCode(val joinCode: String)

  companion object {
    private val json = Json { ignoreUnknownKeys = true }

    /** The MCP endpoint an agent adds once (no header, no sign-in): every session uses it. */
    fun mcpUrl(server: String) = studioServerUrl(server) + "/api/agent/mcp"

    /**
     * What the person tells (or pastes to) their agent. The code is short-lived and single use; it
     * is not the capability, which only the agent that redeems it ever holds.
     */
    /** The one-time setup message: after it, the agent listens for every request. */
    fun setupInvite(server: String, code: String): String =
      "Connect to my Terrazzo (Home Assistant cards and dashboards) so I can ask you for " +
        "changes from the app. Add the MCP server ${mcpUrl(server)} (no sign-in), call connect " +
        "with code $code and keep the link it returns. Then call await_request with that link " +
        "whenever you're free: my requests arrive there, and I'll watch and comment in the app."

    fun invite(server: String, code: String, what: String): String =
      "Help me with $what in Terrazzo. Join with code $code using the Terrazzo MCP server at " +
        "${mcpUrl(server)} (no sign-in: call join_session with the code, then follow its " +
        "instructions; I'll watch and comment in the app)."
  }
}
