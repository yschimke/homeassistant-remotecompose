package ee.schimke.terrazzo.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
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

  private class ObservedHostView(context: Context) : AppWidgetHostView(context) {
    @Volatile var received: CountDownLatch? = null

    override fun updateAppWidget(remoteViews: RemoteViews?) {
      super.updateAppWidget(remoteViews)
      if (remoteViews != null) {
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
      automation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
      try {
        assertTrue(
          "Fixture widget must bind",
          manager.bindAppWidgetIdIfAllowed(
            id,
            ComponentName(context, TerrazzoWidgetProvider::class.java),
          ),
        )
      } finally {
        automation.dropShellPermissionIdentity()
      }
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
      lateinit var view: ObservedHostView
      lateinit var container: FrameLayout
      activity.scenario.onActivity {
        container = FrameLayout(it)
        it.setContentView(container)
        view = appHost.createView(it, id, manager.getAppWidgetInfo(id)) as ObservedHostView
        container.addView(view)
        appHost.startListening()
      }
      for (size in listOf(WidgetSizeDp(144, 168), WidgetSizeDp(288, 252), WidgetSizeDp(72, 84))) {
        val received = CountDownLatch(1)
        view.received = received
        manager.updateAppWidgetOptions(
          id,
          android.os.Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, size.widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, size.widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, size.heightDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, size.heightDp)
          },
        )
        assertTrue(
          "$size: options callback must publish a new document",
          received.await(30, TimeUnit.SECONDS),
        )
        val w = WidgetSizing.dpToPx(context, size.widthDp)
        val h = WidgetSizing.dpToPx(context, size.heightDp)
        fun draw(target: AppWidgetHostView): IntArray {
          val ready = CountDownLatch(1)
          onMain {
            container.removeAllViews()
            container.addView(
              target,
              FrameLayout.LayoutParams(
                w + target.paddingLeft + target.paddingRight,
                h + target.paddingTop + target.paddingBottom,
              ),
            )
            target.postOnAnimation { target.postOnAnimation { ready.countDown() } }
          }
          assertTrue("$size must draw after delivery", ready.await(30, TimeUnit.SECONDS))
          return onMain {
            val bitmap = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
            target.draw(Canvas(bitmap))
            IntArray(w * h).also {
              bitmap.getPixels(it, 0, w, target.paddingLeft, target.paddingTop, w, h)
              bitmap.recycle()
            }
          }
        }
        val actual = draw(view)
        assertTrue("$size must display content after recapture", actual.distinct().size > 10)
        val expectedViews =
          checkNotNull(
            TerrazzoWidgetProvider()
              .renderPreview(context, DemoData.BASE_URL, card, DemoData.snapshot(), size, id)
          )
        val fresh = onMain {
          AppWidgetHostView(context).apply {
            setAppWidget(id, manager.getAppWidgetInfo(id))
            updateAppWidget(expectedViews)
          }
        }
        assertArrayEquals(
          "$size: callback capture must match a new capture at the requested size",
          draw(fresh),
          actual,
        )
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
      host = AppWidgetHostView(it).apply { setAppWidget(42, info) }
      container.addView(host)
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
      fun draw(target: AppWidgetHostView): Bitmap {
        val ready = CountDownLatch(1)
        onMain {
          container.removeAllViews()
          container.addView(
            target,
            FrameLayout.LayoutParams(
              w + target.paddingLeft + target.paddingRight,
              h + target.paddingTop + target.paddingBottom,
            ),
          )
          target.postOnAnimation { target.postOnAnimation { ready.countDown() } }
        }
        assertTrue("Launcher host received frames", ready.await(30, TimeUnit.SECONDS))
        return onMain {
          val outer = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
          target.draw(Canvas(outer))
          Bitmap.createBitmap(outer, target.paddingLeft, target.paddingTop, w, h).also {
            if (it !== outer) outer.recycle()
          }
        }
      }
      onMain { host.updateAppWidget(document) }
      val actual = draw(host)
      val fresh = onMain {
        AppWidgetHostView(context).apply {
          setAppWidget(42, info)
          updateAppWidget(document)
        }
      }
      val expected = draw(fresh)
      fun pixels(bitmap: Bitmap) = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
      val actualPixels = pixels(actual)
      assertTrue(
        "$size must contain visible text rather than a blank/error surface",
        actualPixels.distinct().size > 10,
      )
      val uri =
        checkNotNull(
          context.contentResolver.insert(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            android.content.ContentValues().apply {
              put(
                android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                "adaptive-resize-$index.png",
              )
              put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
              put(
                android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                "Pictures/TerrazzoWidgetTests",
              )
            },
          )
        )
      check(maxOf(actual.width, actual.height) <= 1800)
      checkNotNull(context.contentResolver.openOutputStream(uri)).use {
        assertTrue(actual.compress(Bitmap.CompressFormat.PNG, 100, it))
      }
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
