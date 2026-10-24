package ee.schimke.terrazzo.assistant

import ee.schimke.ha.model.EntityState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class AssistantEntitiesTest {

  private fun entity(
    id: String,
    name: String? = null,
    state: String = "off",
    unit: String? = null,
  ) =
    EntityState(
      entityId = id,
      state = state,
      attributes =
        buildJsonObject {
          name?.let { put("friendly_name", JsonPrimitive(it)) }
          unit?.let { put("unit_of_measurement", JsonPrimitive(it)) }
        },
    )

  private val house =
    listOf(
      entity("light.kitchen_ceiling", "Kitchen ceiling"),
      entity("light.kitchen", "Kitchen"),
      entity("switch.kitchen_kettle", "Kettle"),
      entity("sensor.kitchen_temperature", "Kitchen temperature", "21.5", "°C"),
      entity("light.hall"),
      entity("lock.front_door", "Front door", "locked"),
    )

  @Test
  fun anExactNameComesFirstThenPrefixesThenTheRest() {
    assertEquals(
      listOf(
        "light.kitchen",
        "light.kitchen_ceiling",
        "sensor.kitchen_temperature",
        "switch.kitchen_kettle",
      ),
      AssistantEntities.search(house, "Kitchen", domain = null).map { it.entityId },
    )
  }

  @Test
  fun everyWordMustMatchTheNameOrId() {
    assertEquals(
      listOf("switch.kitchen_kettle"),
      AssistantEntities.search(house, "kettle kitchen", null).map { it.entityId },
    )
    assertEquals(emptyList(), AssistantEntities.search(house, "kitchen garage", null))
  }

  @Test
  fun aDomainFiltersAndBlankArgumentsListEverything() {
    assertEquals(
      // By name; light.hall has none, so its id stands in.
      listOf("light.kitchen", "light.kitchen_ceiling", "light.hall"),
      AssistantEntities.search(house, " ", "Light").map { it.entityId },
    )
    assertEquals(house.size, AssistantEntities.search(house, null, null).size)
    assertEquals(2, AssistantEntities.search(house, null, null, limit = 2).size)
  }

  @Test
  fun namesFallBackToTheEntityIdAndUnitsAreRead() {
    assertEquals("light.hall", AssistantEntities.name(house[4]))
    assertEquals("Kitchen", AssistantEntities.name(house[1]))
    assertEquals("°C", AssistantEntities.unit(house[3]))
    assertNull(AssistantEntities.unit(house[1]))
  }

  @Test
  fun locksAndAlarmPanelsAreReadOnly() {
    assertNotNull(AssistantEntities.writeRefusal("lock.front_door"))
    assertNotNull(AssistantEntities.writeRefusal("alarm_control_panel.home"))
    assertNull(AssistantEntities.writeRefusal("light.kitchen"))
    assertNull(AssistantEntities.writeRefusal("scene.movie"))
  }
}
