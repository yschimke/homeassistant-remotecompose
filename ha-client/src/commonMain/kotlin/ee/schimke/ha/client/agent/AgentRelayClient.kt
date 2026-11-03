package ee.schimke.ha.client.agent

import ee.schimke.ha.client.design.studioServerUrl
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
  }

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
    fun invite(server: String, code: String, what: String): String =
      "Help me with $what in Terrazzo. Join with code $code using the Terrazzo MCP server at " +
        "${mcpUrl(server)} (no sign-in: call join_session with the code, then follow its " +
        "instructions; I'll watch and comment in the app)."
  }
}
