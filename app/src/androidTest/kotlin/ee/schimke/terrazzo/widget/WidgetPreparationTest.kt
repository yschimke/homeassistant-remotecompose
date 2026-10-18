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
import androidx.compose.ui.test.junit4.StateRestorationTester
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
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
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
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    val scale = minOf(1f, 1800f / maxOf(device.displayWidth, device.displayHeight))
    assertTrue(device.takeScreenshot(java.io.File(directory, "$name.png"), scale, 100))
    // AGP may uninstall the test app before the wrapper collects screenshots.
    // MediaStore images survive that cleanup and need no broad storage permission.
    val uri =
      checkNotNull(
        context.contentResolver.insert(
          android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
          android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
              android.provider.MediaStore.Images.Media.RELATIVE_PATH,
              "Pictures/TerrazzoWidgetTests",
            )
          },
        )
      )
    checkNotNull(context.contentResolver.openOutputStream(uri)).use { output ->
      val original =
        checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
      val longest = maxOf(original.width, original.height)
      val bitmap =
        if (longest > 1800) {
          val factor = 1800f / longest
          android.graphics.Bitmap.createScaledBitmap(
              original,
              (original.width * factor).toInt().coerceAtLeast(1),
              (original.height * factor).toInt().coerceAtLeast(1),
              true,
            )
            .also { original.recycle() }
        } else original
      assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
      bitmap.recycle()
    }
    if (name == "launcher-confirmation") {
      val xml = java.io.ByteArrayOutputStream()
      UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).dumpWindowHierarchy(xml)
      xml.toString().chunked(3000).forEach { android.util.Log.i("WidgetJourney", it) }
    }
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
  fun failedPreviewHasRecoveryActionsWithoutAResizeGrid() {
    val requests = java.util.concurrent.ConcurrentLinkedQueue<WidgetSizeDp>()
    val attempts = java.util.concurrent.atomic.AtomicInteger()
    val ready = java.util.concurrent.atomic.AtomicInteger()
    val capture: WidgetPreviewCapture = { ctx, url, cfg, state, size ->
      requests.add(size)
      when (attempts.incrementAndGet()) {
        1 -> null
        // A missing native layout reliably fails RemoteViews.apply. An incomplete drawing
        // buffer can instead be accepted as an empty document by the platform decoder.
        2 -> android.widget.RemoteViews(ctx.packageName, 0)
        else -> TerrazzoWidgetProvider().renderPreview(ctx, url, cfg, state, size)
      }
    }
    compose.setContent {
      MaterialTheme {
        CompositionLocalProvider(LocalWidgetPreviewCapture provides capture) {
          Column {
            WidgetPreparationPreview(DemoData.BASE_URL, card, snapshot("on")) {
              if (it != null) ready.incrementAndGet()
            }
          }
        }
      }
    }
    compose.waitUntil(60_000) {
      compose.onAllNodesWithTag("widget-preview-error").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag("widget-grid-viewport").assertDoesNotExist()
    compose.onNodeWithText("Retry preview").performClick()
    compose.waitUntil(60_000) {
      attempts.get() >= 2 &&
        compose.onAllNodesWithTag("widget-preview-error").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithText("Retry preview").performClick()
    compose.waitUntil(60_000) {
      compose.onAllNodesWithTag("widget-render").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag("widget-preview-error").assertDoesNotExist()
    assertEquals(WidgetSizeDp(72, 84), requests.last())
    assertEquals(
      "Only a successfully inflated native preview may enable installation",
      1,
      ready.get(),
    )
  }

  @Test
  fun adaptiveWidgetPreviewRendersResizesAndTracksState() {
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
    compose.waitUntil(60_000) { captures > 0 }
    compose.onNodeWithText("1 × 1 cells").assertExists()
    compose.onNodeWithText("Compact").assertIsSelected()
    compose.onNodeWithText("Default").assertDoesNotExist()
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
    compose.waitUntil(60_000) {
      captures >= 2 && compose.onAllNodesWithTag("widget-render").fetchSemanticsNodes().isNotEmpty()
    }
    // Publishing a RemoteViews document precedes the platform player's next draw.
    compose.waitUntil(60_000) { pixels() != off }
    assertFalse("a live state change must alter rendered pixels", off == pixels())
    screenshot("preview-on")
    val beforeMedium = captures
    compose.onNodeWithText("Medium").performClick()
    compose.waitUntil(60_000) { captures > beforeMedium }
    compose.onNodeWithText("2 × 1 cells").assertExists()
    val beforeCompact = captures
    compose.onNodeWithText("Compact").performClick()
    compose.waitUntil(60_000) {
      captures > beforeCompact &&
        compose.onAllNodesWithTag("widget-render").fetchSemanticsNodes().isNotEmpty()
    }
    compose.waitForIdle()
    val compact = compose.onNodeWithTag("widget-render").fetchSemanticsNode().size
    fun ink(pixels: List<Int>): Int {
      val background = pixels.groupingBy { it }.eachCount().maxBy { it.value }.key
      return pixels.count { it != background }
    }
    val compactInk = ink(pixels())
    screenshot("preview-compact")
    val beforeRoomy = captures
    compose.onNodeWithText("Roomy").performClick()
    compose.waitUntil(60_000) {
      captures > beforeRoomy &&
        compose.onAllNodesWithTag("widget-render").fetchSemanticsNodes().isNotEmpty()
    }
    compose.waitForIdle()
    val roomy = compose.onNodeWithTag("widget-render").fetchSemanticsNode().size
    assertTrue(
      "size presets must actually resize the widget",
      roomy.width > compact.width || roomy.height > compact.height,
    )
    assertTrue("resized widget must still render content", pixels().distinct().size > 10)
    assertTrue(
      "Roomy must reveal the fuller layout, not just stretch the icon",
      ink(pixels()) > compactInk * 1.2,
    )
    screenshot("preview-roomy")
  }

  @Test
  fun selectedSizeSurvivesLiveDataEditsAndStateRestoration() {
    val restoration = StateRestorationTester(compose)
    val currentCard = mutableStateOf(card)
    val currentSnapshot = mutableStateOf(snapshot("on"))
    val requests = java.util.concurrent.ConcurrentLinkedQueue<WidgetSizeDp>()
    val capture: WidgetPreviewCapture = { ctx, _, _, _, size ->
      requests.add(size)
      android.widget.RemoteViews(
        ctx.packageName,
        ee.schimke.terrazzo.R.layout.terrazzo_widget_initial,
      )
    }
    restoration.setContent {
      MaterialTheme {
        CompositionLocalProvider(LocalWidgetPreviewCapture provides capture) {
          Column {
            WidgetPreparationPreview(DemoData.BASE_URL, currentCard.value, currentSnapshot.value) {}
          }
        }
      }
    }
    compose.waitUntil(60_000) { requests.isNotEmpty() }
    assertEquals(WidgetSizeDp(72, 84), requests.last())
    compose.onNodeWithText("Roomy").performClick()
    compose.waitUntil(60_000) { requests.last() == WidgetSizeDp(144, 168) }
    compose.runOnIdle {
      currentCard.value =
        card.copy(
          raw =
            kotlinx.serialization.json.JsonObject(
              card.raw + ("name" to kotlinx.serialization.json.JsonPrimitive("Renamed"))
            )
        )
      currentSnapshot.value = snapshot("off")
    }
    compose.waitForIdle()
    compose.onNodeWithText("Roomy").assertIsSelected()
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithText("Roomy").assertIsSelected()
    compose.onNodeWithText("2 × 2 cells").assertExists()
  }

  @Test
  fun editPreviewCancelThenConfirmInstallationPersistsEditedCard() {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    assertTrue(
      "The emulator launcher must support widget pinning",
      WidgetInstaller(context).isSupported(),
    )
    // A reset launcher can leave stale rows until the preparation sheet heals them.
    // Compare cancellation with the reconciled baseline, not those removed rows.
    runBlocking { WidgetSync.reconcile(context) }
    val before = runBlocking { graph.widgetStore.installed.first().map { it.widgetId }.toSet() }
    var dismissed = false
    val initialSnapshot = snapshot("on")
    compose.setContent {
      CompositionLocalProvider(LocalTerrazzoGraph provides graph) {
        MaterialTheme {
          WidgetInstallSheet(DemoData.BASE_URL, "", card, initialSnapshot, { dismissed = true })
        }
      }
    }
    compose.onNodeWithText("Widget name").performTextClearance()
    compose.onNodeWithText("Widget name").performTextInput("My magical light")
    compose.onNodeWithText("Widget name").performImeAction()
    compose.onNodeWithText("1 × 1 cells").performScrollTo().assertExists()
    compose.onNodeWithText("Compact").assertIsSelected()
    screenshot("setup-initial-one-cell")
    compose.waitUntil(60_000) {
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
    assertTrue(
      "cancellation must close the launcher confirmation",
      device.wait(
        Until.gone(By.text(java.util.regex.Pattern.compile("(?i).*add to home screen.*"))),
        10_000,
      ),
    )
    assertEquals(
      "cancelled pins must not consume a slot",
      before,
      runBlocking { graph.widgetStore.installed.first().map { it.widgetId }.toSet() },
    )
    assertFalse("cancelling must return to the edited preparation", dismissed)
    compose.onNodeWithText("My magical light").assertExists()
    compose.waitUntil(60_000) {
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
    screenshot("launcher-confirmation")
    val add =
      device.wait(
        Until.findObject(
          By.text(
              java.util.regex.Pattern.compile(
                "(?i)^add(?: automatically| to home screen| widget)?$"
              )
            )
            .clickable(true)
        ),
        30_000,
      )
    assertNotNull("launcher must offer confirmation", add)
    add!!.click()
    compose.waitUntil(60_000) {
      runBlocking { graph.widgetStore.installed.first().any { it.widgetId !in before } }
    }
    val installed = runBlocking {
      graph.widgetStore.installed.first().first { it.widgetId !in before }
    }
    assertEquals("My magical light", installed.card.raw["name"]?.jsonPrimitive?.content)
    device.pressHome()
    device.waitForIdle()
    val hostSelector = By.clazz(java.util.regex.Pattern.compile(".*AppWidgetHostView"))
    if (!device.wait(Until.hasObject(hostSelector), 10_000)) {
      device.swipe(
        device.displayWidth - 30,
        device.displayHeight / 2,
        30,
        device.displayHeight / 2,
        20,
      )
    }
    compose.waitUntil(180_000) {
      val hosts = device.findObjects(hostSelector)
      if (hosts.isEmpty()) false
      else {
        val bitmap =
          checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
          hosts.all { host ->
            val bounds = host.visibleBounds
            bounds.inset(bounds.width() / 8, bounds.height() / 8)
            val p = IntArray(bounds.width() * bounds.height())
            bitmap.getPixels(
              p,
              0,
              bounds.width(),
              bounds.left,
              bounds.top,
              bounds.width(),
              bounds.height(),
            )
            p.distinct().size > 10
          }
        } finally {
          bitmap.recycle()
        }
      }
    }
    screenshot("installed-widget")
    val manager = AppWidgetManager.getInstance(context)
    val info = manager.getAppWidgetInfo(installed.widgetId)
    assertEquals("new launcher widget width", 1, info.targetCellWidth)
    assertEquals("new launcher widget height", 1, info.targetCellHeight)
    val initialSize = WidgetSizing.forWidgetCapture(manager, installed.widgetId)
    assertTrue("initial install must occupy one column: $initialSize", initialSize.widthDp < 130)
    assertTrue("initial install must occupy one row: $initialSize", initialSize.heightDp < 120)
    // Exercise the launcher's actual resize handle, not just a synthetic options bundle.
    val host = device.findObjects(hostSelector).last()
    val hostBounds = host.visibleBounds
    host.longClick()
    device.waitForIdle()
    screenshot("launcher-resize-handles")
    assertTrue(
      device.drag(
        hostBounds.right,
        hostBounds.centerY(),
        (hostBounds.right + hostBounds.width() + 30).coerceAtMost(device.displayWidth - 20),
        hostBounds.centerY(),
        40,
      )
    )
    compose.waitUntil(60_000) {
      WidgetSizing.forWidgetCapture(manager, installed.widgetId).widthDp > initialSize.widthDp
    }
    device.pressBack()
    device.waitForIdle()
    screenshot("installed-widget-resized")
    assertEquals(
      "resizing keeps the saved widget choice",
      installed.card,
      runBlocking { graph.widgetStore.get(installed.widgetId) }!!.card,
    )
    val small =
      Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 72)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 84)
      }
    manager.updateAppWidgetOptions(installed.widgetId, small)
    assertEquals(
      WidgetSizeDp(72, 84),
      WidgetSizing.forWidgetCapture(manager, installed.widgetId),
    )
    val large =
      Bundle(small).apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 144)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 168)
      }
    manager.updateAppWidgetOptions(installed.widgetId, large)
    assertEquals(
      WidgetSizeDp(144, 168),
      WidgetSizing.forWidgetCapture(manager, installed.widgetId),
    )
  }
}
