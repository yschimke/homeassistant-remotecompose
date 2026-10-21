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
    compose.onNodeWithText("Preview at 1 × 1 ", substring = true).assertExists()
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
    compose.onNodeWithText("Preview at 2 × 2 ", substring = true).assertExists()
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
    compose.waitUntil(60_000) { requests.last() == WidgetSizeDp(288, 336) }
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
    compose.onNodeWithText("Preview at 4 × 4 ", substring = true).assertExists()
  }

  @Test
  fun editPreviewCancelThenConfirmInstallationPersistsEditedCard() {
    installJourney(card, snapshot("on"), "")
  }

  @Test
  fun pictureUrlOnlyCaptureReproducesTheOriginalPreviewFailure() {
    // Reproduce the old direct capture: no inline strategy and no image fetching. This isolates
    // native host decoding from network access, bitmap size and the Home Assistant connection.
    val legacyCapture: WidgetPreviewCapture = { ctx, _, cfg, state, size ->
      val profile = ee.schimke.ha.rc.widgetsProfile
      val captured = runBlocking {
        androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument(
          context = ctx,
          creationDisplayInfo =
            androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo(
              WidgetSizing.dpToPx(ctx, size.widthDp),
              WidgetSizing.dpToPx(ctx, size.heightDp),
              ctx.resources.configuration.densityDpi,
            ),
          profile = profile,
        ) {
          ee.schimke.ha.rc.LauncherWidgetContent(
            ee.schimke.ha.rc.cards.defaultRegistry(),
            profile,
            false,
            ee.schimke.ha.rc.components.WidgetActionRegistry(),
            cfg,
            state,
            ee.schimke.ha.rc.WidgetCaptureSizeDp(size.widthDp, size.heightDp),
          )
        }
      }
      android.widget.RemoteViews(
        android.widget.RemoteViews.DrawInstructions.Builder(
            listOf(ee.schimke.ha.rc.platformWidgetDocument(captured.bytes))
          )
          .build()
      )
    }
    val json =
      kotlinx.serialization.json.Json.parseToJsonElement(
        """{"type":"picture-entity","entity":"camera.g5_turret_ultra_high_resolution_channel","camera_view":"auto","aspect_ratio":"16:9","name":"Driveway"}"""
      ) as kotlinx.serialization.json.JsonObject
    compose.setContent {
      CompositionLocalProvider(
        LocalTerrazzoGraph provides graph,
        LocalWidgetPreviewCapture provides legacyCapture,
      ) {
        MaterialTheme {
          WidgetInstallSheet(
            DemoData.BASE_URL,
            "",
            CardConfig("picture-entity", json),
            DemoData.snapshot(),
            {},
          )
        }
      }
    }
    compose.waitUntil(60_000) {
      compose.onAllNodesWithTag("widget-preview-error").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithText("Add widget").assertIsNotEnabled()
    screenshot("picture-before-preview-error")
  }

  @Test
  fun picturePreviewAndConfirmedLauncherInstallation() {
    val json =
      kotlinx.serialization.json.Json.parseToJsonElement(
        """{"type":"picture-entity","entity":"camera.g5_turret_ultra_high_resolution_channel","camera_image":"camera.g5_turret_ultra_high_resolution_channel","camera_view":"auto","aspect_ratio":"16:9","name":"Driveway"}"""
      ) as kotlinx.serialization.json.JsonObject
    installJourney(CardConfig("picture-entity", json), DemoData.snapshot(), "picture-")
  }

  private fun installJourney(
    card: CardConfig,
    initialSnapshot: HaSnapshot,
    evidencePrefix: String,
  ) {
    val editedName = if (evidencePrefix.isEmpty()) "My magical light" else "My driveway camera"
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
    compose.setContent {
      CompositionLocalProvider(LocalTerrazzoGraph provides graph) {
        MaterialTheme {
          WidgetInstallSheet(DemoData.BASE_URL, "", card, initialSnapshot, { dismissed = true })
        }
      }
    }
    compose.onNodeWithText("Label (optional)").performTextClearance()
    compose.onNodeWithText("Label (optional)").performTextInput(editedName)
    compose.onNodeWithText("Label (optional)").performImeAction()
    compose.onNodeWithText("Preview at 1 × 1 ", substring = true).performScrollTo().assertExists()
    compose.onNodeWithText("Compact").assertIsSelected()
    compose.waitUntil(60_000) {
      compose.onAllNodes(hasText("Add widget") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
    }
    // Target the grid viewport so performScrollTo finds the sheet's vertical scroller rather
    // than the grid's own horizontal scroller. A visible sliver is insufficient evidence.
    compose.onNodeWithTag("widget-grid-viewport").performScrollTo()
    compose.waitForIdle()
    val previewNode = compose.onNodeWithTag("widget-render").fetchSemanticsNode()
    assertTrue(
      "The complete preview height must be visible in the setup screenshot",
      previewNode.boundsInRoot.height >= previewNode.size.height - 1f,
    )
    // Compare scene content, rather than colour count: a genuine 1×1 camera can have fewer
    // than 1,000 distinct pixels. Use the image region (above the state label) as a reference.
    fun sceneHistogram(
      bitmap: android.graphics.Bitmap,
      bounds: android.graphics.Rect,
    ): DoubleArray {
      val region = android.graphics.Rect(bounds)
      region.inset(region.width() / 8, 0)
      region.bottom = region.top + region.height() * 3 / 5
      val histogram = DoubleArray(512)
      for (y in region.top until region.bottom) {
        for (x in region.left until region.right) {
          val color = bitmap.getPixel(x, y)
          val bin = ((color shr 21) and 7) * 64 + ((color shr 13) and 7) * 8 + ((color shr 5) and 7)
          histogram[bin]++
        }
      }
      val count = histogram.sum()
      return histogram.map { it / count }.toDoubleArray()
    }
    val previewScene =
      if (evidencePrefix.isNotEmpty()) {
        val bounds = compose.onNodeWithTag("widget-render").fetchSemanticsNode().boundsInWindow
        val bitmap =
          checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
          sceneHistogram(
            bitmap,
            android.graphics.Rect(
              bounds.left.toInt(),
              bounds.top.toInt(),
              bounds.right.toInt(),
              bounds.bottom.toInt(),
            ),
          )
        } finally {
          bitmap.recycle()
        }
      } else null
    if (previewScene != null) {
      assertTrue(
        "Preview must contain the camera scene, not a flat placeholder",
        previewScene.count { it > 0.05 } >= 3,
      )
    }
    screenshot("${evidencePrefix}prepared-preview")
    if (evidencePrefix.isEmpty()) screenshot("setup-initial-one-cell")

    compose.onNodeWithText("Add widget").assertIsEnabled().performClick()
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
    compose.onNodeWithText(editedName).assertExists()
    compose.waitUntil(60_000) {
      compose.onAllNodes(hasText("Add widget") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithText("Add widget").assertIsEnabled().performClick()
    assertTrue(
      device.wait(
        Until.hasObject(By.text(java.util.regex.Pattern.compile("(?i).*add to home screen.*"))),
        30_000,
      )
    )
    screenshot("${evidencePrefix}launcher-confirmation")
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
    assertEquals(editedName, installed.card.raw["name"]?.jsonPrimitive?.content)
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
    if (evidencePrefix.isEmpty()) screenshot("installed-widget")
    if (evidencePrefix.isNotEmpty()) {
      fun cameraPixels(): IntArray? {
        val bitmap =
          checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        return try {
          // Accessibility traversal order does not identify the newly pinned widget. Match
          // its camera scene against the successful preview, excluding the state label.
          val reference = checkNotNull(previewScene)
          val host =
            device.findObjects(hostSelector).maxByOrNull { host ->
              val histogram = sceneHistogram(bitmap, host.visibleBounds)
              histogram.indices.sumOf { minOf(histogram[it], reference[it]) }
            }
          host?.let {
            val bounds = it.visibleBounds
            if (bounds.isEmpty) return null
            val histogram = sceneHistogram(bitmap, bounds)
            val overlap = histogram.indices.sumOf { minOf(histogram[it], reference[it]) }
            if (overlap < 0.6) return null
            IntArray(bounds.width() * bounds.height()).also { pixels ->
              bitmap.getPixels(
                pixels,
                0,
                bounds.width(),
                bounds.left,
                bounds.top,
                bounds.width(),
                bounds.height(),
              )
            }
          }
        } finally {
          bitmap.recycle()
        }
      }
      screenshot("picture-installed-before-scene-check")
      compose.waitUntil(60_000) { cameraPixels() != null }
      screenshot("picture-installed-widget")
      val original = checkNotNull(cameraPixels())
      assertNotNull(
        "Installed camera must match the prepared scene, not an icon or warning",
        original,
      )
      val frame = System.currentTimeMillis() / DemoData.CAMERA_FRAME_MILLIS
      compose.waitUntil(20_000) {
        System.currentTimeMillis() / DemoData.CAMERA_FRAME_MILLIS > frame
      }
      // Exercise the actual provider publication path for the launcher-owned id.
      context.sendBroadcast(
        android.content.Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
          component = android.content.ComponentName(context, TerrazzoWidgetProvider::class.java)
          putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(installed.widgetId))
        }
      )
      compose.waitUntil(60_000) {
        cameraPixels()?.let { it.size == original.size && !it.contentEquals(original) } == true
      }
      screenshot("picture-installed-updated")
    }
    val manager = AppWidgetManager.getInstance(context)
    val info = manager.getAppWidgetInfo(installed.widgetId)
    assertEquals("new launcher widget width", 1, info.targetCellWidth)
    assertEquals("new launcher widget height", 1, info.targetCellHeight)
    val initialSize = WidgetSizing.forWidgetCapture(manager, installed.widgetId)
    assertTrue("initial install must occupy one column: $initialSize", initialSize.widthDp < 130)
    assertTrue("initial install must occupy one row: $initialSize", initialSize.heightDp < 120)
    // The original tile journey owns the real launcher resize gesture regression. The camera
    // journey installs another widget beside that expanded tile and checks image updates there.
    if (evidencePrefix.isEmpty()) {
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
          (hostBounds.right + 2 * hostBounds.width() + 30).coerceAtMost(device.displayWidth - 20),
          hostBounds.centerY(),
          40,
        )
      )
      compose.waitUntil(60_000) {
        WidgetSizing.forWidgetCapture(manager, installed.widgetId).widthDp > initialSize.widthDp
      }
      // Grow vertically too, beyond the former Small provider's 2x2 cap.
      val widerBounds = device.findObjects(hostSelector).last().visibleBounds
      assertTrue(
        device.drag(
          widerBounds.centerX(),
          widerBounds.bottom,
          widerBounds.centerX(),
          (widerBounds.bottom + 2 * hostBounds.height() + 30).coerceAtMost(
            device.displayHeight - 120
          ),
          40,
        )
      )
      compose.waitUntil(60_000) {
        val resized = WidgetSizing.forWidgetCapture(manager, installed.widgetId)
        resized.widthDp > 144 && resized.heightDp > 168
      }
      device.pressBack()
      device.waitForIdle()
      screenshot("installed-widget-resized")
    }
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
