package ee.schimke.terrazzo.widget

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.core.session.DemoData
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Compare the app's native preview with the framework host used by launchers. */
@RunWith(AndroidJUnit4::class)
class WidgetPreviewParityTest {
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
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
          val width = WidgetSizing.dpToPx(context, size.widthDp)
          val height = WidgetSizing.dpToPx(context, size.heightDp)
          val preview = inflateWidgetPreview(context, previewViews)
          val host =
            AppWidgetHostView(context).apply {
              setAppWidget(42, providerInfo)
              updateAppWidget(installedViews)
            }
          fun render(view: View, w: Int, h: Int): Bitmap {
            view.measure(
              View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
              View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, w, h)
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
          }
          val expected = render(preview, width, height)
          // Compare the same content canvas. Each launcher chooses its own outer cell padding.
          val outer =
            render(
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
}
