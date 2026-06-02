package ee.schimke.ha.rc

import ee.schimke.ha.rc.components.HaAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class HaActionParserTest {

  @Test
  fun nullConfigIsNone() {
    assertEquals(HaAction.None, parseHaAction(null, "light.kitchen"))
  }

  @Test
  fun missingActionKeyIsNone() {
    assertEquals(HaAction.None, parseHaAction(buildJsonObject { put("entity", "light.x") }, null))
  }

  @Test
  fun toggleFallsBackToDefaultEntity() {
    val cfg = buildJsonObject { put("action", "toggle") }
    assertEquals(HaAction.Toggle("light.kitchen"), parseHaAction(cfg, "light.kitchen"))
  }

  @Test
  fun toggleWithoutAnyEntityIsNone() {
    assertEquals(HaAction.None, parseHaAction(buildJsonObject { put("action", "toggle") }, null))
  }

  @Test
  fun explicitEntityWinsOverDefault() {
    val cfg = buildJsonObject {
      put("action", "more-info")
      put("entity", "sensor.explicit")
    }
    assertEquals(HaAction.MoreInfo("sensor.explicit"), parseHaAction(cfg, "sensor.default"))
  }

  @Test
  fun navigateRequiresPath() {
    val ok = buildJsonObject {
      put("action", "navigate")
      put("navigation_path", "/lovelace/0")
    }
    assertEquals(HaAction.Navigate("/lovelace/0"), parseHaAction(ok, null))
    assertEquals(HaAction.None, parseHaAction(buildJsonObject { put("action", "navigate") }, null))
  }

  @Test
  fun urlRequiresPath() {
    val ok = buildJsonObject {
      put("action", "url")
      put("url_path", "https://example.test")
    }
    assertEquals(HaAction.Url("https://example.test"), parseHaAction(ok, null))
    assertEquals(HaAction.None, parseHaAction(buildJsonObject { put("action", "url") }, null))
  }

  @Test
  fun callServiceReadsTargetEntityAndData() {
    val cfg = buildJsonObject {
      put("action", "call-service")
      put("service", "light.turn_on")
      putJsonObject("target") { put("entity_id", "light.den") }
      putJsonObject("data") { put("brightness", 128) }
    }
    val action = parseHaAction(cfg, "light.ignored") as HaAction.CallService
    assertEquals("light", action.domain)
    assertEquals("turn_on", action.service)
    assertEquals("light.den", action.entityId)
    assertEquals(JsonPrimitive(128), action.serviceData["brightness"])
  }

  @Test
  fun performActionAliasUsesServiceDataFallbacks() {
    // `perform-action` is HA's newer name for `call-service`; entity and
    // data fall back to the legacy `service_data` block when `target`/`data`
    // are absent.
    val cfg = buildJsonObject {
      put("action", "perform-action")
      put("perform_action", "switch.toggle")
      putJsonObject("service_data") { put("entity_id", "switch.fan") }
    }
    val action = parseHaAction(cfg, null) as HaAction.CallService
    assertEquals("switch", action.domain)
    assertEquals("toggle", action.service)
    assertEquals("switch.fan", action.entityId)
  }

  @Test
  fun callServiceWithoutDomainQualifiedServiceIsNone() {
    val cfg = buildJsonObject {
      put("action", "call-service")
      put("service", "toggle") // missing "<domain>." prefix
    }
    assertEquals(HaAction.None, parseHaAction(cfg, "light.x"))
  }

  @Test
  fun explicitNoneAndUnknownActionsAreNone() {
    assertEquals(HaAction.None, parseHaAction(buildJsonObject { put("action", "none") }, "light.x"))
    assertEquals(
      HaAction.None,
      parseHaAction(buildJsonObject { put("action", "fire-dom-event") }, "light.x"),
    )
  }

  @Test
  fun defaultTapActionTogglesToggleableDomains() {
    listOf("light.a", "switch.a", "input_boolean.a", "fan.a", "cover.a", "media_player.a", "lock.a")
      .forEach { id -> assertEquals(HaAction.Toggle(id), defaultTapActionFor(id)) }
  }

  @Test
  fun defaultTapActionShowsMoreInfoForOtherDomains() {
    assertEquals(HaAction.MoreInfo("sensor.temp"), defaultTapActionFor("sensor.temp"))
    assertEquals(HaAction.None, defaultTapActionFor(null))
  }
}
