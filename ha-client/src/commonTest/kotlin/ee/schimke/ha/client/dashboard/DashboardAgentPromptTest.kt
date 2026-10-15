package ee.schimke.ha.client.dashboard

import ee.schimke.ha.model.EntityState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
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
  fun thePromptCarriesTheRequestDashboardAndEntities() {
    val prompt =
      DashboardAgentPrompt.build(
        request = "Add soil moisture charts",
        dashboard = dashboard,
        states =
          listOf(
            state("sensor.soil", "41", "friendly_name" to "Soil", "unit_of_measurement" to "%"),
            state("valve.garden", "closed", "friendly_name" to "Garden valve"),
          ),
        title = "Garden",
      )
    assertTrue(prompt.startsWith(DashboardAgentPrompt.GUIDE))
    assertTrue("Add soil moisture charts" in prompt)
    assertTrue("## Current dashboard (Garden)" in prompt)
    assertTrue("\"title\": \"Garden\"" in prompt)
    assertTrue("sensor.soil | Soil | 41 | % | " in prompt)
    // Sorted by entity id.
    assertTrue(prompt.indexOf("sensor.soil") < prompt.indexOf("valve.garden"))
  }

  @Test
  fun aLargeHomeIsCappedAndSaysSo() {
    val states = (0 until DashboardAgentPrompt.MAX_ENTITIES + 3).map { state("sensor.s$it", "1") }
    val prompt = DashboardAgentPrompt.build("", dashboard, states)
    assertTrue("(3 more entities not listed)" in prompt)
    assertTrue("Improve this dashboard." in prompt)
  }

  @Test
  fun theAnswerIsReadFromItsJsonFence() {
    val answer =
      """
      Here is your dashboard:

      ```json
      {"title": "Garden v2", "views": [{"type": "sections", "sections": []}]}
      ```

      Enjoy.
      """
        .trimIndent()
    val config = DashboardAgentPrompt.parseAnswer(answer)
    assertEquals("Garden v2", config["title"]!!.jsonPrimitive.content)
  }

  @Test
  fun aBareJsonAnswerIsAccepted() {
    val config = DashboardAgentPrompt.parseAnswer("""{"views": []}""")
    assertEquals(1, config.size)
  }

  @Test
  fun answersThatAreNotADashboardAreRefused() {
    assertFailsWith<IllegalArgumentException> { DashboardAgentPrompt.parseAnswer("no json") }
    assertFailsWith<IllegalArgumentException> { DashboardAgentPrompt.parseAnswer("[1, 2]") }
    assertFailsWith<IllegalArgumentException> {
      DashboardAgentPrompt.parseAnswer("""{"title": "x"}""")
    }
    assertFailsWith<IllegalArgumentException> {
      DashboardAgentPrompt.parseAnswer("""{"views": [1]}""")
    }
  }
}
