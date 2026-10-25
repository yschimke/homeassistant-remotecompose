package ee.schimke.terrazzo.dashboard

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import ee.schimke.ha.model.EntityState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

@OptIn(ExperimentalTestApi::class)
class CardSettingsDialogTest {
  private val entities =
    listOf(
      EntityState(
        "switch.pump",
        "off",
        attributes = buildJsonObject { put("friendly_name", JsonPrimitive("Garden pump")) },
      ),
      EntityState("sensor.soil", "41"),
    )

  private fun card(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

  private fun ComposeUiTest.show(card: JsonObject, saved: MutableList<JsonObject>) = setContent {
    MaterialTheme { CardSettingsDialog(card, entities, onDismiss = {}, onSave = { saved += it }) }
  }

  @Test
  fun aTilesFormSetsNameLayoutAndTapAction() = runComposeUiTest {
    val saved = mutableListOf<JsonObject>()
    show(card("""{"type":"tile","entity":"sensor.soil","grid_options":{"columns":6}}"""), saved)
    onNode(hasSetTextAction() and hasText("Name")).performTextInput("Soil")
    onNodeWithContentDescription("Vertical").performClick()
    onNodeWithText("Default").performClick()
    onNodeWithText("Toggle").performClick()
    onNodeWithText("Change").performClick()
    onNodeWithText("Search entities").performTextInput("pump")
    onNodeWithText("Garden pump · switch.pump").performClick()
    onNodeWithText("Apply").performClick()
    assertEquals(
      card(
        """{"type":"tile","entity":"switch.pump","grid_options":{"columns":6},"name":"Soil",
          "vertical":true,"tap_action":{"action":"toggle"}}"""
      ),
      saved.single(),
    )
  }

  @Test
  fun aNumberThatIsntOneHoldsApply() = runComposeUiTest {
    val saved = mutableListOf<JsonObject>()
    show(card("""{"type":"gauge","entity":"sensor.soil"}"""), saved)
    val max = onNode(hasSetTextAction() and hasText("Maximum"))
    max.performTextInput("lots")
    onNodeWithText("Not a number.").assertExists()
    onNodeWithText("Apply").assertIsNotEnabled()
    onNodeWithText("JSON").assertIsNotEnabled()
    max.performTextReplacement("120")
    onNodeWithText("Apply").performClick()
    assertEquals(card("""{"type":"gauge","entity":"sensor.soil","max":120}"""), saved.single())
  }

  @Test
  fun entityRowsAreAddedAndRemoved() = runComposeUiTest {
    val saved = mutableListOf<JsonObject>()
    show(card("""{"type":"entities","entities":[{"entity":"sensor.soil","name":"Soil"}]}"""), saved)
    onNodeWithText("Add entity").performScrollTo().performClick()
    onNodeWithText("Garden pump · switch.pump").performClick()
    onNodeWithContentDescription("Remove sensor.soil").performClick()
    onNodeWithText("Apply").performClick()
    assertEquals(card("""{"type":"entities","entities":["switch.pump"]}"""), saved.single())
  }

  @Test
  fun formAndJsonEditTheSameCard() = runComposeUiTest {
    val saved = mutableListOf<JsonObject>()
    show(card("""{"type":"heading","heading":"Garden"}"""), saved)
    onNode(hasSetTextAction() and hasText("Garden")).performTextReplacement("Greenhouse")
    onNodeWithText("JSON").performClick()
    val json = onNode(hasSetTextAction() and hasText("Greenhouse", substring = true))
    json.performTextReplacement(
      """{"type":"heading","heading":"Shed","heading_style":"subtitle"}"""
    )
    onNodeWithText("Form").performClick()
    onNode(hasSetTextAction() and hasText("Shed")).assertExists()
    onNodeWithText("Subtitle").assertExists()
    onNodeWithText("Apply").performClick()
    assertEquals(
      card("""{"type":"heading","heading":"Shed","heading_style":"subtitle"}"""),
      saved.single(),
    )
  }

  @Test
  fun aTypeTheFormDoesntKnowOpensOnJson() = runComposeUiTest {
    val saved = mutableListOf<JsonObject>()
    show(card("""{"type":"custom:mushroom-card","entity":"sensor.soil"}"""), saved)
    onNodeWithText("Form").assertDoesNotExist()
    onNode(hasSetTextAction() and hasText("mushroom", substring = true)).assertExists()
    onNodeWithText("Apply").performClick()
    assertNull(saved.single()["name"])
  }
}
