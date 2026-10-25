package ee.schimke.terrazzo.core.auth

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A password sign-in walks Home Assistant's own login flow as its frontend does, and gets tokens
 * issued to the server's own URL: what the public demo's gateway allows.
 */
class HaPasswordLoginTest {
  private val clientIds = mutableListOf<String>()

  /** A Home Assistant whose only account is demo / demo, as the frontend's login flow sees it. */
  private fun ApplicationTestBuilder.homeAssistant() = routing {
    post("/auth/login_flow") {
      val body = Json.parseToJsonElement(call.receiveText()).jsonObject
      clientIds += body.getValue("client_id").jsonPrimitive.content
      call.respondText(
        """{"type":"form","flow_id":"f1","step_id":"init"}""",
        ContentType.Application.Json,
      )
    }
    post("/auth/login_flow/f1") {
      val body = Json.parseToJsonElement(call.receiveText()).jsonObject
      clientIds += body.getValue("client_id").jsonPrimitive.content
      val ok =
        body.getValue("username").jsonPrimitive.content == "demo" &&
          body.getValue("password").jsonPrimitive.content == "demo"
      call.respondText(
        if (ok) """{"type":"create_entry","result":"code-1"}"""
        else """{"type":"form","flow_id":"f1","errors":{"base":"invalid_auth"}}""",
        ContentType.Application.Json,
      )
    }
    post("/auth/token") {
      val form = call.receiveParameters()
      clientIds += form["client_id"].orEmpty()
      if (form["code"] != "code-1")
        return@post call.respondText("", status = HttpStatusCode.BadRequest)
      call.respondText(
        """{"access_token":"a","refresh_token":"r","expires_in":1800,"token_type":"Bearer"}""",
        ContentType.Application.Json,
      )
    }
  }

  @Test
  fun theGuestAccountSignsInAsTheServersOwnFrontend() = testApplication {
    homeAssistant()
    val body = HaPasswordLogin.login(createClient {}, "http://localhost/", "demo", "demo")
    val tokens = Json.parseToJsonElement(body).jsonObject
    assertEquals("r", tokens.getValue("refresh_token").jsonPrimitive.content)
    // Every step names the server itself as the client, so the tokens refresh as it.
    assertEquals(List(3) { "http://localhost/" }, clientIds)
    assertEquals("http://localhost/", HaPasswordLogin.clientId("http://localhost"))
  }

  @Test
  fun aWrongPasswordIsInvalidCredentials() = testApplication {
    homeAssistant()
    val error =
      assertFailsWith<InvalidCredentialsException> {
        HaPasswordLogin.login(createClient {}, "http://localhost", "demo", "nope")
      }
    assertTrue("username and password" in error.message.orEmpty())
  }
}
