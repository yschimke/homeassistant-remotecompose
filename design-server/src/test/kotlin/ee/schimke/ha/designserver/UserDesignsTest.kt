package ee.schimke.ha.designserver

import ee.schimke.ha.client.design.BuiltDesign
import ee.schimke.ha.client.design.DesignPublisher
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignManifest
import ee.schimke.ha.design.DesignSurface
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A person publishes a UI Builder design as a private variant of a library design, sees it in the
 * manifest their device token reads, and shares it into everyone's.
 */
class UserDesignsTest {
  private val template = javaClass.getResource("/tile-light.rc")!!.readBytes()
  private val key =
    DesignKey(
      cardType = "tile",
      surface = DesignSurface.MobileApp,
      rcApiLevel = 8,
      shape = "domain=light",
      theme = "baked:Material3:light",
    )
  private val json = Json { ignoreUnknownKeys = true }

  private fun library(): File {
    val dir = Files.createTempDirectory("designs").toFile()
    val output =
      DesignPublisher.publish(listOf(BuiltDesign(key, 1, template)), serial = 5, nowEpochMs = 1_000)
    for ((path, bytes) in output.files) File(dir, path)
      .also { it.parentFile.mkdirs() }
      .writeBytes(bytes)
    return dir
  }

  private class Fixture(val client: HttpClient, val github: GithubAuth, val storeDir: File) {
    val cookie = "design-server-session=" + github.sign("octocat|${Long.MAX_VALUE}")
  }

  /** The server with a fake UI Builder whose design `good` exports the template, `bad` junk. */
  private fun ApplicationTestBuilder.fixture(): Fixture {
    externalServices {
      hosts("http://uib") {
        routing {
          get("/api/ui-builder/v1/designs/{id}/export.rc") {
            assertEquals("secret", call.request.queryParameters["token"])
            when (call.parameters["id"]) {
              "good" -> {
                call.response.headers.append("X-UI-Builder-Revision", "3")
                call.respondBytes(template, ContentType.Application.OctetStream)
              }
              "bad" -> call.respondBytes(byteArrayOf(1, 2, 3), ContentType.Application.OctetStream)
              else -> call.respondText("no such design", status = HttpStatusCode.NotFound)
            }
          }
        }
      }
    }
    val http = createClient { followRedirects = false }
    val config = UiBuilderConfig(apiUrl = "http://uib", browserUrl = "https://b", token = "secret")
    val github =
      GithubAuth(
        clientId = "id",
        clientSecret = "secret",
        publicUrl = "https://ha.example",
        sessionSecret = "session-secret",
        http = http,
      )
    val storeDir = Files.createTempDirectory("user-designs").toFile()
    application {
      designServer(
        LibraryHolder(library()),
        UiBuilderIntegration(config, UiBuilderClient(config, http)),
        github,
        userDesigns = UserDesignStore(storeDir, clock = { 42 }),
      )
    }
    return Fixture(createClient { followRedirects = false }, github, storeDir)
  }

  private fun publishBody(designId: String, variant: String = "garage", key: DesignKey = this.key) =
    """{"designId":"$designId","variant":"$variant","key":${
      Json.encodeToString(DesignKey.serializer(), key)
    }}"""

  private suspend fun Fixture.publish(
    designId: String,
    variant: String = "garage",
    key: DesignKey = this@UserDesignsTest.key,
  ) =
    client.post("/api/my/designs") {
      header(HttpHeaders.Cookie, cookie)
      contentType(ContentType.Application.Json)
      setBody(publishBody(designId, variant, key))
    }

  private suspend fun HttpClient.manifest(token: String? = null): DesignManifest =
    json.decodeFromString(
      DesignManifest.serializer(),
      get("$API_BASE/manifest.json") {
          token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }
        .bodyAsText(),
    )

  @Test
  fun publishingNeedsSignIn() = testApplication {
    val f = fixture()
    val response =
      f.client.post("/api/my/designs") {
        contentType(ContentType.Application.Json)
        setBody(publishBody("good"))
      }
    assertEquals(HttpStatusCode.Unauthorized, response.status)
  }

  @Test
  fun aPublishedDesignIsPrivateUntilShared() = testApplication {
    val f = fixture()
    val published = f.publish("good")
    assertEquals(HttpStatusCode.Created, published.status)
    val design = json.decodeFromString(UserDesign.serializer(), published.bodyAsText())
    assertEquals("github:octocat", design.entry.author)
    assertEquals(key.copy(variant = "garage"), design.entry.key)
    assertEquals(3, design.revision)
    assertFalse(design.shared)

    // Anyone: the library alone, exactly as published.
    val anonymous = f.client.manifest()
    assertEquals(5, anonymous.serial)
    assertEquals(1, anonymous.designs.size)

    // Its author's device: the library plus their private variant, at a higher serial.
    val token =
      json
        .parseToJsonElement(
          f.client
            .post("/api/my/device-token") { header(HttpHeaders.Cookie, f.cookie) }
            .bodyAsText()
        )
        .jsonObject["token"]!!
        .jsonPrimitive
        .content
    assertEquals("octocat", f.github.deviceLogin(token))
    val own = f.client.manifest(token)
    assertTrue(own.serial > anonymous.serial)
    assertEquals(design.entry, own.designs.single { it.author == "github:octocat" })
    assertContentEquals(
      template,
      f.client.get("$API_BASE/blobs/${design.entry.sha256}.rc").bodyAsBytes(),
    )

    // Shared: in everyone's manifest.
    val share =
      f.client.post("/api/my/designs/${design.entry.sha256}/share") {
        header(HttpHeaders.Cookie, f.cookie)
        setBody("""{"shared":true}""")
      }
    assertEquals(HttpStatusCode.OK, share.status)
    assertTrue(f.client.manifest().designs.any { it == design.entry })
  }

  @Test
  fun aDeviceTokenCannotMintAnother() = testApplication {
    val f = fixture()
    val token = f.github.deviceToken("octocat")
    val response =
      f.client.post("/api/my/device-token") { header(HttpHeaders.Authorization, "Bearer $token") }
    assertEquals(HttpStatusCode.Unauthorized, response.status)
    assertNull(f.github.deviceLogin(token + "x"))
  }

  @Test
  fun badDesignsAreRefused() = testApplication {
    val f = fixture()
    assertEquals(HttpStatusCode.UnprocessableEntity, f.publish("bad").status)
    assertTrue("can't be published" in f.publish("bad").bodyAsText())
    assertEquals(HttpStatusCode.BadGateway, f.publish("missing").status)
    assertEquals(HttpStatusCode.BadRequest, f.publish("good", variant = "default").status)
    assertEquals(HttpStatusCode.BadRequest, f.publish("good", variant = "Not OK").status)
    assertEquals(
      HttpStatusCode.UnprocessableEntity,
      f.publish("good", key = key.copy(cardType = "gauge")).status,
    )
  }

  @Test
  fun republishingReplacesAndTheStoreSurvivesARestart() = testApplication {
    val f = fixture()
    f.publish("good")
    f.publish("good")
    val reloaded = UserDesignStore(f.storeDir)
    assertEquals(1, reloaded.designs("octocat").size)
    assertEquals(2, reloaded.revision)
    assertNotNull(reloaded.blob(reloaded.designs("octocat").single().entry.sha256))
    assertTrue(reloaded.designs("someone-else").isEmpty())
  }

  @Test
  fun theMyPageListsDesignsAndMintsAToken() = testApplication {
    val f = fixture()
    assertEquals(HttpStatusCode.Found, f.client.get("/my").status)
    f.publish("good")
    val page = f.client.get("/my") { header(HttpHeaders.Cookie, f.cookie) }.bodyAsText()
    assertTrue("garage" in page)
    assertTrue("Share" in page)
    val withToken =
      f.client.post("/my/device-token") { header(HttpHeaders.Cookie, f.cookie) }.bodyAsText()
    assertTrue("device-token" in withToken)
  }

  @Test
  fun theMyPagePublishesFromAForm() = testApplication {
    val f = fixture()
    val base = DesignLibrary.idOf(key)
    val done =
      f.client.submitForm(
        "/my/designs",
        parameters {
          append("designId", "good")
          append("base", base)
          append("variant", "garage")
        },
      ) {
        header(HttpHeaders.Cookie, f.cookie)
      }
    assertEquals(HttpStatusCode.Found, done.status)
    assertEquals("/my", done.headers[HttpHeaders.Location])
    val refused =
      f.client.submitForm(
        "/my/designs",
        parameters {
          append("designId", "bad")
          append("base", base)
          append("variant", "garage")
        },
      ) {
        header(HttpHeaders.Cookie, f.cookie)
      }
    assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
    assertTrue("role=\"alert\"" in refused.bodyAsText())
  }

  @Test
  fun aDesignCanBeMadeForOneKindOfDevice() = testApplication {
    val f = fixture()
    val body =
      """{"designId":"good","variant":"boiler","key":${
        Json.encodeToString(DesignKey.serializer(), key)
      },"device":{"integration":"vaillant","model":"ecoTEC*"}}"""
    val published =
      f.client.post("/api/my/designs") {
        header(HttpHeaders.Cookie, f.cookie)
        contentType(ContentType.Application.Json)
        setBody(body)
      }
    assertEquals(HttpStatusCode.Created, published.status)
    val design = json.decodeFromString(UserDesign.serializer(), published.bodyAsText())
    assertEquals(
      ee.schimke.ha.design.DeviceMatch(integration = "vaillant", model = "ecotec*"),
      design.entry.device,
    )
    // A generic design for the same key sits beside it rather than replacing it.
    f.publish("good", variant = "boiler")
    assertEquals(2, UserDesignStore(f.storeDir).designs("octocat").size)
    // The same match in other letter case replaces it rather than adding a third.
    val recased =
      f.client.post("/api/my/designs") {
        header(HttpHeaders.Cookie, f.cookie)
        contentType(ContentType.Application.Json)
        setBody(body.replace("vaillant", "Vaillant").replace("ecoTEC*", "ECOTEC*"))
      }
    assertEquals(HttpStatusCode.Created, recased.status)
    assertEquals(2, UserDesignStore(f.storeDir).designs("octocat").size)
    val empty =
      f.client.post("/api/my/designs") {
        header(HttpHeaders.Cookie, f.cookie)
        contentType(ContentType.Application.Json)
        setBody(body.replace("""{"integration":"vaillant","model":"ecoTEC*"}""", "{}"))
      }
    assertEquals(HttpStatusCode.BadRequest, empty.status)
  }
}
