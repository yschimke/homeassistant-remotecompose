package ee.schimke.terrazzo.shared

import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.design.DesignEntry
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignManifest
import ee.schimke.ha.design.DesignSurface
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class SelectedDesignTest {
  @Test
  fun selectedDesignCanUseSingleEntityCustomCardWithoutChangingItsBindings() {
    val raw =
      Json.parseToJsonElement(
        """{"type":"custom:enhanced-shutter-card","entities":[{"entity":"cover.garage","top_offset_pct":13}]}""",
      ) as JsonObject
    val card = CardConfig("custom:enhanced-shutter-card", raw)
    val snapshot =
      HaSnapshot(
        states =
          mapOf(
            "cover.garage" to
              EntityState(
                "cover.garage",
                "closed",
                JsonObject(mapOf("device_class" to JsonPrimitive("garage"))),
              ),
          ),
      )
    val anchor = assertNotNull(selectedDesignKey(card, snapshot, "classic", false))
    assertEquals("tile", anchor.cardType)
    assertEquals("device_class=garage;domain=cover;state_icon=garage", anchor.shape)
    val values = exactTemplateBindings(card, snapshot)
    assertTrue("cover.s0.is_on" in values)
    val many =
      CardConfig(
        "custom:example",
        Json.parseToJsonElement(
          """{"type":"custom:example","entities":["cover.one","cover.two"]}""",
        ) as JsonObject,
      )
    assertNull(selectedDesignKey(many, snapshot, "classic", false))
  }

  @Test
  fun selectedPrivateDesignUsesHomeTokenAndChecksTheBlob() = runTest {
    val bytes = byteArrayOf(1, 2, 3)
    val sha = Sha256.hex(bytes)
    val key =
      DesignKey(
        cardType = "tile",
        surface = DesignSurface.MobileApp,
        rcApiLevel = 8,
        shape = "domain=cover",
        theme = "baked:Material3:light",
      )
    val entry = DesignEntry(key, sha, bytes.size, 1, designId = "user:door")
    val manifest =
      DesignManifest(
        serial = 1,
        generatedAtEpochMs = 1,
        expiresAtEpochMs = Clock.System.now().toEpochMilliseconds() + 60_000,
        designs = listOf(entry),
      )
    val requests = mutableListOf<String>()
    fun client(blob: ByteArray) =
      HttpClient(
        MockEngine { request ->
          assertEquals("ha.example", request.url.host)
          assertEquals("Bearer fresh-token", request.headers[HttpHeaders.Authorization])
          requests += request.url.encodedPath
          if (request.url.encodedPath.endsWith("manifest.json"))
            respond(
              Json.encodeToString(DesignManifest.serializer(), manifest),
              headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
          else respond(blob)
        },
      )
    val selected =
      LibraryDesignKey(
        JsonObject(emptyMap()),
        "classic",
        false,
        exact = key,
        designId = "user:door",
      )
    assertContentEquals(
      bytes,
      loadSelectedDesign(selected, "https://ha.example", { "fresh-token" }, client(bytes)),
    )
    assertEquals(
      listOf(
        "/api/terrazzo/designs/designs/v1/manifest.json",
        "/api/terrazzo/designs/designs/v1/blobs/$sha.rc",
      ),
      requests,
    )
    assertNull(
      loadSelectedDesign(selected, "https://ha.example", { "fresh-token" }, client(byteArrayOf(9))),
    )
  }
}
