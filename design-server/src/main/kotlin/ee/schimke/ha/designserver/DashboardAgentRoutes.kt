package ee.schimke.ha.designserver

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

fun Route.dashboardAgentRoutes(sessions: DashboardAgentSessions = DashboardAgentSessions()) {
  for (path in
    listOf(
      "/api/studio/dashboard-sessions",
      "/api/studio/dashboard-session",
      "/api/studio/dashboard-session/note",
    )) {
    options(path) {
      call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
      call.response.header(HttpHeaders.AccessControlAllowMethods, "GET, POST, DELETE, OPTIONS")
      call.response.header(HttpHeaders.AccessControlAllowHeaders, "Authorization, Content-Type")
      call.respondText("", status = HttpStatusCode.NoContent)
    }
  }
  post("/api/studio/dashboard-sessions") {
    call.cloudHeaders()
    val input = call.dashboardRequest() ?: return@post
    val dashboard = input["dashboard"] as? JsonObject
    val request = (input["request"] as? JsonPrimitive)?.contentOrNull
    if (dashboard == null || request == null)
      return@post call.respondText("Invalid request", status = HttpStatusCode.BadRequest)
    val access =
      try {
        sessions.create(request, dashboard)
      } catch (_: IllegalArgumentException) {
        return@post call.respondText(
          "Invalid dashboard request",
          status = HttpStatusCode.BadRequest,
        )
      } catch (_: IllegalStateException) {
        return@post call.respondText(
          "Session capacity reached",
          status = HttpStatusCode.TooManyRequests,
        )
      }
    call.respondText(
      buildJsonObject {
        put("ownerToken", access.owner)
        put("agentToken", access.agent)
        put("expiresInSeconds", 3600)
      }
        .toString(),
      ContentType.Application.Json,
    )
  }
  get("/api/studio/dashboard-session") {
    call.cloudHeaders()
    val session =
      sessions.read(call.cloudToken())
        ?: return@get call.respondText("Session unavailable", status = HttpStatusCode.NotFound)
    call.respondText(
      buildJsonObject {
        put("request", session.request)
        put("dashboard", session.dashboard)
        put("revision", session.revision)
        put("summary", session.summary)
        putJsonArray("notes") { session.notes.forEach { add(it) } }
      }
        .toString(),
      ContentType.Application.Json,
    )
  }
  post("/api/studio/dashboard-session/note") {
    call.cloudHeaders()
    val input = call.dashboardRequest() ?: return@post
    val text = (input["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    try {
      if (!sessions.note(call.cloudToken(), text))
        return@post call.respondText("Session unavailable", status = HttpStatusCode.NotFound)
    } catch (_: IllegalArgumentException) {
      return@post call.respondText("Invalid note", status = HttpStatusCode.BadRequest)
    }
    call.respondText("Note added")
  }
  post("/api/studio/dashboard-session") {
    call.cloudHeaders()
    val input = call.dashboardRequest() ?: return@post
    val dashboard = input["dashboard"] as? JsonObject
    val revision = (input["revision"] as? JsonPrimitive)?.longOrNull
    val summary = (input["summary"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    if (dashboard == null || revision == null)
      return@post call.respondText("Invalid draft", status = HttpStatusCode.BadRequest)
    try {
      if (!sessions.submit(call.cloudToken(), revision, dashboard, summary))
        return@post call.respondText("Session unavailable", status = HttpStatusCode.NotFound)
    } catch (_: IllegalArgumentException) {
      return@post call.respondText("Invalid or stale draft", status = HttpStatusCode.Conflict)
    }
    call.respondText("Draft submitted for review")
  }
  delete("/api/studio/dashboard-session") {
    call.cloudHeaders()
    if (!sessions.revoke(call.cloudToken()))
      return@delete call.respondText("Session unavailable", status = HttpStatusCode.NotFound)
    call.respondText("Session revoked")
  }
  post("/api/studio/dashboard-mcp") {
    call.response.header(HttpHeaders.CacheControl, "no-store")
    if (call.request.headers[HttpHeaders.Origin] != null)
      return@post call.respondText(
        "Browser origins are not supported",
        status = HttpStatusCode.Forbidden,
      )
    val token = call.cloudToken()
    if (sessions.read(token, agentOnly = true) == null)
      return@post call.respondText("Session unavailable", status = HttpStatusCode.Unauthorized)
    val message = call.dashboardRequest() ?: return@post
    val result = DashboardAgentMcp.handle(message, token, sessions)
    if (result == null) call.respondText("", status = HttpStatusCode.Accepted)
    else call.respondText(result.toString(), ContentType.Application.Json)
  }
}

private fun ApplicationCall.cloudHeaders() {
  response.header(HttpHeaders.AccessControlAllowOrigin, "*")
  response.header(HttpHeaders.CacheControl, "no-store")
}

private fun ApplicationCall.cloudToken(): String =
  request.headers[HttpHeaders.Authorization]
    ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
    ?.substring(7)
    ?.trim()
    .orEmpty()

private suspend fun ApplicationCall.dashboardRequest(): JsonObject? =
  try {
    val text = receiveText()
    require(text.length <= 520 * 1024)
    Json.parseToJsonElement(text).jsonObject
  } catch (e: Exception) {
    if (e is CancellationException) throw e
    respondText("Invalid request", status = HttpStatusCode.BadRequest)
    null
  }
