package ee.schimke.terrazzo.dashboard

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import ee.schimke.ha.client.dashboard.CardAddress
import ee.schimke.ha.client.dashboard.DashboardDraft
import ee.schimke.ha.client.dashboard.DashboardEditSession
import ee.schimke.terrazzo.core.session.DemoHaSession
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardEditorTest {
  @get:Rule val compose = createComposeRule()

  private val original =
    Json.parseToJsonElement(
      """
      {"title":"Climate","custom":{"kept":true},"views":[{"type":"sections","sections":[
        {"type":"grid","cards":[
          {"type":"tile","entity":"sensor.downstairs_temperature"},
          {"type":"tile","entity":"sensor.upstairs_temperature","grid_options":{"columns":9}}
        ]}
      ]}]}
      """
    ) as JsonObject

  private val second = CardAddress(0, 0, 1)

  private fun setUp(): Pair<DashboardEditSession, MutableList<Pair<JsonObject, JsonObject>>> {
    val writes = mutableListOf<Pair<JsonObject, JsonObject>>()
    val session = DemoHaSession(clock = { 0L })
    val edits =
      DashboardEditSession(original) { expected, replacement -> writes += expected to replacement }
    compose.setContent {
      val scope = rememberCoroutineScope()
      MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
          DashboardEditor(
            session = session,
            edits = edits,
            title = "Climate",
            snapshot = session.dashboardNow("climate").second,
            snackbars = SnackbarHostState(),
            onBack = {},
            onSaved = {},
            launch = { block -> scope.launch { block() } },
          )
        }
      }
    }
    return edits to writes
  }

  @Test
  fun draggingTheHandleResizesAcrossSeveralColumnsInOneGesture() {
    val (edits, _) = setUp()
    compose.onNodeWithContentDescription("Card 2: tile").performClick()
    // Each step of the drag rewrites the card; a gesture cancelled by that would stop after one
    // column instead of reaching the tile's minimum.
    // One move per frame, recomposing in between, as on a device: the card is rewritten while the
    // finger is still down. The handle is found once; later moves follow the same pointer.
    val handle = compose.onNodeWithContentDescription("Drag to resize")
    handle.performTouchInput { down(center) }
    repeat(20) {
      handle.performTouchInput { moveBy(Offset(-20f, 0f)) }
      compose.waitForIdle()
    }
    handle.performTouchInput { up() }
    compose.waitForIdle()
    val min = DashboardDraft.size(DashboardDraft.card(original, second)!!).minColumns
    // Several one-column steps from 9, so only a drag that kept going gets there.
    assertTrue(min <= 7, "tile minimum $min")
    assertEquals(min, DashboardDraft.size(DashboardDraft.card(edits.draft, second)!!).columns)
  }

  @Test
  fun toolbarResizesAndSaveIsAConditionalWriteOfTheWholeConfig() {
    val (edits, writes) = setUp()
    compose.onNodeWithContentDescription("Card 2: tile").performClick()
    compose.onNodeWithContentDescription("Wider").performClick()
    compose.onNodeWithText("12/12 wide").assertExists()
    compose.onNodeWithText("Save").performClick()
    compose.waitForIdle()

    assertEquals(1, writes.size)
    val (expected, saved) = writes.single()
    assertEquals(original, expected)
    assertEquals(original["custom"], saved["custom"])
    assertEquals(12, DashboardDraft.size(DashboardDraft.card(saved, second)!!).columns)
    assertEquals(saved, edits.draft)
  }

  @Test
  fun deletingASelectedCardLeavesTheRest() {
    val (edits, writes) = setUp()
    compose.onNodeWithContentDescription("Card 1: tile").performClick()
    compose.onNodeWithContentDescription("Delete card").performClick()
    compose.waitForIdle()
    assertEquals(1, DashboardDraft.cards(edits.draft, 0, 0).size)
    assertNull(DashboardDraft.card(edits.draft, second))
    assertEquals(emptyList(), writes)
  }

  @Test
  fun aCardSwapsItsTemplateKeepingItsEntityAndSize() {
    val (edits, writes) = setUp()
    compose.onNodeWithContentDescription("Card 2: tile").performClick()
    compose.onNodeWithText("Template…").performClick()
    compose.onNodeWithText("Gauge").performClick()
    compose.waitForIdle()
    val card = DashboardDraft.card(edits.draft, second)!!
    assertEquals("gauge", card["type"]!!.jsonPrimitive.content)
    assertEquals("sensor.upstairs_temperature", card["entity"]!!.jsonPrimitive.content)
    assertEquals(original["custom"], edits.draft["custom"])
    // A swap is a draft edit like any other: nothing is written until Save.
    assertEquals(emptyList(), writes)
  }
}
