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
      Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
        view.draw(Canvas(bitmap))
        // Android's player can swallow paint failures and replace the card with a black/red
        // warning surface. These fixtures use a white or dark-grey card background.
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        assertFalse(
          "Native widget must not display the platform rendering-error surface",
          pixels.count { it == android.graphics.Color.BLACK } > pixels.size * 3 / 4 &&
            pixels.any { it == android.graphics.Color.RED },
        )
      }
    }
  }

  @Test
  fun pictureWidgetsEmbedImagesInTemplateAndDirectCapturesAtEverySize() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val file = java.io.File(context.cacheDir, "launcher-picture.png")
    fun writeImage(color: Int) {
      val bitmap = Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888)
      bitmap.eraseColor(color)
      file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
      bitmap.recycle()
    }
    val entityId = "camera.driveway"
    val snapshot =
      HaSnapshot(
        states =
          mapOf(
            entityId to
              EntityState(
                entityId,
                "idle",
                buildJsonObject {
                  put("friendly_name", "Driveway")
                  put("entity_picture", file.toURI().toString())
                },
              )
          )
      )
    val configs =
      listOf(
        // Eligible for the baked template path.
        """{"type":"picture-entity","entity":"camera.driveway","show_name":false,"show_state":false}""",
        // Real bundled camera card shape: these extra keys require direct capture.
        """{"type":"picture-entity","entity":"camera.driveway","camera_image":"camera.driveway","camera_view":"auto","aspect_ratio":"16:9","show_name":false,"show_state":false}""",
        """{"type":"picture-entity","entity":"camera.driveway","tap_action":{"action":"more-info"},"show_name":false,"show_state":false}""",
      )
    fun pixels(card: CardConfig, size: WidgetSizeDp, installed: Boolean): IntArray {
      val views =
        checkNotNull(
          TerrazzoWidgetProvider()
            .renderPreview(
              context,
              DemoData.BASE_URL,
              card,
              snapshot,
              size,
              if (installed) 42 else AppWidgetManager.INVALID_APPWIDGET_ID,
            )
        )
      val width = WidgetSizing.dpToPx(context, size.widthDp)
      val height = WidgetSizing.dpToPx(context, size.heightDp)
      val view = onMain { inflateWidgetPreview(context, views) }
      val bitmap = renderInWindow(view, width, height)
      return IntArray(width * height).also {
        bitmap.getPixels(it, 0, width, 0, 0, width, height)
        bitmap.recycle()
      }
    }
    try {
      for (raw in configs) {
        val json =
          kotlinx.serialization.json.Json.parseToJsonElement(raw)
            as kotlinx.serialization.json.JsonObject
        val card = CardConfig("picture-entity", json)
        for (size in
          listOf(WidgetSizeDp(144, 168), WidgetSizeDp(288, 252), WidgetSizeDp(432, 336))) {
          writeImage(android.graphics.Color.MAGENTA)
          val preview = pixels(card, size, installed = false)
          assertTrue(
            "$raw at $size must display the loaded image",
            preview.count { it == android.graphics.Color.MAGENTA } > preview.size / 2,
          )
          // Same URL, new pixels: a launcher update must bypass stale memory/disk image caches.
          writeImage(android.graphics.Color.CYAN)
          val updated = pixels(card, size, installed = true)
          assertTrue(
            "$raw at $size must refresh an image changed in place",
            updated.count { it == android.graphics.Color.CYAN } > updated.size / 2,
          )
        }
      }
    } finally {
      file.delete()
    }
  }

  @Test
  fun unavailableDirectPictureStillInflatesWithItsNameAndState() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val raw =
      kotlinx.serialization.json.Json.parseToJsonElement(
        """{"type":"picture-entity","entity":"camera.missing","name":"Unavailable camera","aspect_ratio":"16:9"}"""
      ) as kotlinx.serialization.json.JsonObject
    val card = CardConfig("picture-entity", raw)
    val snapshot =
      HaSnapshot(
        states =
          mapOf(
            "camera.missing" to
              EntityState(
                "camera.missing",
                "unavailable",
                buildJsonObject { put("entity_picture", "file:///missing-widget-image.png") },
              )
          )
      )
    val views =
      checkNotNull(
        TerrazzoWidgetProvider()
          .renderPreview(context, DemoData.BASE_URL, card, snapshot, WidgetSizeDp(288, 252))
      )
    val bitmap = renderInWindow(onMain { inflateWidgetPreview(context, views) }, 576, 504)
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    assertTrue(
      "Unavailable pictures must retain useful icon and text content",
      pixels.distinct().size > 10,
    )
    bitmap.recycle()
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
