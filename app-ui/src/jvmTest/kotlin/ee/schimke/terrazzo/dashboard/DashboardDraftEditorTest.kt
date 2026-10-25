package ee.schimke.terrazzo.dashboard

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import ee.schimke.ha.client.dashboard.DashboardDraft
import ee.schimke.ha.client.dashboard.DashboardEditSession
import ee.schimke.ha.model.EntityState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalTestApi::class)
class DashboardDraftEditorTest {
  private val original =
    Json.parseToJsonElement(
        """{"title":"Garden","views":[{"title":"Home","path":"home","type":"sections",
          "sections":[{"type":"grid","cards":[{"type":"tile","entity":"sensor.soil"}]}]}]}"""
      )
      .jsonObject

  private val entities =
    listOf(
      EntityState(
        "switch.pump",
        "off",
        attributes = buildJsonObject { put("friendly_name", JsonPrimitive("Garden pump")) },
      ),
      EntityState("sensor.soil", "41"),
    )

  private fun ComposeUiTest.show(
    scope: EditorScope,
    writes: MutableList<JsonObject> = mutableListOf(),
  ): DashboardEditSession {
    val edits = DashboardEditSession(original) { _, replacement -> writes += replacement }
    setContent {
      val coroutines = rememberCoroutineScope()
      MaterialTheme {
        DashboardDraftEditor(
          edits = edits,
          title = "Garden",
          snackbars = SnackbarHostState(),
          onBack = {},
          onSaved = {},
          launch = { block -> coroutines.launch { block() } },
          scope = scope,
          entities = entities,
        ) { card ->
          Text("card:${card["type"]?.jsonPrimitive?.content}")
        }
      }
    }
    return edits
  }

  @Test
  fun theLightScopeOffersNoAdding() = runComposeUiTest {
    show(EditorScope.Light)
    onNodeWithText("card:tile").assertExists()
    onNodeWithText("Add card to section 1").assertDoesNotExist()
    onNodeWithText("Add section").assertDoesNotExist()
    onNodeWithContentDescription("View options").assertDoesNotExist()
    onNodeWithContentDescription("Card 1: tile").performClick()
    onNodeWithText("Settings…").assertDoesNotExist()
  }

  @Test
  fun aCardIsAddedFromAnEntityAndATypeThatSuitsIt() = runComposeUiTest {
    val writes = mutableListOf<JsonObject>()
    val edits = show(EditorScope.Full, writes)
    onNodeWithText("Add card to section 1").performClick()
    onNodeWithText("Search entities").performTextInput("pump")
    onNodeWithText("sensor.soil").assertDoesNotExist()
    onNodeWithText("Garden pump · switch.pump").performClick()
    onNodeWithText("Button").performClick()
    waitForIdle()
    val added = DashboardDraft.cards(edits.draft, 0, 0).last()
    assertEquals("button", added["type"]!!.jsonPrimitive.content)
    assertEquals("switch.pump", added["entity"]!!.jsonPrimitive.content)
    // Nothing reaches Home Assistant until Save, which writes the whole draft.
    assertTrue(writes.isEmpty())
    onNodeWithText("Save").performClick()
    waitUntil { writes.isNotEmpty() }
    assertEquals(edits.draft, writes.single())
  }

  @Test
  fun aCardsSettingsAreEditedAsJson() = runComposeUiTest {
    val edits = show(EditorScope.Full)
    onNodeWithContentDescription("Card 1: tile").performClick()
    onNodeWithText("Settings…").performClick()
    val field = onNode(hasSetTextAction() and hasText("sensor.soil", substring = true))
    field.performTextReplacement("""{"entity":"sensor.soil"}""")
    onNodeWithText("A card needs a \"type\".").assertExists()
    onNodeWithText("Apply").assertIsNotEnabled()
    field.performTextReplacement("""{"type":"gauge","entity":"sensor.soil","max":100}""")
    onNodeWithText("Apply").performClick()
    waitForIdle()
    val card = DashboardDraft.cards(edits.draft, 0, 0).single()
    assertEquals("gauge", card["type"]!!.jsonPrimitive.content)
    assertEquals(100, card["max"]!!.jsonPrimitive.content.toInt())
  }

  @Test
  fun sectionsAndViewsAreAddedRenamedAndRemoved() = runComposeUiTest {
    val edits = show(EditorScope.Full)
    onNodeWithText("Add section").performScrollTo().performClick()
    waitForIdle()
    assertEquals(2, DashboardDraft.sections(edits.draft, 0).size)
    onNodeWithContentDescription("Delete section 2").performScrollTo().performClick()
    waitForIdle()
    assertEquals(1, DashboardDraft.sections(edits.draft, 0).size)

    onNodeWithContentDescription("View options").performClick()
    onNodeWithText("Add view").performClick()
    onNode(hasSetTextAction()).performTextInput("Greenhouse")
    onNodeWithText("Add").performClick()
    waitForIdle()
    assertEquals(
      listOf("Home", "Greenhouse"),
      DashboardDraft.views(edits.draft).map { it["title"]!!.jsonPrimitive.content },
    )

    // The new view is shown; rename it, then delete it.
    onNodeWithContentDescription("View options").performClick()
    onNodeWithText("Rename view").performClick()
    onNode(hasSetTextAction()).performTextReplacement("Glasshouse")
    onNodeWithText("Rename").performClick()
    waitForIdle()
    assertEquals(
      "Glasshouse",
      DashboardDraft.views(edits.draft)[1]["title"]!!.jsonPrimitive.content,
    )
    onNodeWithContentDescription("View options").performClick()
    onNodeWithText("Delete view").performClick()
    waitForIdle()
    assertEquals(original, edits.draft)
  }

  @Test
  fun entitiesMatchEveryWordInTheirNameOrId() {
    assertEquals(listOf("switch.pump"), entityMatches(entities, "garden PUMP").map { it.entityId })
    assertEquals(listOf("switch.pump"), entityMatches(entities, "switch.").map { it.entityId })
    assertEquals(2, entityMatches(entities, "").size)
  }

  @Test
  fun cardSettingsMustBeAnObjectWithAType() {
    assertTrue(parseCardSettings("""{"type":"tile"}""").isSuccess)
    assertEquals("Not valid JSON.", parseCardSettings("{").exceptionOrNull()?.message)
    assertEquals("A card is a JSON object.", parseCardSettings("[]").exceptionOrNull()?.message)
  }
}
