package ee.schimke.ha.client

import ee.schimke.ha.client.dashboard.DashboardAgent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

/** What `terrazzo/agent/info` tells an administrator about setting the dashboard agent up. */
class DashboardAgentSetupTest {
  private val json = Json { ignoreUnknownKeys = true }

  private fun agent(text: String) = json.decodeFromString(DashboardAgent.serializer(), text)

  @Test
  fun noAgentPointsAtTerrazzosSettings() {
    val off =
      agent(
        """{"enabled":false,"agent_id":null,"name":null,"setup":"agent",
          "setup_path":"/config/integrations/integration/terrazzo"}""",
      )
    assertEquals("Set up a chat agent", off.setupLabel)
    assertEquals(
      "https://ha.example:8123/config/integrations/integration/terrazzo",
      off.setupUrl("https://ha.example:8123/"),
    )
  }

  @Test
  fun anAgentWithoutTheToolsPointsAtItsIntegration() {
    val plain =
      agent(
        """{"enabled":true,"agent_id":"conversation.plain","name":"Plain agent","setup":"tools",
          "setup_path":"/config/integrations/integration/ollama"}""",
      )
    assertEquals("Finish setting up Plain agent", plain.setupLabel)
    assertEquals(
      "http://ha.local/config/integrations/integration/ollama",
      plain.setupUrl("http://ha.local"),
    )
  }

  @Test
  fun nothingToSetUpOrNotAnAdministrator() {
    // An older integration sends neither field; a person who isn't an administrator gets nulls.
    for (text in
      listOf(
        """{"enabled":true,"agent_id":"conversation.a","name":"A"}""",
        """{"enabled":false,"agent_id":null,"name":null,"setup":null,"setup_path":null}""",
      )) {
      val found = agent(text)
      assertNull(found.setupLabel)
      assertNull(found.setupUrl("http://ha.local"))
    }
    // Only a path on Home Assistant itself is opened.
    assertNull(
      DashboardAgent(setup = "agent", setupPath = "https://elsewhere.example/")
        .setupUrl("http://ha.local"),
    )
  }
}
