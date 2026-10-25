package ee.schimke.terrazzo.core.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Signs in to a Home Assistant with a username and password, through the login flow the HA frontend
 * itself uses (`POST /auth/login_flow`, then the flow's step, then `/auth/token`), with no browser.
 * The tokens are issued to [clientId] (the server's own URL, which HA accepts as a client with a
 * same-host redirect), so they refresh only with that `client_id`.
 */
object HaPasswordLogin {
  private val json = Json { ignoreUnknownKeys = true }

  /** The `client_id` a sign-in to [baseUrl] signs in as: the server's own frontend. */
  fun clientId(baseUrl: String): String = "${baseUrl.trim().removeSuffix("/")}/"

  /**
   * The token response's body for [username] and [password] at [baseUrl]. Throws
   * [InvalidCredentialsException] when HA refuses them, and an [IllegalStateException] when the
   * server refuses the flow itself.
   */
  suspend fun login(
    client: HttpClient,
    baseUrl: String,
    username: String,
    password: String,
  ): String {
    val base = baseUrl.trim().removeSuffix("/")
    val clientId = clientId(base)
    val start =
      client.post("$base/auth/login_flow") {
        contentType(ContentType.Application.Json)
        setBody(
          buildJsonObject {
            put("client_id", clientId)
            putJsonArray("handler") {
              add("homeassistant")
              add(JsonNull)
            }
            put("redirect_uri", "$base/?auth_callback=1")
          }
            .toString()
        )
      }
    check(start.status.isSuccess()) { "Home Assistant refused to sign in: ${start.status}" }
    val flowId =
      json.parseToJsonElement(start.bodyAsText()).jsonObject.text("flow_id")
        ?: error("Home Assistant started no sign-in")
    val step =
      client.post("$base/auth/login_flow/$flowId") {
        contentType(ContentType.Application.Json)
        setBody(
          buildJsonObject {
            put("client_id", clientId)
            put("username", username)
            put("password", password)
          }
            .toString()
        )
      }
    check(step.status.isSuccess()) { "Home Assistant refused to sign in: ${step.status}" }
    val result = json.parseToJsonElement(step.bodyAsText()).jsonObject
    if (result.text("type") != "create_entry") {
      throw InvalidCredentialsException("Home Assistant didn't accept that username and password")
    }
    val code = result.text("result") ?: error("Home Assistant returned no sign-in code")
    val tokens =
      client.submitForm(
        url = "$base/auth/token",
        formParameters =
          Parameters.build {
            append("grant_type", "authorization_code")
            append("code", code)
            append("client_id", clientId)
          },
      )
    check(tokens.status.isSuccess()) { "Home Assistant's token exchange failed: ${tokens.status}" }
    return tokens.bodyAsText()
  }

  private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** Home Assistant refused a username and password ([HaPasswordLogin]). */
class InvalidCredentialsException(message: String) : Exception(message)
