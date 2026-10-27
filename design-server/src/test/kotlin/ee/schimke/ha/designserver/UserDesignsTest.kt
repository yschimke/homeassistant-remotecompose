package ee.schimke.ha.designserver

import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcFloatConstant
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.client.design.BuiltDesign
import ee.schimke.ha.client.design.DesignPublisher
import ee.schimke.ha.client.design.handoffToken
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
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

  /** A Small Wear widget of [template]'s card, as Glance Wear captures it (`:wear`'s tests). */
  private val wearSmall = javaClass.getResource("/tile-light-wear-small.rc")!!.readBytes()

  /**
   * [wearSmall] with a named value added: the container frame a `hostFrame: device` export leaves
   * to the watch, or (for [name] outside it) a value no card provides.
   */
  private fun wearSmallNaming(name: String): ByteArray {
    val document = RcDocumentCodec.decode(wearSmall, RcWireLimits())
    val at = document.operations.indexOfFirst { it is RcLayoutContent } + 1
    val id = 4_000
    val named =
      listOf(
        RcNamedVariable(id, RcNamedVariable.FLOAT_TYPE, "USER:$name"),
        RcFloatConstant(id, RcFloatWord.literal(8f)),
      )
    return RcDocumentCodec.encode(
      document.copy(
        operations = document.operations.take(at) + named + document.operations.drop(at)
      )
    )
  }

  /** The designs the card studio opened in UI Builder. */
  private val opened = mutableListOf<String>()

  /** The Wear container roots UI Builder was asked to export. */
  private val exportedWear = mutableListOf<String>()

  /** The UI Builder source of design `wear`: a card surface in a section. */
  private val wearSource =
    UiBuilderSeed.envelope(
      HaCardsSeed.section(template = null),
      designId = "wear",
      title = "Tile",
      heightDp = 120,
      stateVariables = emptyList(),
      nodes =
        mapOf(
          "root" to
            UiBuilderSeed.node(
              "root",
              HaCardsSeed.DASHBOARD_SECTION,
              slots = mapOf("content" to listOf("surface")),
            ),
          "surface" to
            UiBuilderSeed.node(
              "surface",
              HaCardsSeed.CARD_SURFACE,
              slots = mapOf("content" to listOf("tile")),
            ),
          "tile" to UiBuilderSeed.node("tile", HaCardsSeed.TILE),
        ),
    )

  /** The library: [key]'s design, and the same card in each of [themes] too. */
  private fun library(themes: List<String> = emptyList(), base: ByteArray = template): File {
    val dir = Files.createTempDirectory("designs").toFile()
    val designs =
      (listOf(key) + themes.map { key.copy(theme = it) }).map { BuiltDesign(it, 1, base) }
    val output = DesignPublisher.publish(designs, serial = 5, nowEpochMs = 1_000)
    for ((path, bytes) in output.files) File(dir, path)
      .also { it.parentFile.mkdirs() }
      .writeBytes(bytes)
    return dir
  }

  private class Fixture(
    val client: HttpClient,
    val github: GithubAuth,
    val storeDir: File,
    /** The server's own store. */
    val store: UserDesignStore,
  ) {
    val cookie = "design-server-session=" + github.sign("octocat|${Long.MAX_VALUE}")
  }

  /** A picture of one [color], as UI Builder serves an uploaded asset. */
  private fun picture(color: java.awt.Color): ByteArray {
    val image = java.awt.image.BufferedImage(32, 24, java.awt.image.BufferedImage.TYPE_INT_RGB)
    image.createGraphics().apply {
      this.color = color
      fillRect(0, 0, 32, 24)
      dispose()
    }
    return java.io
      .ByteArrayOutputStream()
      .also { javax.imageio.ImageIO.write(image, "png", it) }
      .toByteArray()
  }

  /**
   * `handmade`: a card drawn from two pictures, the door's height following `light.s0.is_on`. UI
   * Builder can't export it, so the server builds it.
   */
  private val handmade =
    Json.parseToJsonElement(
        """
        {"schema": "compose-ui-builder-document/v1-candidate", "id": "handmade",
         "title": "Garage", "revision": 7,
         "catalogPin": {"systemId": "ha-cards", "catalogRevision": "candidate",
           "capabilityDigest": "candidate", "nativeRuntimeId": "candidate"},
         "environment": {"widthDp": 360, "heightDp": 200, "density": 2, "theme": "light",
           "locale": "en-GB", "fontScale": 1, "layoutDirection": "ltr"},
         "stateVariables": {"light.s0.is_on": {"type": "value", "valueType": "bool",
           "nullable": false, "initialValue": false, "persistence": "preview"}},
         "roots": ["stage"],
         "nodes": {
           "stage": {"id": "stage", "componentId": "layout/box",
             "modifiers": [{"type": "size", "widthDp": 360, "heightDp": 200}],
             "slots": {"children": ["inside", "door"]}},
           "inside": {"id": "inside", "componentId": "asset/image",
             "properties": {"assetKey": {"type": "string", "value": "inside"}},
             "modifiers": [{"type": "fillMaxSize"}]},
           "door": {"id": "door", "componentId": "asset/image",
             "properties": {"assetKey": {"type": "string", "value": "door"}},
             "modifiers": [{"type": "fillMaxWidth"}, {"type": "height", "heightDp":
               {"type": "expr", "op": "select", "args": [
                 {"type": "state", "variable": "light.s0.is_on"},
                 {"type": "float", "value": 4}, {"type": "float", "value": 200}]}}]}
         },
         "assets": {
           "inside": {"mediaType": "image/png", "contentDigest": "sha256:1",
             "source": {"type": "uploaded", "storageKey": "sha256:1"}},
           "door": {"mediaType": "image/png", "contentDigest": "sha256:2",
             "source": {"type": "uploaded", "storageKey": "sha256:2"}}
         }}
        """
      )
      .jsonObject

  /**
   * [handmade], its inside dimmed by [variable], a value its card doesn't push, declared as
   * [valueType]. The app pushes the `HA:` values every home shares to every design.
   */
  private fun dimmedBy(variable: String, valueType: String = "float"): JsonObject {
    val read =
      if (valueType == "bool")
        """{"type": "expr", "op": "select", "args": [{"type": "state", "variable": "$variable"},""" +
          """ {"type": "float", "value": 1}, {"type": "float", "value": 0}]}"""
      else """{"type": "state", "variable": "$variable"}"""
    val initial = if (valueType == "bool") "false" else "0"
    val document = handmade.toString()
    val dimmed =
      document
        .replace(
          """"stateVariables":{""",
          """"stateVariables":{"$variable":{"type":"value","valueType":"$valueType",""" +
            """"nullable":false,"initialValue":$initial,"persistence":"design"},""",
        )
        .replace(
          """"modifiers":[{"type":"fillMaxSize"}]}""",
          """"modifiers":[{"type":"fillMaxSize"},{"type":"alpha","alpha":""" +
            """{"type":"expr","op":"smoothstep","args":[{"type":"float","value":-6},""" +
            """{"type":"float","value":6},$read]}}]}""",
        )
    check(dimmed.length > document.length + 200) { "the fixture changed shape" }
    return Json.parseToJsonElement(dimmed).jsonObject
  }

  private val sunlit = dimmedBy("HA:sun.sun.elevation")
  private val peeking = dimmedBy("HA:lock.front_door.numeric_state")
  private val mistyped = dimmedBy("HA:sun.sun.elevation", valueType = "bool")

  /**
   * Who UI Builder says may edit each design: octocat owns the test designs, mallory owns
   * `mallorys` (which exports fine) and only lets octocat view `viewed`.
   */
  private fun accessOf(designId: String): String? =
    when (designId) {
      "mallorys" -> """{"ownerActorId":"github:mallory","actorGrants":[]}"""
      "viewed" ->
        """{"ownerActorId":"github:mallory","actorGrants":[{"actorId":"github:octocat","role":"VIEWER"}]}"""
      "made" -> """{"ownerActorId":"operator","actorGrants":[]}"""
      "good",
      "bad",
      "wear",
      "framed",
      "foreign",
      "handmade",
      "sunlit",
      "peeking",
      "mistyped" -> """{"ownerActorId":"github:octocat","actorGrants":[]}"""
      else -> null
    }

  /** The server with a fake UI Builder whose design `good` exports the template, `bad` junk. */
  private fun ApplicationTestBuilder.fixture(libraryDir: File = library()): Fixture {
    externalServices {
      hosts("http://uib") {
        routing {
          post("/api/ui-builder/v1/requests") {
            assertEquals("secret", call.request.queryParameters["token"])
            val request =
              Json.parseToJsonElement(call.receiveText()).jsonObject["request"]!!.jsonObject
            if (request["type"]!!.jsonPrimitive.content == "listCatalogs") {
              return@post call.respondText(
                """{"response":{"type":"catalogs","catalogs":[{"benchmark":""" +
                  """{"catalogSystemId":"ha-cards","catalogRevision":"sha256:published",""" +
                  """"nativeRuntimeId":"published-runtime"}}]}}"""
              )
            }
            assertEquals("getDesignAccess", request["type"]!!.jsonPrimitive.content)
            val id = request["designId"]!!.jsonPrimitive.content
            val access =
              accessOf(id)
                ?: return@post call.respondText(
                  """{"schemaVersion":1,"requestId":"r","response":{"type":"error",""" +
                    """"error":{"code":"notFound","message":"no such design"}}}""",
                  ContentType.Application.Json,
                )
            call.respondText(
              """{"schemaVersion":1,"requestId":"r","response":{"type":"designAccess",""" +
                """"designId":"$id","access":$access}}""",
              ContentType.Application.Json,
            )
          }
          // The card studio: a draft opened in UI Builder, and shared with its designer.
          put("/api/ui-builder/v1/designs/{id}") {
            opened += call.parameters["id"]!!
            call.respondText("created", status = HttpStatusCode.Created)
          }
          post("/ui-builder/{id}/access") { call.respondText("", status = HttpStatusCode.SeeOther) }
          for ((id, document) in
            listOf(
              "handmade" to handmade,
              "sunlit" to sunlit,
              "peeking" to peeking,
              "mistyped" to mistyped,
            )) {
            get("/api/ui-builder/v1/designs/$id") {
              call.respondText(document.toString(), ContentType.Application.Json)
            }
          }
          get("/api/ui-builder/v1/designs/{id}/assets/{key}") {
            val color =
              if (call.parameters["key"] == "door") java.awt.Color.RED else java.awt.Color.GREEN
            call.respondBytes(picture(color), ContentType.Image.PNG)
          }
          // `wear`: a card design whose Small Wear widget exports and whose Large one doesn't.
          get("/api/ui-builder/v1/designs/{id}") {
            if (call.parameters["id"] !in setOf("wear", "framed", "foreign")) {
              return@get call.respondText("no such design", status = HttpStatusCode.NotFound)
            }
            val id = call.parameters["id"]!!
            call.respondText(
              JsonObject(wearSource + ("id" to JsonPrimitive(id))).toString(),
              ContentType.Application.Json,
            )
          }
          post("/api/ui-builder/v1/documents/export.rc") {
            val document = Json.parseToJsonElement(call.receiveText()).jsonObject
            val root = document["nodes"]!!.jsonObject["root"]!!.jsonObject
            exportedWear += root["componentId"]!!.jsonPrimitive.content
            when (document["id"]!!.jsonPrimitive.content) {
              "wear-wear-small" -> call.respondBytes(wearSmall, ContentType.Application.OctetStream)
              // `framed`: its Wear widgets leave the container frame to the watch; `foreign`'s
              // read values nothing supplies.
              "framed-wear-small",
              "framed-wear-large" ->
                call.respondBytes(
                  wearSmallNaming("wear.container.horizontal_padding"),
                  ContentType.Application.OctetStream,
                )
              "foreign-wear-small" ->
                call.respondBytes(
                  wearSmallNaming("somewhere.else"),
                  ContentType.Application.OctetStream,
                )
              // A name under the frame's prefix the watch doesn't fill.
              "foreign-wear-large" ->
                call.respondBytes(
                  wearSmallNaming("wear.container.opacity"),
                  ContentType.Application.OctetStream,
                )
              // A mobile document: not a Wear widget.
              else -> call.respondBytes(template, ContentType.Application.OctetStream)
            }
          }
          get("/api/ui-builder/v1/designs/{id}/export.rc") {
            assertEquals("secret", call.request.queryParameters["token"])
            when (call.parameters["id"]) {
              "good",
              "mallorys",
              "viewed",
              "made",
              "wear",
              "framed",
              "foreign" -> {
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
    val store = UserDesignStore(storeDir, clock = { 42 })
    application {
      designServer(
        LibraryHolder(libraryDir),
        UiBuilderIntegration(config, UiBuilderClient(config, http)),
        github,
        userDesigns = store,
      )
    }
    return Fixture(createClient { followRedirects = false }, github, storeDir, store)
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

  private fun DesignLibrary.serialFor(store: UserDesignStore, login: String?): Long =
    json
      .decodeFromString(
        DesignManifest.serializer(),
        manifestFor(store, login).bytes.decodeToString(),
      )
      .serial

  @Test
  fun theServedSerialNeverFallsAcrossUnsharingAndRestarts() {
    val lib = DesignLibrary.load(library())
    val dir = Files.createTempDirectory("user-designs").toFile()
    val store = UserDesignStore(dir, clock = { 42 })
    val seen = mutableListOf<Pair<Long, Long>>()
    fun record(s: UserDesignStore) {
      seen += lib.serialFor(s, null) to lib.serialFor(s, "octocat")
    }

    record(store)
    val design = store.publish("octocat", key.copy(variant = "garage"), template, 1, "good", 3)
    record(store)
    store.share("octocat", design.entry.sha256, true)
    record(store)
    // The last shared design goes private: anyone's manifest loses it, but not its serial.
    store.share("octocat", design.entry.sha256, false)
    record(store)
    // A restart reads the persisted revisions back.
    record(UserDesignStore(dir, clock = { 42 }))

    seen.zipWithNext().forEach { (before, after) ->
      assertTrue(after.first >= before.first, "anonymous serial fell: $seen")
      assertTrue(after.second >= before.second, "signed-in serial fell: $seen")
    }
    assertTrue(seen.last().first > seen.first().first, "unsharing still raised it: $seen")
    // A private publish leaves everyone else's manifest the library's, signature and all.
    assertEquals(5, seen[1].first)
  }

  @Test
  fun anIndexFromBeforeSharedRevisionsKeepsItsAnonymousSerial() {
    val lib = DesignLibrary.load(library())
    val dir = Files.createTempDirectory("user-designs").toFile()
    val store = UserDesignStore(dir, clock = { 42 })
    val design = store.publish("octocat", key.copy(variant = "garage"), template, 1, "good", 3)
    store.share("octocat", design.entry.sha256, true)
    // As an older server wrote it: anyone's serial was library + revision.
    val index = File(dir, "index.json")
    index.writeText(index.readText().replace(Regex(""",?\s*"sharedRevision":\s*\d+"""), ""))
    val legacy = UserDesignStore(dir, clock = { 42 })
    assertEquals(5 + legacy.revision, lib.serialFor(legacy, null))
  }

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
  fun withTheHostsSignIn_myPagesFollowItsSessionAndOnlyItsCookieIsSent() = testApplication {
    val forwarded = mutableListOf<String?>()
    externalServices {
      hosts("http://preview") {
        routing {
          get(GithubAuth.HOST_WHOAMI_PATH) {
            val cookie = call.request.headers[HttpHeaders.Cookie]
            forwarded += cookie
            if (cookie == "__Host-cp_gh_auth=live")
              call.respondText(
                """{"login":"octocat","guest":false}""",
                ContentType.Application.Json,
              )
            else call.respondText("Not signed in.", status = HttpStatusCode.Unauthorized)
          }
        }
      }
    }
    val github =
      GithubAuth(
        GithubAuth.SignIn.Host("http://preview"),
        sessionSecret = "session-secret",
        http = createClient { followRedirects = false },
      )
    val store = UserDesignStore(Files.createTempDirectory("user-designs").toFile())
    application { designServer(LibraryHolder(library()), github = github, userDesigns = store) }
    val browser = createClient { followRedirects = false }

    // Signed out: sign-in is the host's, and an anonymous request never asks it.
    val signedOut = browser.get("/my")
    assertEquals(HttpStatusCode.Found, signedOut.status)
    assertEquals("/auth/github/start?return=%2Fmy", signedOut.headers[HttpHeaders.Location])
    assertEquals(
      "/auth/github/start?return=%2Fmy",
      browser.get(GithubAuth.START_PATH).headers[HttpHeaders.Location],
    )
    assertEquals(HttpStatusCode.Unauthorized, browser.get("/api/my/studio").status)
    assertTrue(forwarded.isEmpty())

    val session = "__Host-cp_gh_auth=live; design-server-edit=other"
    val page = browser.get("/my") { header(HttpHeaders.Cookie, session) }
    assertEquals(HttpStatusCode.OK, page.status)
    assertTrue("octocat" in page.bodyAsText())
    assertEquals("__Host-cp_gh_auth=live", forwarded.last(), "only the host's session cookie")

    val minted = browser.post("/api/my/device-token") { header(HttpHeaders.Cookie, session) }
    assertEquals(HttpStatusCode.OK, minted.status)
    val token = json.parseToJsonElement(minted.bodyAsText()).jsonObject["token"]!!.jsonPrimitive
    assertEquals("octocat", github.deviceLogin(token.content))

    val ended = browser.get("/my") { header(HttpHeaders.Cookie, "__Host-cp_gh_auth=ended") }
    assertEquals(HttpStatusCode.Found, ended.status)
    assertEquals(
      HttpStatusCode.Unauthorized,
      browser
        .post("/api/my/device-token") { header(HttpHeaders.Cookie, "cp_gh_auth=ended") }
        .status,
    )
  }

  @Test
  fun theHostSignInsDeviceTokenKeyIsMadeOnceAndKept() {
    val dir = Files.createTempDirectory("user-designs").toFile()
    val key = GithubAuth.storedKey(dir)
    assertTrue(key.length >= 32)
    assertEquals(key, GithubAuth.storedKey(dir))
    assertEquals(
      "rw-------",
      java.nio.file.attribute.PosixFilePermissions.toString(
        Files.getPosixFilePermissions(File(dir, "device-token-key").toPath())
      ),
    )
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
  fun aWearWidgetThatLeavesItsFrameToTheWatchIsKept() = testApplication {
    val f = fixture()
    assertEquals(HttpStatusCode.Created, f.publish("framed").status)
    val wear =
      UserDesignStore(f.storeDir).designs("octocat").mapNotNull { it.entry.key.container }.toSet()
    assertEquals(setOf(DesignKey.WEAR_SMALL, DesignKey.WEAR_LARGE), wear)
  }

  @Test
  fun aWearWidgetReadingAValueNothingSuppliesIsLeftOut() = testApplication {
    val f = fixture()
    assertEquals(HttpStatusCode.Created, f.publish("foreign").status)
    assertTrue(
      UserDesignStore(f.storeDir).designs("octocat").none { it.entry.key.container != null }
    )
  }

  @Test
  fun aCardDesignGetsTheWearWidgetsThatExportAndTheyShareWithIt() = testApplication {
    val f = fixture()
    val published = f.publish("wear")
    assertEquals(HttpStatusCode.Created, published.status)
    val card = json.decodeFromString(UserDesign.serializer(), published.bodyAsText())
    assertEquals(listOf(HaCardsSeed.WEAR_SMALL, HaCardsSeed.WEAR_LARGE), exportedWear)

    // The Small one plays as a Wear widget; the Large export didn't, so there is none.
    val store = UserDesignStore(f.storeDir)
    val wear = store.designs("octocat").single { it.entry.key.container != null }
    assertEquals(card.entry.key.forWearWidget(DesignKey.WEAR_SMALL), wear.entry.key)
    assertEquals(card.entry.sha256, wear.wearOf)
    assertEquals(card.revision, wear.revision)
    assertContentEquals(wearSmall, store.blob(wear.entry.sha256))
    assertFalse(wear.shared)

    // Shared with its card, so everyone's manifest has both.
    f.client.post("/api/my/designs/${card.entry.sha256}/share") {
      header(HttpHeaders.Cookie, f.cookie)
      setBody("""{"shared":true}""")
    }
    val shared = f.client.manifest().designs.filter { it.author == "github:octocat" }
    assertEquals(setOf(card.entry.key, wear.entry.key), shared.map { it.key }.toSet())

    // Unsharing from the Wear widget's own row unshares its card too: one group.
    f.client.post("/api/my/designs/${wear.entry.sha256}/share") {
      header(HttpHeaders.Cookie, f.cookie)
      setBody("""{"shared":false}""")
    }
    assertTrue(f.client.manifest().designs.none { it.author == "github:octocat" })

    // A republish whose Wear widgets no longer export drops the stale one.
    f.publish("good")
    assertTrue(
      UserDesignStore(f.storeDir).designs("octocat").none { it.entry.key.container != null }
    )
  }

  @Test
  fun badDesignsAreRefused() = testApplication {
    val f = fixture()
    assertEquals(HttpStatusCode.UnprocessableEntity, f.publish("bad").status)
    assertTrue("can't be published" in f.publish("bad").bodyAsText())
    // Nobody can say who edits it, so it isn't published.
    assertEquals(HttpStatusCode.Forbidden, f.publish("missing").status)
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

  @Test
  fun aPublishedDesignIsOneDesignIdAcrossItsKeysWithItsUiBuilderTitle() = testApplication {
    val f = fixture()
    val published = f.publish("wear")
    val design = json.decodeFromString(UserDesign.serializer(), published.bodyAsText())
    assertEquals("user:wear", design.entry.designId)
    assertEquals("Tile", design.entry.title)
    // Its Wear widget designs are the same design on another surface.
    val all = UserDesignStore(f.storeDir).designs("octocat")
    assertTrue(all.size > 1)
    assertTrue(all.all { it.entry.designId == "user:wear" })
    // A design UI Builder can't describe still gets its id, untitled.
    val untitled =
      json.decodeFromString(UserDesign.serializer(), f.publish("good", variant = "g").bodyAsText())
    assertEquals("user:good", untitled.entry.designId)
    assertEquals(null, untitled.entry.title)
  }

  @Test
  fun aDesignPublishedBeforeIdsIsServedWithOne() {
    val store = UserDesignStore(Files.createTempDirectory("user-designs").toFile(), clock = { 7 })
    val design = store.publish("octocat", key.copy(variant = "garage"), template, 1, "good", 3)
    val old = design.copy(entry = design.entry.copy(designId = null))
    assertEquals("user:good", old.served.designId)
    assertEquals(design.entry, design.served)
    // Nothing to name it by: served as stored.
    assertEquals(null, old.copy(sourceDesignId = null).served.designId)
  }

  @Test
  fun onlyADesignTheyOwnOrEditPublishes() = testApplication {
    val f = fixture()
    // Someone else's private design: the operator token could read it, but it isn't theirs.
    val foreign = f.publish("mallorys")
    assertEquals(HttpStatusCode.Forbidden, foreign.status)
    assertTrue("own or edit" in foreign.bodyAsText())
    // Shared with them to view is still not theirs to publish.
    assertEquals(HttpStatusCode.Forbidden, f.publish("viewed").status)
    assertTrue(UserDesignStore(f.storeDir).designs("octocat").isEmpty())
  }

  @Test
  fun aDesignTheServerMadeForThemPublishesAndIsListedForTheApp() = testApplication {
    val f = fixture()
    val token = f.github.deviceToken("octocat")
    // UI Builder records the server as its owner and shares it with nobody it can name here.
    assertEquals(HttpStatusCode.Forbidden, f.publish("made").status)
    // The server recorded who it made it for (the card studio does, on opening a draft).
    f.store.recordMade("octocat", "made", cardType = "tile")
    assertEquals(HttpStatusCode.Created, f.publish("made").status)
    // The app lists what it can publish with its device token; nobody else sees it.
    val listed =
      f.client.get("/api/my/studio") { header(HttpHeaders.Authorization, "Bearer $token") }
    assertEquals(HttpStatusCode.OK, listed.status)
    val made = json.decodeFromString(ListSerializer(MadeDesign.serializer()), listed.bodyAsText())
    assertEquals(listOf(MadeDesign("made", "octocat", "tile", 42)), made)
    assertEquals(HttpStatusCode.Unauthorized, f.client.get("/api/my/studio").status)
    // Recording it doesn't move anyone's manifest.
    assertEquals(5, f.client.manifest().serial)
  }

  @Test
  fun aDesignUiBuilderCannotExportIsBuiltHereAndBindsTheCard() = testApplication {
    val f = compilingFixture()
    val published = f.publish("handmade")
    assertEquals(HttpStatusCode.Created, published.status, published.bodyAsText())
    val design = json.decodeFromString(UserDesign.serializer(), published.bodyAsText())
    assertEquals("Garage", design.entry.title)
    assertEquals(7, design.revision)
    val bytes = assertNotNull(UserDesignStore(f.storeDir).blob(design.entry.sha256))
    val names =
      RcDocumentCodec.decode(bytes, RcWireLimits())
        .operations
        .filterIsInstance<RcNamedVariable>()
        .map { it.name }
    assertTrue("USER:light.s0.is_on" in names, "named: $names")
  }

  /** The library as this server captures it on the JVM, which a compiled design is held to. */
  private fun ApplicationTestBuilder.compilingFixture() =
    fixture(
      library(
        base =
          ee.schimke.ha.rc.CardDesignTemplates.capture(
              ee.schimke.ha.rc.CommonCaptureHost(
                ee.schimke.ha.designs.LibraryCaptureProfile.OPERATIONS
              ),
              key,
            )
            .bytes
      )
    )

  @Test
  fun aDesignMayReadTheValuesEveryHomeShares() = testApplication {
    val f = compilingFixture()
    val published = f.publish("sunlit")
    assertEquals(HttpStatusCode.Created, published.status, published.bodyAsText())
    val design = json.decodeFromString(UserDesign.serializer(), published.bodyAsText())
    val bytes = assertNotNull(UserDesignStore(f.storeDir).blob(design.entry.sha256))
    val named =
      RcDocumentCodec.decode(bytes, RcWireLimits())
        .operations
        .filterIsInstance<RcNamedVariable>()
        .associate { it.name to it.type }
    // In the shared domain, not the card's own, as a float: the type the app pushes it as.
    assertEquals(RcNamedVariable.FLOAT_TYPE, named["HA:sun.sun.elevation"], "named: $named")
    assertTrue(named.keys.none { it.startsWith("USER:HA") }, "named: $named")
  }

  @Test
  fun aDesignCannotReadAnEntityOfTheHomeItPlaysIn() = testApplication {
    val f = compilingFixture()
    val refused = f.publish("peeking")
    assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
    val message = refused.bodyAsText()
    assertTrue("HA:lock.front_door.numeric_state" in message, message)
    // It says what a design may read instead.
    assertTrue("HA:sun.sun.elevation" in message, message)
  }

  @Test
  fun aSharedValueReadAsTheWrongTypeIsRefused() = testApplication {
    val f = compilingFixture()
    val refused = f.publish("mistyped")
    assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
    assertTrue("wrong type" in refused.bodyAsText(), refused.bodyAsText())
  }

  @Test
  fun aDesignPublishesForItsCardInEachThemeOfItsStyle() = testApplication {
    val dark = "baked:Material3:dark"
    val f = fixture(library(themes = listOf(dark, "style:braun:light")))
    val published = f.publish("good")
    assertEquals(HttpStatusCode.Created, published.status)
    // Classic in light and dark; another card style is another choice, published on its own.
    val keys = UserDesignStore(f.storeDir).designs("octocat").map { it.entry.key }.toSet()
    assertEquals(
      setOf(key.copy(variant = "garage"), key.copy(variant = "garage", theme = dark)),
      keys,
    )
    // One design to the app, whichever of them it draws in.
    assertEquals(
      setOf("user:good"),
      UserDesignStore(f.storeDir).designs("octocat").map { it.entry.designId }.toSet(),
    )
  }

  @Test
  fun aDraftOpenedFromTheAppsCardStudioIsTheirsToPublish() = testApplication {
    val f = fixture()
    val studio = ee.schimke.ha.client.design.CardStudioClient("https://localhost", f.client.engine)
    val card =
      ee.schimke.ha.model.CardConfig(
        "tile",
        Json.parseToJsonElement("""{"type":"tile","entity":"light.kitchen"}""").jsonObject,
      )
    val snapshot =
      ee.schimke.ha.model.HaSnapshot(
        states = mapOf("light.kitchen" to ee.schimke.ha.model.EntityState("light.kitchen", "on"))
      )
    // The app may rename its draft; the card type comes from the seed.
    val draft = JsonObject(studio.seed(card, snapshot) + ("title" to JsonPrimitive("Mine")))
    val handoff = studio.handoff(draft)
    studio.close()
    val opening =
      f.client.post(handoff.removePrefix("https://localhost")) {
        header(HttpHeaders.Cookie, f.cookie)
      }
    assertEquals(HttpStatusCode.Found, opening.status)
    val designId = opened.single()
    // Recorded with the handoff the app holds for this card, so it publishes this card's design.
    assertEquals(
      listOf(MadeDesign(designId, "octocat", "tile", 42, handoff = handoffToken(handoff))),
      f.store.made("octocat"),
    )
  }
}
