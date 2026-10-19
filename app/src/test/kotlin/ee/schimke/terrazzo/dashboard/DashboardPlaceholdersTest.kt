package ee.schimke.terrazzo.dashboard

import android.app.Application
import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import ee.schimke.terrazzo.core.session.DemoHaSession
import ee.schimke.terrazzo.core.session.HaSession
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardPlaceholdersTest {
  @get:Rule val compose = createComposeRule()
  private val context: Application = RuntimeEnvironment.getApplication()

  @Before
  fun clearBriefs() {
    File(context.filesDir, "terrazzo/card-briefs").deleteRecursively()
  }

  @Test
  fun sectionLongPressPlansACardAndNothingWritesToHomeAssistant() {
    var writes = 0
    val session =
      object : HaSession by DemoHaSession() {
        override suspend fun fetchDashboardConfig(urlPath: String?): JsonObject =
          Json.parseToJsonElement("""{"views":[{"cards":[]}]}""") as JsonObject

        override suspend fun replaceDashboardConfig(
          urlPath: String?,
          config: JsonObject,
        ): JsonObject {
          writes++
          error("Local card planning must not save to Home Assistant")
        }
      }
    compose.setContent {
      var planning by remember { mutableStateOf(false) }
      MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
          Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            DashboardPlaceholders(
              session,
              "garden",
              planning = planning,
              onPlanningDone = { planning = false },
            )
            SectionHeading(title = "Garden", onLongClick = { planning = true })
          }
        }
      }
    }
    compose.waitForIdle()
    assertTrue(
      compose.onAllNodesWithText("Add placeholder card").fetchSemanticsNodes().isEmpty(),
      "planning starts from a section long press, not a button",
    )
    screenshot("before-add")
    compose.onNodeWithText("Garden").performTouchInput { longClick() }
    compose.onNodeWithText("Plan a card").assertExists()
    compose.onNodeWithText("Save placeholder").assertIsNotEnabled()
    compose.onNodeWithText("Card title").performTextInput("Garden moisture")
    compose
      .onNodeWithText("What should it show?")
      .performTextInput("Soil moisture trend and low-water warning")
    compose
      .onNodeWithText("HA entity IDs (optional)")
      .performScrollTo()
      .performTextInput("sensor.garden_moisture")
    compose
      .onNodeWithText("Component and bindings (optional)")
      .performScrollTo()
      .performTextInput("Gauge: moisture to value")
    compose.onNodeWithText("Plan a card").performScrollTo()
    screenshot("form")
    compose.onNodeWithText("Save placeholder").performScrollTo().performClick()
    compose.waitUntil(10_000) {
      runCatching { compose.onNodeWithText("Edit").assertIsEnabled() }.isSuccess
    }
    compose.onNodeWithText("Plan a card").assertDoesNotExist()
    screenshot("after-add")
    compose.onNodeWithText("Copy implementation prompt").performScrollTo().performClick()
    compose.waitUntil(10_000) {
      context.getSystemService(ClipboardManager::class.java).primaryClip != null
    }
    val prompt =
      context
        .getSystemService(ClipboardManager::class.java)
        .primaryClip!!
        .getItemAt(0)
        .text
        .toString()
    assertTrue("Garden moisture" in prompt)
    assertTrue("Gauge: moisture to value" in prompt)
    assertTrue("named bindings" in prompt)
    compose.waitUntil(10_000) {
      runCatching { compose.onNodeWithText("Edit").assertIsEnabled() }.isSuccess
    }
    compose.onNodeWithText("Edit").performScrollTo().performClick()
    compose
      .onNodeWithText("What should it show?")
      .performTextReplacement("Soil moisture trend and low-water warning with daily history")
    compose.onNodeWithText("Save placeholder").performScrollTo().performClick()
    compose.waitUntil(10_000) {
      runCatching { compose.onNodeWithText("Remove").assertIsEnabled() }.isSuccess
    }
    compose
      .onNodeWithText("Soil moisture trend and low-water warning with daily history")
      .assertExists()
    compose.onNodeWithText("Remove").performScrollTo().performClick()
    compose.waitUntil(10_000) {
      runCatching { compose.onNodeWithText("Garden moisture").assertDoesNotExist() }.isSuccess
    }
    assertEquals(0, writes)
  }

  private fun screenshot(name: String) {
    val root = File("build/reports/placeholder-cards").apply { mkdirs() }
    compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
      File(root, "$name.png").outputStream().use {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
      }
    }
  }
}
