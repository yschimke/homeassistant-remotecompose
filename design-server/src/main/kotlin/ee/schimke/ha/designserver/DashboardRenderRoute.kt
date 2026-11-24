package ee.schimke.ha.designserver

import ee.schimke.livery.sessions.server.SessionToolResult
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * `POST /api/studio/render-dashboard`: a dashboard config's cards drawn with made-up sample states,
 * the same drawing an agent session's `render_dashboard` returns, for a caller with no session.
 *
 * Home Assistant calls it for an edit session's draft when no app has drawn the draft yet
 * (`get_draft_render`), so an agent working on its own still sees what it made. Only the config
 * comes in (as `/api/studio/dashboard-sessions` already takes one); the states are invented here,
 * never the home's, and nothing is kept.
 *
 * Body: `{"dashboard": {…}, "view": 0, "dark": false}` (`view` and `dark` optional). Answers the
 * renderer's result (`cards`, `undrawable`, `sampleStates`, …) with each image's PNG inlined as
 * base64 under `images[].png`, or `422` with the reason when no card could be drawn.
 */
fun Route.dashboardRenderRoutes(guide: AgentGuide) {
  options(DASHBOARD_RENDER_PATH) {
    call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
    call.response.header(HttpHeaders.AccessControlAllowMethods, "POST, OPTIONS")
    call.response.header(HttpHeaders.AccessControlAllowHeaders, "Content-Type")
    call.respondText("", status = HttpStatusCode.NoContent)
  }
  post(DASHBOARD_RENDER_PATH) {
    call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
    call.response.header(HttpHeaders.CacheControl, "no-store")
    val input = call.renderRequest() ?: return@post
    val dashboard =
      input["dashboard"] as? JsonObject
        ?: return@post call.respondText(
          "dashboard must be a dashboard config object",
          status = HttpStatusCode.BadRequest,
        )
    when (val result = guide.renderDashboard(dashboard, input)) {
      is SessionToolResult.Done ->
        call.respondText(rendered(result).toString(), ContentType.Application.Json)
      is SessionToolResult.Failed ->
        call.respondText(result.reason, status = HttpStatusCode.UnprocessableEntity)
      else -> call.respondText("unexpected result", status = HttpStatusCode.InternalServerError)
    }
  }
}

const val DASHBOARD_RENDER_PATH = "/api/studio/render-dashboard"

/** The renderer's own result, its images inlined: a caller here has no MCP content blocks. */
internal fun rendered(result: SessionToolResult.Done): JsonObject = buildJsonObject {
  result.data.forEach { (key, value) -> if (key != "images") put(key, value) }
  putJsonArray("images") {
    result.images.forEachIndexed { i, image ->
      add(
        buildJsonObject {
          put("index", i)
          put("caption", image.caption)
          put("mimeType", image.mimeType)
          put("png", Base64.getEncoder().encodeToString(image.bytes))
        },
      )
    }
  }
}

private suspend fun ApplicationCall.renderRequest(): JsonObject? =
  try {
    val text = receiveText()
    // The same cap as a dashboard session's: a dashboard, not a library.
    require(text.length <= 520 * 1024)
    Json.parseToJsonElement(text).jsonObject
  } catch (e: Exception) {
    if (e is CancellationException) throw e
    respondText("Invalid request", status = HttpStatusCode.BadRequest)
    null
  }
