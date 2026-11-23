package ee.schimke.terrazzo.shared

import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.ha.design.DeviceInfo
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.REMOTE_COMPOSE_CARD_TYPE
import ee.schimke.ha.model.remoteComposeActions
import ee.schimke.ha.model.remoteComposeBindings
import ee.schimke.ha.model.remoteComposeDesignId
import ee.schimke.ha.model.remoteComposeDeviceId
import ee.schimke.ha.model.remoteComposeTemplateUrl
import ee.schimke.ha.rc.components.HaAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class SemanticDeviceBindingsTest {
  private fun doc(vararg vars: Pair<String, Int>): ByteArray =
    RcDocumentCodec.encode(
      RcDocument(
        RcHeader(RcVersion(1, 0, 0)),
        vars.mapIndexed { i, (name, type) -> RcNamedVariable(42 + i, type, "USER:$name") },
      ),
    )

  @Test
  fun wrapperAccessorsReadDesignTemplateBindingsActionsAndDeviceId() {
    val raw =
      Json.parseToJsonElement(
          """
          {
            "type": "$REMOTE_COMPOSE_CARD_TYPE",
            "card": {"type": "tile", "entity": "sensor.dw_state"},
            "design_id": "pack:bosch-dishwasher",
            "template_url": "/designs/v1/blobs/abc.rc",
            "device_id": "dw1",
            "bindings": {"sensor_operation_state": "sensor.dw_state"},
            "actions": {"start": "button.dw_start"}
          }
          """
            .trimIndent(),
        )
        .jsonObject
    val wrapper = CardConfig(REMOTE_COMPOSE_CARD_TYPE, raw)
    assertEquals("pack:bosch-dishwasher", remoteComposeDesignId(wrapper))
    assertEquals("/designs/v1/blobs/abc.rc", remoteComposeTemplateUrl(wrapper))
    assertEquals("dw1", remoteComposeDeviceId(wrapper))
    assertEquals(
      JsonPrimitive("sensor.dw_state"),
      remoteComposeBindings(wrapper)?.get("sensor_operation_state"),
    )
    assertEquals(JsonPrimitive("button.dw_start"), remoteComposeActions(wrapper)?.get("start"))
  }

  @Test
  fun semanticBindingsCombineExplicitMappingsAndSiblingAutoDiscovery() {
    val card =
      CardConfig(
        "tile",
        buildJsonObject {
          put("type", "tile")
          put("entity", "sensor.bosch_dw_operation_state")
        },
      )
    val snapshot =
      HaSnapshot(
        states =
          mapOf(
            "sensor.bosch_dw_operation_state" to
              EntityState("sensor.bosch_dw_operation_state", "run"),
            "sensor.bosch_dw_remaining" to
              EntityState(
                "sensor.bosch_dw_remaining",
                "45",
                buildJsonObject { put("unit_of_measurement", "min") },
              ),
            "binary_sensor.bosch_dw_door" to EntityState("binary_sensor.bosch_dw_door", "off"),
            "sensor.helper_program" to EntityState("sensor.helper_program", "eco_50"),
          ),
        devices =
          mapOf(
            "sensor.bosch_dw_operation_state" to
              DeviceInfo(deviceId = "dw1", translationKey = "operation_state"),
            "sensor.bosch_dw_remaining" to
              DeviceInfo(deviceId = "dw1", translationKey = "remaining_program_time"),
            "binary_sensor.bosch_dw_door" to DeviceInfo(deviceId = "dw1", translationKey = "door"),
          ),
      )
    val bytes =
      doc(
        "sensor_operation_state" to RcNamedVariable.STRING_TYPE,
        "sensor_remaining_program_time" to RcNamedVariable.STRING_TYPE,
        "binary_sensor_door" to RcNamedVariable.INT_TYPE,
        "program_label" to RcNamedVariable.STRING_TYPE,
        "active_tab" to RcNamedVariable.INT_TYPE,
      )
    val explicit = buildJsonObject {
      put(
        "sensor_operation_state",
        buildJsonObject {
          put("entity", "sensor.bosch_dw_operation_state")
          put("uppercase", true)
        },
      )
      put(
        "program_label",
        buildJsonObject {
          put("entity", "sensor.helper_program")
          put("format", "title")
        },
      )
    }
    val bound = semanticDeviceBindings(card, snapshot, bytes, explicit, null, emptyMap())
    assertEquals(RcNamedValue.Text("RUN"), bound["sensor_operation_state"])
    assertEquals(RcNamedValue.Text("45 min"), bound["sensor_remaining_program_time"])
    assertEquals(RcNamedValue.Integer(0), bound["binary_sensor_door"])
    assertEquals(RcNamedValue.Text("Eco 50"), bound["program_label"])
    assertNull(bound["active_tab"])
  }

  @Test
  fun semanticActionsResolveExplicitMapAndDeviceSiblings() {
    val card =
      CardConfig(
        "tile",
        buildJsonObject {
          put("type", "tile")
          put("entity", "sensor.bosch_dw_operation_state")
        },
      )
    val snapshot =
      HaSnapshot(
        devices =
          mapOf(
            "sensor.bosch_dw_operation_state" to
              DeviceInfo(deviceId = "dw1", translationKey = "operation_state"),
            "button.bosch_dw_start_pause" to
              DeviceInfo(deviceId = "dw1", translationKey = "start_pause"),
          ),
      )
    val actions = buildJsonObject {
      put(
        "eco_50",
        buildJsonObject {
          put("service", "home_connect.start_program")
          put("data", buildJsonObject { put("program", "Eco50") })
        },
      )
    }
    assertEquals(
      HaAction.CallService(
        "home_connect",
        "start_program",
        "sensor.bosch_dw_operation_state",
        JsonObject(mapOf("program" to JsonPrimitive("Eco50"))),
      ),
      resolveSemanticNamedAction(card, "eco_50", "", snapshot, actions, null, null),
    )
    assertEquals(
      HaAction.CallService("button", "press", "button.bosch_dw_start_pause"),
      resolveSemanticNamedAction(card, "start_pause", "", snapshot, actions, null, null),
    )
    assertNull(
      resolveSemanticNamedAction(card, "unknown_action", "", snapshot, actions, null, null),
    )
  }

  @Test
  fun resolveDirectTemplateUrlHandlesRelativeAndRejectsBadSchemes() {
    assertEquals(
      "https://ha.example/designs/v1/blobs/abc.rc",
      resolveDirectTemplateUrl("/designs/v1/blobs/abc.rc", "https://ha.example/"),
    )
    assertEquals(
      "https://cdn.example/card.rc",
      resolveDirectTemplateUrl("https://cdn.example/card.rc", "https://ha.example/"),
    )
    assertNull(resolveDirectTemplateUrl("javascript:alert(1)", "https://ha.example/"))
    assertNull(resolveDirectTemplateUrl("https://u:p@cdn.example/card.rc", "https://ha.example/"))
  }

  @Test
  fun semanticActionsPreserveFullTargetsWithoutSubstitutingPrimaryEntity() {
    val card =
      CardConfig(
        "tile",
        buildJsonObject {
          put("type", "tile")
          put("entity", "sensor.bosch_dw_operation_state")
        },
      )
    val actions = buildJsonObject {
      put(
        "eco_device",
        buildJsonObject {
          put("service", "home_connect.start_program")
          put("target", buildJsonObject { put("device_id", "dw1") })
          put("data", buildJsonObject { put("program", "Eco50") })
        },
      )
      put(
        "lights_area",
        buildJsonObject {
          put("service", "light.turn_off")
          put("target", buildJsonObject { put("area_id", "kitchen") })
        },
      )
      put(
        "lights_multi",
        buildJsonObject {
          put("service", "light.turn_on")
          put(
            "target",
            buildJsonObject {
              put(
                "entity_id",
                Json.parseToJsonElement("""["light.a","light.b"]"""),
              )
            },
          )
        },
      )
    }

    val eco = resolveSemanticAction(card, "eco_device", "", HaSnapshot(), actions, null, null)!!
    assertNull((eco.action as HaAction.CallService).entityId)
    assertEquals(buildJsonObject { put("device_id", "dw1") }, eco.target)
    val ecoCall = cardActionCall(semanticActionPayload(eco))!!
    assertNull(ecoCall.entityId)
    assertEquals(buildJsonObject { put("device_id", "dw1") }, ecoCall.target)

    val area = resolveSemanticAction(card, "lights_area", "", HaSnapshot(), actions, null, null)!!
    val areaCall = cardActionCall(semanticActionPayload(area))!!
    assertNull(areaCall.entityId)
    assertEquals(buildJsonObject { put("area_id", "kitchen") }, areaCall.target)

    val multi = resolveSemanticAction(card, "lights_multi", "", HaSnapshot(), actions, null, null)!!
    val multiCall = cardActionCall(semanticActionPayload(multi))!!
    assertNull(multiCall.entityId)
    assertEquals(
      buildJsonObject { put("entity_id", Json.parseToJsonElement("""["light.a","light.b"]""")) },
      multiCall.target,
    )
  }

  @Test
  fun standaloneNavigationUriHandlesNavigateAndUrlSafely() {
    val navPayload = cardActionPayload(HaAction.Navigate("/lovelace/kitchen"))
    assertEquals(
      "https://ha.example/lovelace/kitchen",
      standaloneNavigationUri(navPayload, "https://ha.example/"),
    )
    assertNull(
      standaloneNavigationUri(
        cardActionPayload(HaAction.Navigate("//evil.example")),
        "https://ha.example",
      ),
    )
    val urlPayload = cardActionPayload(HaAction.Url("https://docs.example/bosch"))
    assertEquals(
      "https://docs.example/bosch",
      standaloneNavigationUri(urlPayload, "https://ha.example"),
    )
    assertNull(
      standaloneNavigationUri(
        cardActionPayload(HaAction.Url("javascript:alert(1)")),
        "https://ha.example",
      ),
    )
    assertNull(
      standaloneNavigationUri(cardActionPayload(HaAction.Toggle("light.a")), "https://ha.example"),
    )
  }
}
