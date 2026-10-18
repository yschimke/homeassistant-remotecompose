package ee.schimke.terrazzo.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.core.session.DemoData
import ee.schimke.terrazzo.terrazzoGraph
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Reuse the launcher's framework host after first draw, feeding a recapture at each size. */
@RunWith(AndroidJUnit4::class)
class AdaptiveWidgetResizeTest {
  @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)

  private fun <T> onMain(block: () -> T): T {
    var result: Result<T>? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
    return checkNotNull(result).getOrThrow()
  }

  /**
   * Capture the actual hardware-rendered frame, after layout and GPU frame commitment. A software
   * View.draw canvas can paint a different rounded edge or omit native text.
   */
  private fun captureWindow(
    container: FrameLayout,
    target: AppWidgetHostView,
    w: Int,
    h: Int,
  ): Bitmap {
    check(w in 1..1800 && h in 1..1800)
    val committed = CountDownLatch(1)
    onMain {
      // Keep the player under test attached across updates/resizes. The fresh reference
      // is a separate child; hiding it must not recreate the reused player's lifecycle.
      if (target.parent == null) container.addView(target)
      for (index in 0 until container.childCount) {
        val child = container.getChildAt(index)
        child.visibility =
          if (child === target) android.view.View.VISIBLE else android.view.View.INVISIBLE
      }
      target.layoutParams =
        FrameLayout.LayoutParams(
          w + target.paddingLeft + target.paddingRight,
          h + target.paddingTop + target.paddingBottom,
        )
      assertTrue("Fixture requires a hardware-rendered window", target.isHardwareAccelerated)
      target.viewTreeObserver.registerFrameCommitCallback {
        // Native RemoteCompose initializes its paint context during the first draw. Commit
        // another frame as well, including any layout requested by that initialization.
        target.post {
          target.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
          target.invalidate()
        }
      }
      target.invalidate()
    }
    assertTrue("Widget must commit its frame", committed.await(30, TimeUnit.SECONDS))
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val copied = CountDownLatch(1)
    var status = PixelCopy.ERROR_UNKNOWN
    onMain {
      val location = IntArray(2)
      target.getLocationInWindow(location)
      val left = location[0] + target.paddingLeft
      val top = location[1] + target.paddingTop
      activity.scenario.onActivity { owner ->
        PixelCopy.request(
          owner.window,
          Rect(left, top, left + w, top + h),
          bitmap,
          {
            status = it
            copied.countDown()
          },
          Handler(Looper.getMainLooper()),
        )
      }
    }
    assertTrue("Widget pixels must be copied", copied.await(30, TimeUnit.SECONDS))
    org.junit.Assert.assertEquals("Window capture must succeed", PixelCopy.SUCCESS, status)
    return bitmap
  }

  private fun pixels(bitmap: Bitmap): IntArray =
    IntArray(bitmap.width * bitmap.height).also {
      bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

  private fun assertContent(bitmap: Bitmap, label: String) {
    // Exclude the rounded frame: its antialiased corners alone contain >10 colors.
    val interior = mutableSetOf<Int>()
    for (y in bitmap.height / 5 until bitmap.height * 4 / 5) {
      for (x in bitmap.width / 5 until bitmap.width * 4 / 5) interior.add(bitmap.getPixel(x, y))
    }
    assertTrue("$label must contain text inside its frame", interior.size > 10)
  }

  private fun save(context: Context, name: String, bitmap: Bitmap) {
    val uri =
      checkNotNull(
        context.contentResolver.insert(
          android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
          android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "adaptive-$name.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
              android.provider.MediaStore.Images.Media.RELATIVE_PATH,
              "Pictures/TerrazzoWidgetTests",
            )
          },
        )
      )
    checkNotNull(context.contentResolver.openOutputStream(uri)).use {
      assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
    }
  }

  private fun optionsFor(size: WidgetSizeDp) =
    android.os.Bundle().apply {
      putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, size.widthDp)
      putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, size.widthDp)
      putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, size.heightDp)
      putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, size.heightDp)
    }

  private fun awaitProviderBroadcasts() {
    // Options updates run through goAsync. A host callback can be an older initial update;
    // wait for the system's broadcast completion barrier before inspecting the resized frame.
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val descriptor =
      automation.executeShellCommand(
        "timeout 30s am wait-for-broadcast-idle --flush-broadcast-loopers"
      )
    val output =
      android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use {
        it.readText()
      }
    assertTrue(
      "Provider broadcasts must finish: $output",
      output.lineSequence().any { it.trim() == "All broadcast queues are idle!" },
    )
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
  }

  private class ObservedHostView(context: Context) : AppWidgetHostView(context) {
    @Volatile var received: CountDownLatch? = null
    @Volatile var hasDocument: Boolean = false

    override fun updateAppWidget(remoteViews: RemoteViews?) {
      super.updateAppWidget(remoteViews)
      if (remoteViews != null) {
        hasDocument = true
        received?.countDown()
      }
    }
  }

  /** A registered widget, options-change broadcast, provider recapture and framework delivery. */
  @Test
  fun installedWidgetRecapturesWhenLauncherOptionsChange() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val manager = AppWidgetManager.getInstance(context)
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val appHost = onMain {
      object : AppWidgetHost(context, 328) {
        override fun onCreateView(
          context: Context,
          appWidgetId: Int,
          appWidget: AppWidgetProviderInfo,
        ): AppWidgetHostView = ObservedHostView(context)
      }
    }
    val id = appHost.allocateAppWidgetId()
    try {
      val card =
        CardConfig(
          "entity",
          buildJsonObject {
            put("type", "entity")
            // An absent fixture entity gives a stable Unknown state; demo sensor values drift.
            put("entity", "sensor.adaptive_resize_fixture")
            put("name", "Options resize fixture")
          },
        )
      runBlocking { context.terrazzoGraph().widgetStore.put(id, DemoData.BASE_URL, card) }
      val initialSize = WidgetSizeDp(180, 90)
      automation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
      try {
        assertTrue(
          "Fixture widget must bind",
          manager.bindAppWidgetIdIfAllowed(
            id,
            ComponentName(context, TerrazzoWidgetProvider::class.java),
            optionsFor(initialSize),
          ),
        )
      } finally {
        automation.dropShellPermissionIdentity()
      }
      awaitProviderBroadcasts()

      lateinit var view: ObservedHostView
      lateinit var container: FrameLayout
      val firstDocument = CountDownLatch(1)
      activity.scenario.onActivity {
        container = FrameLayout(it)
        it.setContentView(container)
        view = appHost.createView(it, id, manager.getAppWidgetInfo(id)) as ObservedHostView
        // Wait for the real provider's first document before attachment and the first draw.
        // Cached or newly delivered initial RemoteViews are both valid.
        view.received = firstDocument
        if (view.hasDocument) firstDocument.countDown()
        appHost.startListening()
      }
      assertTrue("Initial provider document must arrive", firstDocument.await(30, TimeUnit.SECONDS))
      val initial =
        captureWindow(
          container,
          view,
          WidgetSizing.dpToPx(context, initialSize.widthDp),
          WidgetSizing.dpToPx(context, initialSize.heightDp),
        )
      save(context, "options-initial", initial)
      assertContent(initial, "initial registered widget")
      initial.recycle()
      for (size in listOf(WidgetSizeDp(144, 168), WidgetSizeDp(288, 252), WidgetSizeDp(72, 84))) {
        val w = WidgetSizing.dpToPx(context, size.widthDp)
        val h = WidgetSizing.dpToPx(context, size.heightDp)
        // A launcher lays out the resized cell before asking the provider for new bytes.
        // Retain the old-document frame separately; recapture is the sizing workaround.
        val before = captureWindow(container, view, w, h)
        save(context, "options-${size.widthDp}-before-recapture", before)
        before.recycle()
        val received = CountDownLatch(1)
        view.received = received
        manager.updateAppWidgetOptions(id, optionsFor(size))
        awaitProviderBroadcasts()
        assertTrue(
          "$size: options callback must publish a new document",
          received.await(30, TimeUnit.SECONDS),
        )
        val actual = captureWindow(container, view, w, h)
        save(context, "options-${size.widthDp}-actual", actual)
        assertContent(actual, "$size after recapture")
        val expectedViews =
          checkNotNull(
            TerrazzoWidgetProvider()
              .renderPreview(context, DemoData.BASE_URL, card, DemoData.snapshot(), size, id)
          )
        val fresh = onMain {
          AppWidgetHostView(view.context).apply {
            setAppWidget(id, manager.getAppWidgetInfo(id))
            updateAppWidget(expectedViews)
          }
        }
        val expected = captureWindow(container, fresh, w, h)
        save(context, "options-${size.widthDp}-expected", expected)
        assertArrayEquals(
          "$size: callback capture must match a new capture at the requested size",
          pixels(expected),
          pixels(actual),
        )
        actual.recycle()
        expected.recycle()
      }
    } finally {
      onMain { appHost.stopListening() }
      appHost.deleteAppWidgetId(id)
      runBlocking { context.terrazzoGraph().widgetStore.remove(id) }
    }
  }

  @Test
  fun recapturedDocumentUpdatesTheSameLauncherHostAfterGrowthAndShrink() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val info =
      AppWidgetManager.getInstance(context).installedProviders.single {
        it.provider == ComponentName(context, TerrazzoWidgetProvider::class.java)
      }
    lateinit var container: FrameLayout
    lateinit var host: AppWidgetHostView
    activity.scenario.onActivity {
      container = FrameLayout(it)
      it.setContentView(container)
      // Match AppWidgetHost.createView/fresh reference initialization: apply the first
      // document before attachment, then reuse this attached view for every later update.
      host = AppWidgetHostView(it).apply { setAppWidget(42, info) }
    }
    val card =
      CardConfig(
        "entity",
        buildJsonObject {
          put("type", "entity")
          put("entity", "sensor.resize")
          put("name", "Resize fixture")
        },
      )
    val provider = TerrazzoWidgetProvider()
    for ((index, size) in
      listOf(WidgetSizeDp(144, 168), WidgetSizeDp(288, 252), WidgetSizeDp(72, 84)).withIndex()) {
      val snapshot =
        HaSnapshot(states = mapOf("sensor.resize" to EntityState("sensor.resize", "${index + 1}")))
      // Same capture used by updateWidget after onAppWidgetOptionsChanged. No default/min-size
      // policy is asserted here; those belong to the widget sizing tests.
      val document =
        checkNotNull(provider.renderPreview(context, DemoData.BASE_URL, card, snapshot, size, 42))
      val w = WidgetSizing.dpToPx(context, size.widthDp)
      val h = WidgetSizing.dpToPx(context, size.heightDp)
      if (index > 0) {
        val before = captureWindow(container, host, w, h)
        save(context, "recapture-$index-before-update", before)
        before.recycle()
      }
      onMain { host.updateAppWidget(document) }
      val actual = captureWindow(container, host, w, h)
      save(context, "recapture-$index-actual", actual)
      val fresh = onMain {
        AppWidgetHostView(host.context).apply {
          setAppWidget(42, info)
          updateAppWidget(document)
        }
      }
      val expected = captureWindow(container, fresh, w, h)
      val actualPixels = pixels(actual)
      save(context, "recapture-$index-expected", expected)
      assertContent(actual, "$size after resize")
      assertArrayEquals(
        "$size: reused host must match a fresh host",
        pixels(expected),
        actualPixels,
      )
      actual.recycle()
      expected.recycle()
    }
  }
}
