package ee.schimke.ha.designserver

import ee.schimke.ha.designserver.AgentRelayMcp.json
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/**
 * The relay around card and dashboard agent sessions ([AgentRelay]).
 *
 * The person's app (Android, desktop, Wasm and Wasmo in any origin) uses the owner capability as a
 * bearer: it long-polls `GET /api/agent/session?since=<version>` for drafts, presence and the
 * agent's messages, and posts comments and decisions. Agents use `POST /api/agent/mcp`, configured
 * once with no header, or `POST /api/agent/join` to trade a code for their capability.
 */
fun Route.agentRelayRoutes(relay: AgentRelay) {
  for (path in OWNER_PATHS) {
    options(path) {
      call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
      call.response.header(HttpHeaders.AccessControlAllowMethods, "GET, POST, DELETE, OPTIONS")
      call.response.header(HttpHeaders.AccessControlAllowHeaders, "Authorization, Content-Type")
      call.response.header(HttpHeaders.AccessControlMaxAge, "600")
      call.respondText("", status = HttpStatusCode.NoContent)
    }
  }

  get("/api/agent/session") {
    call.relayHeaders()
    val since = call.request.queryParameters["since"]?.toLongOrNull()
    val wait = call.request.queryParameters["wait"]?.toLongOrNull()?.times(1000) ?: relay.maxWaitMs
    val state =
      relay.ownerState(call.bearerToken(), since, wait)
        ?: return@get call.respondText("Session unavailable", status = HttpStatusCode.NotFound)
    call.respondText(state.json().toString(), ContentType.Application.Json)
  }
  post("/api/agent/session/join-code") {
    call.relayHeaders()
    val code =
      relay.newJoinCode(call.bearerToken())
        ?: return@post call.respondText("Session unavailable", status = HttpStatusCode.NotFound)
    call.respondText(
      buildJsonObject { put("joinCode", code) }.toString(),
      ContentType.Application.Json,
    )
  }
  post("/api/agent/session/comment") {
    call.relayHeaders()
    val input = call.relayBody() ?: return@post
    val text = (input["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val target = (input["target"] as? JsonPrimitive)?.contentOrNull
    try {
      if (!relay.comment(call.bearerToken(), text, target))
        return@post call.respondText("Session unavailable", status = HttpStatusCode.NotFound)
    } catch (_: IllegalArgumentException) {
      return@post call.respondText("Invalid comment", status = HttpStatusCode.BadRequest)
    }
    call.respondText("Comment sent")
  }
  post("/api/agent/session/decision") {
    call.relayHeaders()
    val input = call.relayBody() ?: return@post
    val decision = (input["decision"] as? JsonPrimitive)?.contentOrNull
    if (decision != "accepted" && decision != "rejected")
      return@post call.respondText("Invalid decision", status = HttpStatusCode.BadRequest)
    try {
      if (
        !relay.decide(
          call.bearerToken(),
          decision == "accepted",
          (input["text"] as? JsonPrimitive)?.contentOrNull,
        )
      )
        return@post call.respondText("Session unavailable", status = HttpStatusCode.NotFound)
    } catch (_: IllegalArgumentException) {
      return@post call.respondText("Invalid decision", status = HttpStatusCode.BadRequest)
    }
    call.respondText("Decision sent")
  }
  post("/api/agent/claim") {
    call.relayHeaders()
    val input = call.relayBody() ?: return@post
    val code = (input["code"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    when (relay.claim(call.bearerToken(), code)) {
      AgentRelay.ClaimResult.Claimed -> call.respondText("Agent connected")
      AgentRelay.ClaimResult.Unknown ->
        call.respondText("Unknown or expired code", status = HttpStatusCode.NotFound)
      AgentRelay.ClaimResult.Throttled ->
        call.respondText(
          "Too many wrong codes; wait a minute",
          status = HttpStatusCode.TooManyRequests,
        )
      AgentRelay.ClaimResult.Gone ->
        call.respondText("Session unavailable", status = HttpStatusCode.Gone)
    }
  }
  delete("/api/agent/session") {
    call.relayHeaders()
    if (!relay.revoke(call.bearerToken()))
      return@delete call.respondText("Session unavailable", status = HttpStatusCode.NotFound)
    call.respondText("Session revoked")
  }

  // ---- Agents: never from a browser page (MCP's DNS-rebinding rule) ----

  post("/api/agent/mcp") {
    call.response.header(HttpHeaders.CacheControl, "no-store")
    if (call.request.headers[HttpHeaders.Origin] != null)
      return@post call.respondText(
        "Browser origins are not supported",
        status = HttpStatusCode.Forbidden,
      )
    val message = call.relayBody(limit = 1_500_000) ?: return@post
    val response = AgentRelayMcp.handle(message, call.bearerToken(), relay)
    if (response == null) call.respondText("", status = HttpStatusCode.Accepted)
    else call.respondText(response.toString(), ContentType.Application.Json)
  }
  post("/api/agent/join") {
    call.response.header(HttpHeaders.CacheControl, "no-store")
    if (call.request.headers[HttpHeaders.Origin] != null)
      return@post call.respondText(
        "Browser origins are not supported",
        status = HttpStatusCode.Forbidden,
      )
    val input = call.relayBody() ?: return@post
    val joined =
      relay.join(
        (input["code"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        (input["agentName"] as? JsonPrimitive)?.contentOrNull,
      ) ?: return@post call.respondText("Unknown or expired code", status = HttpStatusCode.NotFound)
    call.respondText(
      buildJsonObject {
        put("session", joined.session)
        put("kind", joined.kind.wire)
        put("title", joined.title)
        put("mcp", "/api/agent/mcp")
        put(
          "http",
          if (joined.kind == AgentRelay.Kind.Card) "/api/studio/agent-session"
          else "/api/studio/dashboard-session",
        )
        put(
          "next",
          "Prefer the MCP endpoint with session as an argument. Over plain HTTP, GET and POST the " +
            "http path with Authorization: Bearer <session>.",
        )
      }
        .toString(),
      ContentType.Application.Json,
    )
  }
}

private val OWNER_PATHS =
  listOf(
    "/api/agent/session",
    "/api/agent/session/join-code",
    "/api/agent/session/comment",
    "/api/agent/session/decision",
    "/api/agent/claim",
  )

fun AgentRelay.OwnerState.json() = buildJsonObject {
  put("version", version)
  put("kind", kind.wire)
  put("title", title)
  put("revision", revision)
  put("expiresInSeconds", expiresInSeconds)
  joinCode?.let {
    put("joinCode", it)
    put("joinCodeExpiresInSeconds", joinCodeExpiresInSeconds)
  }
  putJsonObject("agent") {
    put("joined", agent.joined)
    put("connected", agent.connected)
    put("name", agent.name)
    put("status", agent.status)
    agent.lastSeenMs?.let { put("lastSeenSecondsAgo", it / 1000) }
  }
  put("messages", messages.json())
  decision?.let { put("decision", it) }
}

private fun ApplicationCall.relayHeaders() {
  response.header(HttpHeaders.AccessControlAllowOrigin, "*")
  response.header(HttpHeaders.CacheControl, "no-store")
}

internal fun ApplicationCall.bearerToken(): String =
  request.headers[HttpHeaders.Authorization]
    ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
    ?.substring(7)
    ?.trim()
    .orEmpty()

private suspend fun ApplicationCall.relayBody(limit: Int = 16_000): JsonObject? =
  try {
    val text = receiveText()
    require(text.length <= limit)
    Json.parseToJsonElement(text).jsonObject
  } catch (e: Exception) {
    if (e is CancellationException) throw e
    respondText("Invalid request", status = HttpStatusCode.BadRequest)
    null
  }
