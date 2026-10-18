package ee.schimke.ha.designserver

import io.ktor.http.*
import io.ktor.server.http.content.staticFiles
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File
import java.net.URI
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/**
 * Browser host for whole dashboards. HA OAuth, entity subscriptions and writes stay in the browser.
 */
fun Route.dashboardStudioRoutes(
  integration: UiBuilderIntegration?,
  github: GithubAuth?,
  editorAssets: File? =
    System.getenv("UI_BUILDER_WEB_DIR")?.takeIf { it.isNotBlank() }?.let(::File),
) {
  if (editorAssets?.isDirectory == true)
    staticFiles("/studio/dashboard/assets", editorAssets, index = null)
  get("/studio/dashboard") {
    call.response.header(HttpHeaders.CacheControl, "no-store")
    call.response.header("Referrer-Policy", "no-referrer")
    call.response.header("Content-Security-Policy", "frame-ancestors 'self'")
    call.respondText(dashboardResource("index.html"), ContentType.Text.Html)
  }
  get("/studio/dashboard/{name}") {
    val name = call.parameters["name"]
    if (name !in setOf("app.mjs", "model.mjs", "templates.json", "fixture.json", "editor.html"))
      return@get call.respondText("Not found", status = HttpStatusCode.NotFound)
    call.response.header(HttpHeaders.CacheControl, "no-store")
    call.response.header("Referrer-Policy", "no-referrer")
    call.response.header("Content-Security-Policy", "frame-ancestors 'self'")
    val type =
      when (name) {
        "editor.html" -> ContentType.Text.Html
        "app.mjs",
        "model.mjs" -> ContentType.Text.JavaScript
        else -> ContentType.Application.Json
      }
    if (name == "editor.html" && editorAssets?.isDirectory != true)
      return@get call.respondText(
        "The dashboard editor is not installed on this host",
        status = HttpStatusCode.ServiceUnavailable,
      )
    call.respondText(dashboardResource(name!!), type)
  }
  get("/api/studio/dashboard/catalog") {
    val builder =
      integration
        ?: return@get call.respondText(
          "Dashboard editor is not configured",
          status = HttpStatusCode.ServiceUnavailable,
        )
    call.response.header(HttpHeaders.CacheControl, "no-store")
    try {
      call.respondText(builder.client.dashboardCatalog().toString(), ContentType.Application.Json)
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      call.respondText(
        "The Lovelace catalog is not available on this editor host",
        status = HttpStatusCode.ServiceUnavailable,
      )
    }
  }
  get("/api/studio/dashboard/access") {
    val editor = call.editor(integration, github)
    call.response.header(HttpHeaders.CacheControl, "no-store")
    val access =
      if (editor.authorized || github != null || integration == null)
        buildJsonObject {
          put("authorized", editor.authorized)
          editor.signInPath?.let {
            put("signInPath", github?.signInPath("/studio/dashboard") ?: it)
          }
        }
      else integration.client.dashboardPreviewAccess(call.request.header(HttpHeaders.Cookie))
    call.respondText(access.toString(), ContentType.Application.Json)
  }
  post("/api/studio/dashboard/preview") {
    val builder =
      integration
        ?: return@post call.respondText(
          "Preview unavailable",
          status = HttpStatusCode.ServiceUnavailable,
        )
    val localEditor = call.editor(builder, github).authorized
    val cookie = call.request.header(HttpHeaders.Cookie)
    val sharedEditor =
      !localEditor &&
        github == null &&
        cookie != null &&
        builder.client.dashboardPreviewAccess(cookie)["authorized"] == JsonPrimitive(true)
    if (!localEditor && !sharedEditor)
      return@post call.respondText(
        "Sign in to render card previews",
        status = HttpStatusCode.Forbidden,
      )
    val origin = call.request.header(HttpHeaders.Origin)
    if (
      origin != null &&
        runCatching { URI(origin).rawAuthority != call.request.header(HttpHeaders.Host) }
          .getOrDefault(true)
    )
      return@post call.respondText(
        "Same-origin preview required",
        status = HttpStatusCode.Forbidden,
      )
    call.response.header(HttpHeaders.CacheControl, "no-store")
    try {
      val text = call.receiveText()
      require(text.length <= 256_000)
      val body = Json.parseToJsonElement(text).jsonObject
      val card = body.getValue("card").jsonObject
      require(card["type"] is JsonPrimitive)
      val snapshot = body["snapshot"] as? JsonObject ?: JsonObject(emptyMap())
      val style = (body["style"] as? JsonPrimitive)?.content ?: "classic"
      require(
        style in
          setOf(
            "classic",
            "expressive",
            "trinitron",
            "lcd",
            "braun",
            "nixie",
            "console",
            "ikea",
            "mushroom",
            "bubble",
            "minimalist",
            "cupertino",
            "catppuccin",
            "obsidian",
            "noctis",
            "lcars",
            "arctic",
            "aeon",
          )
      )
      val dark = (body["dark"] as? JsonPrimitive)?.booleanOrNull ?: false
      val bytes =
        builder.client.renderDashboardCard(
          card,
          snapshot,
          style,
          dark,
          browserCookie = cookie,
          operator = localEditor,
        )
      require(bytes.size <= 6 * 1024 * 1024)
      call.respondText(
        buildJsonObject {
          putJsonObject("properties") {
            putJsonObject("documentBase64") {
              put("type", "string")
              put("value", Base64.getEncoder().encodeToString(bytes))
            }
          }
        }
          .toString(),
        ContentType.Application.Json,
      )
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      call.respondText(
        "Card preview is unavailable. The card configuration is preserved.",
        status = HttpStatusCode.UnprocessableEntity,
      )
    }
  }
}

private fun dashboardResource(name: String): String =
  checkNotNull(LibraryHolder::class.java.classLoader.getResourceAsStream("dashboard/$name")) {
      "Missing dashboard resource $name"
    }
    .bufferedReader()
    .use { it.readText() }
