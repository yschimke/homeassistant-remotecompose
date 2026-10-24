package ee.schimke.ha.client.dashboard

import ee.schimke.ha.model.EntityState
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class DashboardAgentPromptTest {
  private val dashboard = buildJsonObject {
    put("title", "Garden")
    put("views", buildJsonArray {})
  }

  private fun state(id: String, state: String, vararg attrs: Pair<String, String>) =
    EntityState(
      entityId = id,
      state = state,
      attributes = buildJsonObject { attrs.forEach { (k, v) -> put(k, JsonPrimitive(v)) } },
    )

  @Test
  fun theContextCarriesTheDashboardAndEntities() {
    val context =
      DashboardAgentPrompt.context(
        dashboard = dashboard,
        states =
          listOf(
            state("sensor.soil", "41", "friendly_name" to "Soil", "unit_of_measurement" to "%"),
            state("valve.garden", "closed", "friendly_name" to "Garden valve"),
          ),
        title = "Garden",
      )
    assertTrue("## Current dashboard (Garden)" in context)
    assertTrue("\"title\": \"Garden\"" in context)
    assertTrue("sensor.soil | Soil | 41 | % | " in context)
    // Sorted by entity id.
    assertTrue(context.indexOf("sensor.soil") < context.indexOf("valve.garden"))
  }

  @Test
  fun aLargeHomeIsCappedAndSaysSo() {
    val states = (0 until DashboardAgentPrompt.MAX_ENTITIES + 3).map { state("sensor.s$it", "1") }
    assertTrue("(3 more entities not listed)" in DashboardAgentPrompt.context(dashboard, states))
  }
}
