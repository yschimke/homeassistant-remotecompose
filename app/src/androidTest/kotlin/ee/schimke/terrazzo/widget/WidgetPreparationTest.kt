package ee.schimke.terrazzo.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.LocalTerrazzoGraph
import ee.schimke.terrazzo.TerrazzoApplication
import ee.schimke.terrazzo.core.session.DemoData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetPreparationTest {
  @get:Rule val compose = createComposeRule()
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val graph = (context as TerrazzoApplication).graph
  private val card =
    CardConfig(
      "tile",
      buildJsonObject {
        put("type", "tile")
        put("entity", "light.garage_door_garage_door_light")
        put("name", "Studio light")
      },
    )

  private fun screenshot(name: String) {
    val directory =
      java.io.File(context.getExternalFilesDir(null), "widget-test-screenshots").apply { mkdirs() }
    assertTrue(
      UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        .takeScreenshot(java.io.File(directory, "$name.png"))
    )
  }

  private fun snapshot(state: String) =
    HaSnapshot(
      states =
        mapOf(
          "light.garage_door_garage_door_light" to
            EntityState("light.garage_door_garage_door_light", state)
        )
    )

  @Test
  fun realWidgetPreviewRendersResizesAndTracksState() {
    val current = mutableStateOf(snapshot("off"))
    var captures = 0
    compose.setContent {
      MaterialTheme {
        Column {
          WidgetPreparationPreview(DemoData.BASE_URL, card, current.value) {
            if (it != null) captures++
          }
        }
      }
    }
    compose.waitUntil(30_000) { captures > 0 }
    fun pixels(): List<Int> {
      compose.waitForIdle()
      val bounds = compose.onNodeWithTag("widget-render").fetchSemanticsNode().boundsInWindow
      val screenshot =
        checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
      val bitmap =
        android.graphics.Bitmap.createBitmap(
          screenshot,
          bounds.left.toInt(),
          bounds.top.toInt(),
          bounds.width.toInt(),
          bounds.height.toInt(),
        )
      val p = bitmap.asImageBitmap().toPixelMap()
      return (0 until p.height).flatMap { y -> (0 until p.width).map { x -> p[x, y].hashCode() } }
    }
    val off = pixels()
    screenshot("preview-off")
    assertTrue("preview must draw content, not a flat empty canvas", off.distinct().size > 10)
    compose.runOnIdle { current.value = snapshot("on") }
    compose.waitUntil(30_000) { captures >= 2 }
    assertNotEquals("a live state change must alter rendered pixels", off, pixels())
    screenshot("preview-on")
    compose.onNodeWithText("Compact").performClick()
    compose.waitForIdle()
    val compact = compose.onNodeWithTag("widget-render").fetchSemanticsNode().size
    screenshot("preview-compact")
    compose.onNodeWithText("Roomy").performClick()
    compose.waitForIdle()
    val roomy = compose.onNodeWithTag("widget-render").fetchSemanticsNode().size
    assertTrue(
      "size presets must actually resize the widget",
      roomy.width > compact.width || roomy.height > compact.height,
    )
    assertTrue("resized widget must still render content", pixels().distinct().size > 10)
    screenshot("preview-roomy")
  }

  @Test
  fun editPreviewCancelThenConfirmInstallationPersistsEditedCard() {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    assertTrue(
      "The emulator launcher must support widget pinning",
      WidgetInstaller(context).isSupported(),
    )
    val before = runBlocking { graph.widgetStore.installed.first().map { it.widgetId }.toSet() }
    var dismissed = false
    compose.setContent {
      CompositionLocalProvider(LocalTerrazzoGraph provides graph) {
        MaterialTheme {
          WidgetInstallSheet(DemoData.BASE_URL, "", card, snapshot("on"), { dismissed = true })
        }
      }
    }
    compose.onNodeWithText("Widget name").performTextClearance()
    compose.onNodeWithText("Widget name").performTextInput("My magical light")
    compose.onNodeWithText("Roomy").performScrollTo().performClick()
    compose.waitUntil(30_000) {
      compose
        .onAllNodes(hasText("Install widget") and isEnabled())
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    compose.onNodeWithText("Install widget").assertIsEnabled().performClick()
    assertTrue(
      device.wait(
        Until.hasObject(By.text(java.util.regex.Pattern.compile("(?i).*add to home screen.*"))),
        30_000,
      )
    )
    device.pressBack()
    assertEquals(
      "cancelled pins must not consume a slot",
      before,
      runBlocking { graph.widgetStore.installed.first().map { it.widgetId }.toSet() },
    )
    assertFalse("cancelling must return to the edited preparation", dismissed)
    compose.onNodeWithText("My magical light").assertExists()
    compose.waitUntil(30_000) {
      compose
        .onAllNodes(hasText("Install widget") and isEnabled())
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    compose.onNodeWithText("Install widget").assertIsEnabled().performClick()
    screenshot("launcher-confirmation")
    val add =
      device.wait(Until.findObject(By.text("Add automatically")), 30_000)
        ?: device.wait(Until.findObject(By.text("Add")), 5_000)
    assertNotNull("launcher must offer confirmation", add)
    add!!.click()
    compose.waitUntil(30_000) {
      runBlocking { graph.widgetStore.installed.first().any { it.widgetId !in before } }
    }
    val installed = runBlocking {
      graph.widgetStore.installed.first().first { it.widgetId !in before }
    }
    assertEquals("My magical light", installed.card.raw["name"]?.jsonPrimitive?.content)
    device.pressHome()
    device.waitForIdle()
    screenshot("installed-widget")
    val manager = AppWidgetManager.getInstance(context)
    val small =
      Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 72)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 84)
      }
    manager.updateAppWidgetOptions(installed.widgetId, small)
    assertEquals(
      WidgetSizeDp(72, 84),
      WidgetSizing.forWidgetCapture(manager, installed.widgetId, 84),
    )
    val large =
      Bundle(small).apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 144)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 168)
      }
    manager.updateAppWidgetOptions(installed.widgetId, large)
    assertEquals(
      WidgetSizeDp(144, 168),
      WidgetSizing.forWidgetCapture(manager, installed.widgetId, 84),
    )
  }
}
