package ee.schimke.ha.client.design

import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignSurface
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

/** The app publishes a design it opened in UI Builder privately, with its device token. */
class CardStudioPublishTest {
  private val key =
    DesignKey(
      cardType = "tile",
      surface = DesignSurface.MobileApp,
      rcApiLevel = 8,
      shape = "device_class=garage;domain=cover;state_icon=garage",
      theme = "baked:Material3:light",
    )
  private val json = headersOf(HttpHeaders.ContentType, "application/json")

  @Test
  fun listsTheDesignsOpenedForThisCardTypeWithTheDeviceToken() = runTest {
    val engine = MockEngine { request ->
      assertEquals("/api/my/studio", request.url.encodedPath)
      assertEquals(HttpMethod.Get, request.method)
      assertEquals("Bearer device-token", request.headers[HttpHeaders.Authorization])
      respond(
        """[{"designId":"card-new","login":"octocat","cardType":"tile"},
           {"designId":"card-gauge","login":"octocat","cardType":"gauge"},
           {"designId":"card-old","login":"octocat","handoff":"h1"}]""",
        HttpStatusCode.OK,
        json,
      )
    }
    val client = CardStudioClient("https://ha.example/designs/v1", engine)
    assertEquals(
      listOf(StudioDesign("card-new", "tile"), StudioDesign("card-old", null, handoff = "h1")),
      client.studioDesigns(" device-token\n", cardType = "tile"),
    )
  }

  @Test
  fun throughHomeAssistantEveryCallCarriesItsTokenOnItsOwnScheme() = runTest {
    val seen = mutableListOf<Pair<String, String?>>()
    val engine = MockEngine { request ->
      seen += request.url.toString() to request.headers[HttpHeaders.Authorization]
      if (request.url.encodedPath.endsWith("/handoffs"))
        respond("""{"path":"/studio/${"a".repeat(32)}"}""", HttpStatusCode.OK, json)
      else respond("[]", HttpStatusCode.OK, json)
    }
    // A Home Assistant on the LAN is often plain http; it already holds its own token.
    val client =
      CardStudioClient.throughHomeAssistant(
        "http://homeassistant.local:8123/",
        { "ha-token" },
        engine,
      )
    // A call that names no token (ha.coo.ee takes these anonymously) gets Home Assistant's.
    client.handoff(JsonObject(emptyMap()))
    client.studioDesigns("ha-token")
    val base = "http://homeassistant.local:8123/api/terrazzo/designs"
    assertEquals(
      listOf<Pair<String, String?>>(
        "$base/api/studio/handoffs" to "Bearer ha-token",
        "$base/api/my/studio" to "Bearer ha-token",
      ),
      seen,
    )
  }

  @Test
  fun publishesPrivatelyAndReturnsTheSkinToSwitchTo() = runTest {
    val engine = MockEngine { request ->
      assertEquals("/api/my/designs", request.url.encodedPath)
      assertEquals("Bearer device-token", request.headers[HttpHeaders.Authorization])
      val sent = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
      assertEquals("card-new", sent["designId"]!!.jsonPrimitive.content)
      assertEquals(privateVariant("card-new"), sent["variant"]!!.jsonPrimitive.content)
      assertEquals(key, Json.decodeFromJsonElement(DesignKey.serializer(), sent["key"]!!))
      respond(
        """{"entry":{"designId":"user:card-new"},"shared":false}""",
        HttpStatusCode.Created,
        json,
      )
    }
    val client = CardStudioClient("https://ha.example", engine)
    assertEquals("user:card-new", client.publishPrivately("device-token", "card-new", key))
  }

  @Test
  fun eachDesignPublishesUnderItsOwnValidVariant() {
    val a = privateVariant("card-AbC_123")
    assertNotEquals(a, privateVariant("card-other"))
    assertEquals(a, privateVariant("card-AbC_123"))
    assertTrue(Regex("[a-z0-9][a-z0-9-]{0,39}").matches(a), a)
    assertEquals("tok", handoffToken("https://ha.example/studio/tok"))
  }

  @Test
  fun publishesTheDesignOpenedFromThisCardNeverAnothers() {
    val mine = StudioDesign("a", "tile", handoff = "h-a")
    val other = StudioDesign("b", "tile", handoff = "h-b")
    // Newest first: another tile's design was opened since.
    assertEquals(mine, designForCard(listOf(other, mine), opened = listOf("h-a")))
    // Nothing opened from this card: only an unambiguous one.
    assertNull(designForCard(listOf(other, mine), opened = emptyList()))
    assertEquals(mine, designForCard(listOf(mine), opened = emptyList()))
  }

  @Test
  fun refusalsSayWhatToDoWithoutEchoingTheServer() = runTest {
    for ((status, expected) in
      listOf(
        HttpStatusCode.Unauthorized to "Sign in again in Settings",
        HttpStatusCode.Forbidden to "made or edit",
        HttpStatusCode.UnprocessableEntity to "it reads values this card doesn't provide",
      )) {
      val engine = MockEngine {
        respond(
          if (status == HttpStatusCode.UnprocessableEntity)
            "That design can't be published: it reads values this card doesn't provide: x.y"
          else "private server detail",
          status,
        )
      }
      val error =
        assertFailsWith<CardPublishException> {
          CardStudioClient("https://ha.example", engine).publishPrivately("t", "d", key)
        }
      assertTrue(expected in error.message!!, error.message)
      assertFalse("private server detail" in error.message!!)
    }
  }
}
