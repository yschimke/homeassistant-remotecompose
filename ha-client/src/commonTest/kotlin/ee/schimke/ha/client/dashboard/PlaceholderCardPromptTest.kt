package ee.schimke.ha.client.dashboard

import ee.schimke.ha.model.EntityState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PlaceholderCardPromptTest {
  private val brief =
    PlaceholderCard(
      "brief-1",
      "Garden",
      "Soil moisture trend",
      listOf("sensor.soil"),
      "Gauge: moisture -> value",
    )

  @Test
  fun briefsRoundTripWithoutBecomingDashboardCards() {
    assertEquals(
      brief,
      Json.decodeFromString<PlaceholderCard>(
        Json.encodeToString(PlaceholderCard.serializer(), brief)
      ),
    )
    assertEquals(
      listOf("sensor.soil", "sensor.rain"),
      PlaceholderCard.parseEntityIds(" sensor.soil,\nsensor.rain sensor.soil "),
    )
    assertFailsWith<IllegalArgumentException> { brief.copy(title = " ") }
    assertFailsWith<IllegalArgumentException> { brief.copy(request = " ") }
    assertFailsWith<IllegalArgumentException> { brief.copy(entityIds = listOf("not an id")) }
  }

  @Test
  fun implementationHandoffCarriesBriefContextAndLiveBindingContract() {
    val dashboard =
      Json.parseToJsonElement(
        """{"views":[{"cards":[{"type":"custom:untouched","options":{"keep":true}}]}]}"""
      ) as kotlinx.serialization.json.JsonObject
    val states =
      listOf(
        EntityState(
          "sensor.soil",
          "42",
          buildJsonObject {
            put("friendly_name", "Garden soil")
            put("unit_of_measurement", "%")
            put("access_token", "must-not-be-copied")
          },
        )
      )
    val prompt = PlaceholderCardPrompt.build(brief, dashboard, states, "Home")
    assertTrue("Soil moisture trend" in prompt)
    assertTrue("Gauge: moisture -> value" in prompt)
    assertTrue("sensor.soil | Garden soil | 42 | %" in prompt)
    assertTrue("custom:untouched" in prompt)
    assertTrue("named bindings" in prompt)
    assertTrue("dataSignature" in prompt)
    assertTrue("subscription cleanup" in prompt)
    assertTrue("not a request for dashboard JSON alone" in prompt)
    assertFalse("must-not-be-copied" in prompt)
    assertEquals(1, (dashboard["views"] as kotlinx.serialization.json.JsonArray).size)
  }

  @Test
  fun missingSourcesAreExplicitAndRequestedValuesSurviveTheEntityCap() {
    val states =
      (0..DashboardAgentPrompt.MAX_ENTITIES).map { EntityState("sensor.s$it", "$it") } +
        EntityState("sensor.zz_requested", "67")
    val prompt =
      PlaceholderCardPrompt.build(
        brief.copy(entityIds = listOf("sensor.zz_requested", "sensor.missing")),
        buildJsonObject { put("views", kotlinx.serialization.json.JsonArray(emptyList())) },
        states,
      )
    assertTrue("sensor.zz_requested | 67" in prompt)
    assertTrue("Requested entities absent from this snapshot: sensor.missing" in prompt)
    assertTrue("do not invent entities" in prompt)
  }
}
