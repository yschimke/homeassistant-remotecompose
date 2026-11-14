package ee.schimke.terrazzo.containers

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName

/**
 * A throwaway Home Assistant with the checked-in minimal config (`ha-config/configuration.yaml`).
 * [onboard] creates its owner, so the app can sign in with a username and password.
 *
 * The image is pinned to the release the demo server runs (`deploy/demo-ha/compose.yaml`), so
 * Renovate moves the two together.
 */
class HomeAssistantContainer : GenericContainer<HomeAssistantContainer>(IMAGE) {

  init {
    withExposedPorts(PORT)
    withCopyToContainer(
      Transferable.of(resource("ha-config/configuration.yaml")),
      "/config/configuration.yaml",
    )
    // Onboarding answers before any user exists, so this is up as soon as the frontend is.
    waitingFor(
      Wait.forHttp("/api/onboarding").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(4)),
    )
  }

  /** The base URL from this machine, the one Testcontainers maps. */
  val hostUrl: String
    get() = "http://$host:${getMappedPort(PORT)}"

  /** The base URL as a device sees this machine at [hostAddress] (`10.0.2.2` from an emulator). */
  fun deviceUrl(hostAddress: String): String = "http://$hostAddress:${getMappedPort(PORT)}"

  /**
   * Runs Home Assistant's first-run onboarding over its REST API, as
   * `integration/scripts/onboard.sh` does, creating the owner [username] / [password].
   */
  fun onboard(username: String, password: String) {
    val clientId = "$hostUrl/"
    val created =
      postJson(
        "/api/onboarding/users",
        buildJsonObject {
          put("client_id", clientId)
          put("name", "Restore Test")
          put("username", username)
          put("password", password)
          put("language", "en")
        },
      )
    val authCode = created.string("auth_code")
    val tokens =
      postForm(
        "/auth/token",
        mapOf(
          "client_id" to clientId,
          "code" to authCode,
          "grant_type" to "authorization_code",
        ),
      )
    val accessToken = tokens.string("access_token")
    postJson("/api/onboarding/core_config", JsonObject(emptyMap()), accessToken)
    postJson("/api/onboarding/analytics", JsonObject(emptyMap()), accessToken)
  }

  private fun postJson(path: String, body: JsonObject, bearer: String? = null): JsonObject {
    val request =
      HttpRequest.newBuilder(URI.create("$hostUrl$path"))
        .header("Content-Type", "application/json")
        .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
        .build()
    return send(request)
  }

  private fun postForm(path: String, form: Map<String, String>): JsonObject {
    val encoded =
      form.entries.joinToString("&") { (key, value) ->
        "${URLEncoder.encode(key, Charsets.UTF_8)}=${URLEncoder.encode(value, Charsets.UTF_8)}"
      }
    val request =
      HttpRequest.newBuilder(URI.create("$hostUrl$path"))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(encoded))
        .build()
    return send(request)
  }

  private fun send(request: HttpRequest): JsonObject {
    val response = http.send(request, HttpResponse.BodyHandlers.ofString())
    check(response.statusCode() in 200..299) {
      "${request.method()} ${request.uri()} answered ${response.statusCode()}: ${response.body()}"
    }
    val body = response.body().ifBlank { "{}" }
    return Json.parseToJsonElement(body).let {
      if (it is JsonObject) it else JsonObject(emptyMap())
    }
  }

  private fun JsonObject.string(key: String): String =
    checkNotNull(get(key)?.jsonPrimitive?.content) { "No $key in $this" }

  private companion object {
    const val PORT = 8123
    val IMAGE: DockerImageName =
      DockerImageName.parse("ghcr.io/home-assistant/home-assistant:2026.11.1")
    val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun resource(name: String): ByteArray =
      checkNotNull(HomeAssistantContainer::class.java.classLoader.getResourceAsStream(name)) {
          "Missing test resource $name"
        }
        .use { it.readBytes() }
  }
}
