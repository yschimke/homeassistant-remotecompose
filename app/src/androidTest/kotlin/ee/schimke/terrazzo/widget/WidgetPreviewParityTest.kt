package ee.schimke.terrazzo.widget

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.core.session.DemoData
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Compare the app's native preview with the framework host used by launchers. */
@RunWith(AndroidJUnit4::class)
class WidgetPreviewParityTest {
  @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
  private lateinit var container: FrameLayout

  @Before
  fun attachNativeHost() {
    activity.scenario.onActivity {
      container = FrameLayout(it)
      it.setContentView(container)
    }
  }

  private fun <T> onMain(block: () -> T): T {
    var result: Result<T>? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
    return checkNotNull(result).getOrThrow()
  }

  private fun renderInWindow(view: View, width: Int, height: Int): Bitmap {
    // Native RemoteCompose needs a real window and frame lifecycle to measure its document.
    // Drawing a detached view can produce only the background and hide content regressions.
    val rendered = java.util.concurrent.CountDownLatch(1)
    onMain {
      container.removeAllViews()
      container.addView(view, FrameLayout.LayoutParams(width, height))
      view.postOnAnimation { view.postOnAnimation { rendered.countDown() } }
    }
    assertTrue(
      "Native widget must receive frames in its window",
      rendered.await(30, java.util.concurrent.TimeUnit.SECONDS),
    )
    return onMain {
      fun diagnose(node: View) {
        if (node.javaClass.name.endsWith("RemoteComposeCanvas")) {
          fun field(name: String): Any? =
            node.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(node)
          if (field("mDisable") == true) {
            val document = checkNotNull(field("mDocument"))
            try {
              document.javaClass.methods
                .first { it.name == "paint" }
                .invoke(document, field("mARContext"), field("mTheme"))
            } catch (failure: java.lang.reflect.InvocationTargetException) {
              throw AssertionError("Native document paint failed", failure.targetException)
            }
            throw AssertionError("Native renderer disabled the document after a paint failure")
          }
        }
        if (node is android.view.ViewGroup)
          for (i in 0 until node.childCount) diagnose(node.getChildAt(i))
      }
      diagnose(view)
      Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }
  }

  @Test
  fun multiEntityWidgetsKeepAndUpdateTheFifthEntity() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val entities = (1..5).map { "sensor.room_$it" }
    for (type in listOf("glance", "entities")) {
      val raw =
        kotlinx.serialization.json.Json.parseToJsonElement(
          """{"type":"$type","entities":[${entities.joinToString { "\"$it\"" }}]}"""
        ) as kotlinx.serialization.json.JsonObject
      val card = CardConfig(type, raw)
      fun pixels(lastValue: String): IntArray {
        val snapshot =
          HaSnapshot(
            states =
              entities.associateWith {
                EntityState(it, if (it == entities.last()) lastValue else "12")
              }
          )
        val views =
          checkNotNull(
            TerrazzoWidgetProvider()
              .renderPreview(
                context,
                DemoData.BASE_URL,
                card,
                snapshot,
                WidgetSizeDp(288, 336),
              )
          )
        val w = WidgetSizing.dpToPx(context, 288)
        val h = WidgetSizing.dpToPx(context, 336)
        val pixels = IntArray(w * h)
        val preview = onMain { inflateWidgetPreview(context, views) }
        val bitmap = renderInWindow(preview, w, h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        java.io.File(context.getExternalFilesDir(null), "$type-$lastValue.png").outputStream().use {
          bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        return pixels
      }
      assertFalse(
        "$type must display changes after its third entity",
        pixels("12").contentEquals(pixels("89")),
      )
    }
  }

  @Test
  fun boilerCardVariantsRenderOnThePlatform() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val snapshot =
      HaSnapshot(
        states =
          mapOf(
            "switch.boiler" to EntityState("switch.boiler", "on"),
            "sensor.boiler_power" to EntityState("sensor.boiler_power", "23.9"),
          )
      )
    val configs =
      listOf(
        """{"type":"tile","entity":"switch.boiler","name":"Boiler"}""",
        """{"type":"entity","entity":"switch.boiler","name":"Boiler"}""",
        """{"type":"entities","entities":[{"entity":"switch.boiler","name":"Boiler"},{"entity":"sensor.boiler_power","name":"Power draw"}]}""",
        """{"type":"history-graph","title":"Boiler power & status (48h)","entities":["sensor.boiler_power","switch.boiler"]}""",
        """{"type":"vertical-stack","cards":[{"type":"tile","entity":"switch.boiler","name":"Boiler"},{"type":"tile","entity":"sensor.boiler_power","name":"Power draw"}]}""",
        """{"type":"vertical-stack","cards":[{"type":"tile","entity":"switch.boiler","name":"Boiler"},{"type":"tile","entity":"sensor.boiler_power","name":"Power draw"},{"type":"history-graph","title":"Boiler power & status","entities":["sensor.boiler_power","switch.boiler"]}]}""",
        """{"type":"markdown","content":"## Boiler\n**Boiler** is on. Power draw: **23.9 W**"}""",
        """{"type":"glance","entities":["switch.boiler","sensor.boiler_power"]}""",
        """{"type":"grid","columns":2,"cards":[{"type":"tile","entity":"switch.boiler","name":"Boiler"},{"type":"tile","entity":"sensor.boiler_power","name":"Power draw"}]}""",
      )
    configs.forEach { raw ->
      val json =
        kotlinx.serialization.json.Json.parseToJsonElement(raw)
          as kotlinx.serialization.json.JsonObject
      val card = CardConfig(json["type"]!!.toString().trim('"'), json)
      val views =
        checkNotNull(
          TerrazzoWidgetProvider()
            .renderPreview(context, DemoData.BASE_URL, card, snapshot, WidgetSizeDp(288, 252))
        ) {
          "${card.type} capture failed"
        }
      val preview = onMain {
        try {
          inflateWidgetPreview(context, views)
        } catch (failure: RuntimeException) {
          throw AssertionError("${card.type} must inflate on the launcher platform", failure)
        }
      }
      val w = WidgetSizing.dpToPx(context, 288)
      val h = WidgetSizing.dpToPx(context, 252)
      val bitmap = renderInWindow(preview, w, h)
      val pixels = IntArray(w * h)
      bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
      val interiorColors = mutableSetOf<Int>()
      for (y in h / 10 until h * 9 / 10) {
        for (x in w / 10 until w * 9 / 10) interiorColors.add(pixels[y * w + x])
      }
      assertTrue("${card.type} must draw content inside its border", interiorColors.size > 10)
      bitmap.recycle()
    }
  }

  @Test
  fun nativePreviewMatchesLauncherContentForSizeAndState() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val providerInfo =
      checkNotNull(
        AppWidgetManager.getInstance(context).getInstalledProviders().find {
          it.provider == ComponentName(context, TerrazzoWidgetProvider::class.java)
        }
      )
    val card =
      CardConfig(
        "tile",
        buildJsonObject {
          put("type", "tile")
          put("entity", "light.garage_door_garage_door_light")
          put("name", "Studio light")
        },
      )
    for (size in listOf(WidgetSizeDp(72, 84), WidgetSizeDp(144, 84), WidgetSizeDp(144, 168))) {
      for (state in listOf("off", "on")) {
        val snapshot =
          HaSnapshot(
            states =
              mapOf(
                "light.garage_door_garage_door_light" to
                  EntityState("light.garage_door_garage_door_light", state)
              )
          )
        val provider = TerrazzoWidgetProvider()
        val previewViews =
          checkNotNull(provider.renderPreview(context, DemoData.BASE_URL, card, snapshot, size))
        // Real widget documents include click PendingIntents; previews must remain read-only.
        val installedViews =
          checkNotNull(provider.renderPreview(context, DemoData.BASE_URL, card, snapshot, size, 42))
        val width = WidgetSizing.dpToPx(context, size.widthDp)
        val height = WidgetSizing.dpToPx(context, size.heightDp)
        val (preview, host) =
          onMain {
            inflateWidgetPreview(context, previewViews) to
              AppWidgetHostView(context).apply {
                setAppWidget(42, providerInfo)
                updateAppWidget(installedViews)
              }
          }
        val expected = renderInWindow(preview, width, height)
        // Compare the same content canvas. Each launcher chooses its own outer cell padding.
        val outer =
          renderInWindow(
            host,
            width + host.paddingLeft + host.paddingRight,
            height + host.paddingTop + host.paddingBottom,
          )
        val actual = Bitmap.createBitmap(outer, host.paddingLeft, host.paddingTop, width, height)
        val expectedPixels = IntArray(width * height)
        val actualPixels = IntArray(width * height)
        expected.getPixels(expectedPixels, 0, width, 0, 0, width, height)
        actual.getPixels(actualPixels, 0, width, 0, 0, width, height)
        assertTrue("$size / $state must render real content", expectedPixels.distinct().size > 10)
        assertArrayEquals(
          "$size / $state: app preview must match launcher content",
          expectedPixels,
          actualPixels,
        )
        expected.recycle()
        actual.recycle()
        if (actual !== outer) outer.recycle()
      }
    }
  }
}
