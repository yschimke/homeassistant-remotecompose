package ee.schimke.ha.rc

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.rc.components.HaAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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
  fun callServiceWithASingleEntityListTargetsThatEntity() {
    val cfg = buildJsonObject {
      put("action", "perform-action")
      put("perform_action", "light.turn_on")
      putJsonObject("target") { putJsonArray("entity_id") { add("light.den") } }
    }
    val action = parseHaAction(cfg, "light.ignored") as HaAction.CallService
    assertEquals("light.den", action.entityId)
  }

  @Test
  fun callServiceWithSeveralEntitiesCarriesThemInServiceData() {
    // A list used to throw from .jsonPrimitive and drop the whole card.
    val cfg = buildJsonObject {
      put("action", "perform-action")
      put("perform_action", "light.turn_off")
      putJsonObject("target") {
        putJsonArray("entity_id") {
          add("light.den")
          add("light.hall")
        }
      }
      putJsonObject("data") { put("transition", 2) }
    }
    val action = parseHaAction(cfg, "light.ignored") as HaAction.CallService
    assertNull(action.entityId)
    assertEquals(
      buildJsonArray {
        add("light.den")
        add("light.hall")
      },
      action.serviceData["entity_id"],
    )
    assertEquals(JsonPrimitive(2), action.serviceData["transition"])
  }

  @Test
  fun callServiceWithAnAreaTargetDoesNotFallBackToTheCardEntity() {
    val cfg = buildJsonObject {
      put("action", "perform-action")
      put("perform_action", "light.turn_on")
      putJsonObject("target") { put("area_id", "kitchen") }
    }
    val action = parseHaAction(cfg, "light.card_entity") as HaAction.CallService
    assertNull(action.entityId)
    assertEquals(JsonPrimitive("kitchen"), action.serviceData["area_id"])
  }

  @Test
  fun callServiceWithLegacyServiceDataEntityListKeepsIt() {
    val cfg = buildJsonObject {
      put("action", "call-service")
      put("service", "switch.turn_on")
      putJsonObject("service_data") {
        putJsonArray("entity_id") {
          add("switch.a")
          add("switch.b")
        }
      }
    }
    val action = parseHaAction(cfg, "switch.card") as HaAction.CallService
    assertNull(action.entityId)
    assertEquals(2, (action.serviceData["entity_id"] as JsonArray).size)
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
  fun defaultTapActionTogglesHaToggleDomains() {
    // home-assistant/frontend src/common/const.ts DOMAINS_TOGGLE.
    listOf(
        "fan.a",
        "input_boolean.a",
        "light.a",
        "switch.a",
        "group.a",
        "automation.a",
        "humidifier.a",
        "valve.a",
      )
      .forEach { id -> assertEquals(HaAction.Toggle(id), defaultTapActionFor(id)) }
  }

  @Test
  fun defaultTapActionNeverActuatesCoversLocksOrMediaPlayers() {
    // A tap on a garage door or a lock opens more-info in HA; it must not open or unlock it.
    listOf("cover.garage_door", "lock.front_door", "media_player.tv", "climate.hall").forEach { id
      ->
      assertEquals(HaAction.MoreInfo(id), defaultTapActionFor(id))
    }
  }

  @Test
  fun defaultTapActionShowsMoreInfoForOtherDomains() {
    assertEquals(HaAction.MoreInfo("sensor.temp"), defaultTapActionFor("sensor.temp"))
    assertEquals(HaAction.None, defaultTapActionFor(null))
  }

  @Test
  fun toggleCardsToggleOnlyHaToggleDomains() {
    for (type in listOf("button", "tile", "picture-glance", "entity", "glance", "gauge")) {
      assertEquals(
        HaAction.Toggle("light.kitchen"),
        defaultTapActionFor(CardConfig(type), "light.kitchen"),
      )
      assertEquals(
        HaAction.MoreInfo("cover.garage_door"),
        defaultTapActionFor(CardConfig(type), "cover.garage_door"),
      )
    }
  }

  @Test
  fun pictureGlanceForceDialogOpensMoreInfo() {
    val card =
      CardConfig(
        "picture-glance",
        buildJsonObject {
          put("type", "picture-glance")
          put("force_dialog", true)
        },
      )
    assertEquals(HaAction.MoreInfo("light.kitchen"), defaultTapActionFor(card, "light.kitchen"))
  }
}
