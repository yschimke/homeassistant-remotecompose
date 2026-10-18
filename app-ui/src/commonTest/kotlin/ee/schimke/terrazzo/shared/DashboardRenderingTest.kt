package ee.schimke.terrazzo.shared

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import ee.schimke.ha.client.HaConfig
import ee.schimke.ha.design.DeviceInfo
import ee.schimke.ha.model.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardRenderingTest {
  private fun obj(raw: String) = Json.parseToJsonElement(raw).jsonObject

  private fun card(raw: String) =
    obj(raw).let { CardConfig(it.getValue("type").jsonPrimitive.content, it) }

  private val snapshot =
    HaSnapshot(states = mapOf("sensor.progress" to EntityState("sensor.progress", "50")))

  @Test
  fun conditionsFollowLiveStateAndPackHiddenWrappers() {
    val conditional =
      card(
        """{"type":"conditional","conditions":[{"entity":"sensor.progress","state_not":["0","100"]}],"card":{"type":"tile","entity":"sensor.progress"}}"""
      )
    assertEquals("tile", visibleDashboardCard(conditional, snapshot)?.type)
    assertNull(
      visibleDashboardCard(
        conditional,
        snapshot.copy(states = mapOf("sensor.progress" to EntityState("sensor.progress", "100"))),
      )
    )
    assertNull(visibleDashboardCard(conditional, HaSnapshot()))
    val nested = card("""{"type":"conditional","conditions":[],"card":${conditional.raw}}""")
    assertEquals("tile", visibleDashboardCard(nested, snapshot)?.type)
  }

  @Test
  fun numericAndLogicalConditionsUseExclusiveFiniteBounds() {
    fun result(raw: String) = dashboardConditions(Json.parseToJsonElement(raw), snapshot)
    assertEquals(
      true,
      result(
        """[{"condition":"numeric_state","entity_id":"sensor.progress","above":49,"below":51}]"""
      ),
    )
    assertEquals(false, result("""[{"entity":"sensor.progress","above":50}]"""))
    assertEquals(false, result("""[{"entity":"sensor.progress","below":50}]"""))
    assertEquals(
      true,
      result(
        """[{"condition":"or","conditions":[{"entity":"sensor.progress","state":"50"},{"condition":"user","users":[]}]}]"""
      ),
    )
    assertNull(result("""[{"condition":"user","users":[]}]"""))
    assertNull(
      result("""[{"condition":"state","entity":"sensor.progress","state":"50","for":"00:01:00"}]""")
    )
    assertEquals(
      false,
      result("""[{"condition":"not","conditions":[{"entity":"sensor.progress","state":"50"}]}]"""),
    )
  }

  @Test
  fun ambiguousOrLegacyResolverResponsesAreNotExactDesigns() {
    assertFalse(
      exactLibraryDesign(
        obj("""{"exact":false,"key":{"shape":"device_class=temperature;domain=sensor"}}""")
      )
    )
    assertFalse(exactLibraryDesign(obj("""{"sha256":"legacy"}""")))
    assertTrue(exactLibraryDesign(obj("""{"exact":true}""")))
  }

  @Test
  fun bambuReadingsNeverComeFromAnotherPrinter() {
    val status = EntityState("sensor.p2s_print_status", "finish")
    val other = EntityState("sensor.other_print_status", "running")
    val configured =
      card("""{"type":"custom:ha-bambulab-print_status-card","printer":"p2s-device"}""")
    val data =
      HaSnapshot(
        states = listOf(status, other).associateBy { it.entityId },
        devices =
          mapOf(
            status.entityId to DeviceInfo(deviceId = "p2s-device"),
            other.entityId to DeviceInfo(deviceId = "other-device"),
          ),
      )
    assertEquals(listOf(status), bambuStatusEntities(configured, data).map { it.second })
    assertTrue(bambuStatusEntities(configured, data.copy(devices = emptyMap())).isEmpty())
    assertEquals(
      "running",
      bambuStatusEntities(
          configured,
          data.copy(states = data.states + (status.entityId to status.copy(state = "running"))),
        )
        .single()
        .second
        .state,
    )
  }

  @Test
  fun imageEntitiesUsePicturesAndInvalidateOnNewFramesAndTokens() {
    val image =
      EntityState(
        "image.cover",
        "2026-10-18T17:23:17.432840",
        obj("""{"entity_picture":"/api/image_proxy/cover?token=fixture"}"""),
      )
    val key = assertNotNull(entityPictureKey(image))
    assertNotEquals(key, entityPictureKey(image.copy(state = "2026-10-18T18:00:00")))
    assertNotEquals(
      key,
      entityPictureKey(
        image.copy(
          attributes = obj("""{"entity_picture":"/api/image_proxy/cover?token=rotated"}""")
        )
      ),
    )
    assertNull(entityPictureKey(image.copy(state = "unavailable")))
    assertNull(entityPictureKey(image.copy(attributes = JsonObject(emptyMap()))))
    assertTrue(isEntityPictureCard(card("""{"type":"entity","entity":"image.cover"}""")))
    assertFalse(isEntityPictureCard(card("""{"type":"entity","entity":"sensor.progress"}""")))
  }

  @Test
  fun picturesAuthenticateOnlyToTheConfiguredOrigin() = runTest {
    var reads = 0
    val client =
      EntityPictureClient(
        HaConfig("https://ha.example.test", "old-token"),
        { "current-token" },
        MockEngine { request ->
          reads++
          assertEquals("Bearer current-token", request.headers[HttpHeaders.Authorization])
          assertEquals("fixture", request.url.parameters["token"])
          assertEquals("frame 2", request.url.parameters["terrazzo_frame"])
          assertFalse(request.url.toString().contains("current-token"))
          respond(byteArrayOf(1, 2, 3))
        },
      )
    assertContentEquals(
      byteArrayOf(1, 2, 3),
      client.load(EntityPictureKey("/api/image_proxy/cover?token=fixture", "frame 2")),
    )
    for (path in
      listOf(
        "https://evil.example/cover",
        "//evil.example/cover",
        "http://ha.example.test/cover",
        "https://ha.example.test:444/cover",
      )) {
      assertFailsWith<IllegalArgumentException> { client.load(EntityPictureKey(path, "frame 2")) }
    }
    assertEquals(1, reads)
    client.close()
  }

  @Test
  fun scrollingSharesInflightLoadsAndKeepsCompletedValues() = runTest {
    val gate = CompletableDeferred<Unit>()
    var loads = 0
    val cache =
      DashboardLoadCache<String, String>(backgroundScope) { key ->
        loads++
        gate.await()
        key
      }
    val leaving = backgroundScope.async { cache.get("classic") }
    runCurrent()
    leaving.cancelAndJoin()
    val returning = backgroundScope.async { cache.get("classic") }
    gate.complete(Unit)
    runCurrent()
    assertEquals("classic", returning.await())
    assertEquals("classic", cache.peek("classic"))
    assertEquals("classic", cache.get("classic"))
    assertEquals(1, loads)
    assertEquals("lcd", cache.get("lcd"))
    assertEquals(2, loads)
  }

  @Test
  fun cacheIsBoundedAndFailedLoadsDoNotCancelTheDashboard() = runTest {
    var failures = 0
    val cache =
      DashboardLoadCache<String, String>(backgroundScope, capacity = 2) { key ->
        if (key == "failure") {
          failures++
          error("Fixture failure")
        }
        key
      }
    assertEquals("a", cache.get("a"))
    assertEquals("b", cache.get("b"))
    assertEquals("c", cache.get("c"))
    assertNull(cache.peek("a"))
    assertFailsWith<IllegalStateException> { cache.get("failure") }
    assertFailsWith<IllegalStateException> { cache.get("failure") }
    assertEquals(2, failures)
    assertEquals("still alive", cache.get("still alive"))
  }

  @Test
  fun lightStatesStayDistinctAndLitInkContrastsWithEveryCardPalette() {
    val neutral = Color(0xFF46503A)
    assertEquals("Light on", lightIconDescription("on"))
    assertEquals("Light off", lightIconDescription("off"))
    assertEquals("Light unavailable", lightIconDescription("unavailable"))
    assertEquals("Light unknown", lightIconDescription("unknown"))
    for (surface in listOf(0xFFFFF8FF, 0xFFF8F6F0, 0xFFB9C3A2, 0xFF2F4A57, 0xFF07263A)) {
      val background = Color(surface)
      val lit = lightIconTint("on", background, neutral)
      val ratio =
        (maxOf(lit.luminance(), background.luminance()) + 0.05f) /
          (minOf(lit.luminance(), background.luminance()) + 0.05f)
      assertTrue(ratio >= 3f, "Lit icon needs 3:1 contrast against $surface: $ratio")
      assertEquals(1f, lit.alpha)
      assertNotEquals(neutral, lit)
      assertEquals(neutral, lightIconTint("off", background, neutral))
      assertEquals(neutral, lightIconTint("unavailable", background, neutral))
    }
  }
}
