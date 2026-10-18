package ee.schimke.ha.designserver

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.request.receiveText
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.testApplication
import java.util.Base64
import kotlin.test.*
import kotlinx.serialization.json.*

class DashboardStudioTest {
  @Test
  fun fixtureHostAndResourcesNeedNoHomeAssistantConnection() = testApplication {
    application { routing { dashboardStudioRoutes(null, null) } }
    val page = client.get("/studio/dashboard")
    assertEquals(HttpStatusCode.OK, page.status)
    assertEquals(
      HttpStatusCode.ServiceUnavailable,
      client.get("/studio/dashboard/editor.html").status,
    )
    assertEquals("frame-ancestors 'self'", page.headers["Content-Security-Policy"])
    val fixture =
      Json.parseToJsonElement(client.get("/studio/dashboard/fixture.json").bodyAsText()).jsonObject
    assertEquals(3, fixture["views"]!!.jsonArray.size)
    assertNotNull(fixture["x-fixture"])
    assertEquals(HttpStatusCode.NotFound, client.get("/studio/dashboard/secrets.json").status)
    assertEquals(
      HttpStatusCode.ServiceUnavailable,
      client.get("/api/studio/dashboard/catalog").status,
    )
    assertEquals(
      HttpStatusCode.ServiceUnavailable,
      client.post("/api/studio/dashboard/preview").status,
    )
  }

  /** The requests API takes the operator token as `?token=` and refuses an Authorization header. */
  @Test
  fun catalogIsTheServedLovelaceCatalog() = testApplication {
    externalServices {
      hosts("https://builder.test") {
        routing {
          post("/api/ui-builder/v1/requests") {
            if (
              call.request.queryParameters["token"] != "operator" ||
                call.request.headers[HttpHeaders.Authorization] != null
            ) {
              call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
              return@post
            }
            call.respondText(
              """{"response":{"type":"catalogs","catalogs":[""" +
                """{"benchmark":{"catalogSystemId":"ha-cards"}},""" +
                """{"benchmark":{"catalogSystemId":"lovelace"},"statusSemantics":{"platform":"lovelace"}}]}}"""
            )
          }
        }
      }
    }
    val config = UiBuilderConfig("https://builder.test", "https://builder.test", "operator")
    val integration =
      UiBuilderIntegration(
        config,
        UiBuilderClient(config, createClient { followRedirects = false }),
      )
    application { routing { dashboardStudioRoutes(integration, null) } }
    val response = client.get("/api/studio/dashboard/catalog")
    assertEquals(HttpStatusCode.OK, response.status)
    val catalog = Json.parseToJsonElement(response.bodyAsText()).jsonObject
    assertEquals(
      JsonPrimitive("lovelace"),
      catalog.getValue("statusSemantics").jsonObject["platform"],
    )
  }

  @Test
  fun cardPreviewRequiresEditorAuthorizationAndDelegatesToActualHarcRenderPort() = testApplication {
    val bytes = checkNotNull(javaClass.getResourceAsStream("/tile-light.rc")).use { it.readBytes() }
    var renders = 0
    var expectedStyle = "braun"
    externalServices {
      hosts("https://builder.test") {
        routing {
          post(
            "/homeassistant-remotecompose/render/ee.schimke.ha.previews.LovelaceCardPreviewKt.LovelaceCardPreview.rc"
          ) {
            assertEquals("operator", call.request.headers["X-Compose-Preview-Token"])
            val body = Json.parseToJsonElement(call.receiveText()).jsonObject
            val card =
              Json.parseToJsonElement(body.getValue("knob.cardJson").jsonPrimitive.content)
                .jsonObject
            assertEquals(JsonPrimitive("tile"), card["type"])
            assertEquals(JsonPrimitive(expectedStyle), body["knob.cardStyle"])
            assertEquals(JsonPrimitive("true"), body["knob.dark"])
            renders++
            call.respondBytes(bytes, ContentType.Application.OctetStream)
          }
        }
      }
    }
    val http = createClient { followRedirects = false }
    val integration =
      UiBuilderIntegration(
        UiBuilderConfig("https://builder.test", "https://builder.test", "operator"),
        UiBuilderClient(
          UiBuilderConfig("https://builder.test", "https://builder.test", "operator"),
          http,
        ),
      )
    application { routing { dashboardStudioRoutes(integration, null) } }
    val body =
      """{"card":{"type":"tile","entity":"light.sofa"},"snapshot":{"states":{}},"style":"braun","dark":true}"""
    assertEquals(
      HttpStatusCode.Forbidden,
      client.post("/api/studio/dashboard/preview") { setBody(body) }.status,
    )
    assertEquals(
      HttpStatusCode.Forbidden,
      client
        .post("/api/studio/dashboard/preview") {
          header(HttpHeaders.Cookie, "design-server-edit=operator")
          header(HttpHeaders.Origin, "https://elsewhere.test")
          setBody(body)
        }
        .status,
    )
    assertEquals(0, renders)
    val result =
      client.post("/api/studio/dashboard/preview") {
        header(HttpHeaders.Cookie, "design-server-edit=operator")
        setBody(body)
      }
    assertEquals(HttpStatusCode.OK, result.status)
    assertEquals("no-store", result.headers[HttpHeaders.CacheControl])
    val encoded =
      Json.parseToJsonElement(result.bodyAsText())
        .jsonObject["properties"]!!
        .jsonObject["documentBase64"]!!
        .jsonObject["value"]!!
        .jsonPrimitive
        .content
    assertContentEquals(bytes, Base64.getDecoder().decode(encoded))
    assertEquals(1, renders)
    val page = client.get("/studio/dashboard").bodyAsText()
    val communityStyles =
      listOf(
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
    for (style in communityStyles) {
      assertTrue(page.contains("<option>$style</option>"), "$style is discoverable")
      expectedStyle = style
      assertEquals(
        HttpStatusCode.OK,
        client
          .post("/api/studio/dashboard/preview") {
            header(HttpHeaders.Cookie, "design-server-edit=operator")
            setBody(body.replace("\"braun\"", "\"$style\""))
          }
          .status,
        "$style reaches the render port",
      )
    }
    assertEquals(11, renders)
    expectedStyle = "ikea"
    val ikea =
      client.post("/api/studio/dashboard/preview") {
        header(HttpHeaders.Cookie, "design-server-edit=operator")
        setBody(body.replace("braun", "ikea"))
      }
    assertEquals(HttpStatusCode.OK, ikea.status)
    assertEquals(12, renders)
    expectedStyle = "home-assistant"
    assertTrue(page.contains("<option>home-assistant</option>"))
    assertEquals(
      HttpStatusCode.OK,
      client
        .post("/api/studio/dashboard/preview") {
          header(HttpHeaders.Cookie, "design-server-edit=operator")
          setBody(body.replace("braun", "home-assistant"))
        }
        .status,
    )
    assertEquals(13, renders)
    assertEquals(
      HttpStatusCode.UnprocessableEntity,
      client
        .post("/api/studio/dashboard/preview") {
          header(HttpHeaders.Cookie, "design-server-edit=operator")
          setBody("{invalid}")
        }
        .status,
    )
  }

  @Test
  fun sharedHostIdentityIsVerifiedAndBrowserAuthorityIsNotReplacedByOperator() = testApplication {
    val bytes = checkNotNull(javaClass.getResourceAsStream("/tile-light.rc")).use { it.readBytes() }
    var renders = 0
    externalServices {
      hosts("https://shared.test") {
        routing {
          get("/api/ui-builder/v1/identity") {
            assertNull(call.request.headers[HttpHeaders.Authorization])
            assertNull(call.request.headers["X-Compose-Preview-Token"])
            if (call.request.headers[HttpHeaders.Cookie] == "shared-session=valid")
              call.respondText(
                """{"signedIn":true,"canWrite":true}""",
                ContentType.Application.Json,
              )
            else
              call.respondText(
                """{"signInUrl":"/auth/github/start?return=%2Fstudio%2Fdashboard"}""",
                ContentType.Application.Json,
                HttpStatusCode.Unauthorized,
              )
          }
          post(
            "/homeassistant-remotecompose/render/ee.schimke.ha.previews.LovelaceCardPreviewKt.LovelaceCardPreview.rc"
          ) {
            assertEquals("shared-session=valid", call.request.headers[HttpHeaders.Cookie])
            assertNull(call.request.headers[HttpHeaders.Authorization])
            assertNull(call.request.headers["X-Compose-Preview-Token"])
            renders++
            call.respondBytes(bytes)
          }
        }
      }
    }
    val config = UiBuilderConfig("https://shared.test", "https://shared.test", "operator-secret")
    val integration =
      UiBuilderIntegration(
        config,
        UiBuilderClient(config, createClient { followRedirects = false }),
      )
    application { routing { dashboardStudioRoutes(integration, null) } }
    val access =
      Json.parseToJsonElement(client.get("/api/studio/dashboard/access").bodyAsText()).jsonObject
    assertEquals(JsonPrimitive(false), access["authorized"])
    assertTrue(access.getValue("signInPath").jsonPrimitive.content.startsWith("/auth/github/start"))
    val body = """{"card":{"type":"tile","entity":"light.sofa"}}"""
    assertEquals(
      HttpStatusCode.Forbidden,
      client
        .post("/api/studio/dashboard/preview") {
          header(HttpHeaders.Cookie, "shared-session=forged")
          setBody(body)
        }
        .status,
    )
    assertEquals(0, renders)
    assertEquals(
      HttpStatusCode.OK,
      client
        .post("/api/studio/dashboard/preview") {
          header(HttpHeaders.Cookie, "shared-session=valid")
          setBody(body)
        }
        .status,
    )
    assertEquals(1, renders)
  }

  @Test
  fun packagedEditorAssetsAreServedOnTheHostOriginWithoutARunningBuilderProcess() =
    testApplication {
      val assets = kotlin.io.path.createTempDirectory("dashboard-assets").toFile()
      try {
        java.io.File(assets, "uiBuilder.mjs").writeText("export const packagedEditor = true;")
        java.io.File(assets, "uiBuilder.wasm").writeBytes(byteArrayOf(0, 97, 115, 109, 1, 0, 0, 0))
        application { routing { dashboardStudioRoutes(null, null, assets) } }
        val editor = client.get("/studio/dashboard/editor.html")
        assertEquals(HttpStatusCode.OK, editor.status)
        assertTrue(editor.bodyAsText().contains("/studio/dashboard/assets/uiBuilder.mjs"))
        assertEquals(
          "export const packagedEditor = true;",
          client.get("/studio/dashboard/assets/uiBuilder.mjs").bodyAsText(),
        )
        val wasm = client.get("/studio/dashboard/assets/uiBuilder.wasm")
        assertEquals(HttpStatusCode.OK, wasm.status)
        assertTrue(wasm.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/wasm"))
      } finally {
        assets.deleteRecursively()
      }
    }
}
