package ee.schimke.terrazzo.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.os.Bundle
import ee.schimke.terrazzo.R
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WidgetSizingTest {
  private val context: Application = RuntimeEnvironment.getApplication()

  @Test
  fun oneProviderStartsAtOneCellAndResizesWithoutAnAppImposedMaximum() {
    val bounds = LauncherWidgetSpec.gridBounds(context)
    assertEquals(LauncherGridBounds(1, 1, 1, 1, 4, 4), bounds)
    val receivers =
      context.packageManager
        .getPackageInfo(
          context.packageName,
          android.content.pm.PackageManager.GET_RECEIVERS or
            android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS,
        )
        .receivers
        .orEmpty()
        .filter { it.name.startsWith(TerrazzoWidgetProvider::class.java.name) }
    assertEquals(listOf(TerrazzoWidgetProvider::class.java.name), receivers.map { it.name })
    assertEquals(
      TerrazzoWidgetProvider::class.java.name,
      LauncherWidgetSpec.componentName(context).className,
    )
    context.resources.getXml(R.xml.terrazzo_widget_info).use { parser ->
      while (parser.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) parser.next()
      val namespace = "http://schemas.android.com/apk/res/android"
      kotlin.test.assertNull(parser.getAttributeValue(namespace, "maxResizeWidth"))
      kotlin.test.assertNull(parser.getAttributeValue(namespace, "maxResizeHeight"))
      for (dimension in listOf(android.R.attr.minWidth, android.R.attr.minHeight)) {
        val attrs =
          context.obtainStyledAttributes(
            android.util.Xml.asAttributeSet(parser),
            intArrayOf(dimension),
          )
        try {
          assertTrue(attrs.getDimension(0, 0f) / context.resources.displayMetrics.density <= 50)
        } finally {
          attrs.recycle()
        }
      }
      val resize =
        context.obtainStyledAttributes(
          android.util.Xml.asAttributeSet(parser),
          intArrayOf(android.R.attr.resizeMode),
        )
      try {
        assertEquals(android.appwidget.AppWidgetProviderInfo.RESIZE_BOTH, resize.getInt(0, 0))
      } finally {
        resize.recycle()
      }
    }
  }

  @Test
  fun absentOrInvalidHostSizesUseOneCellDefaults() {
    assertEquals(WidgetSizeDp(72, 84), WidgetSizing.forOptions(Bundle()))
    assertEquals(WidgetSizeDp(72, 84), WidgetSizing.forOptions(options(0, -1)))
  }

  @Test
  fun installedSizesAndUserResizesAlwaysOverrideDefaults() {
    for (size in
      listOf(
        WidgetSizeDp(50, 50),
        WidgetSizeDp(72, 84),
        WidgetSizeDp(144, 84),
        WidgetSizeDp(288, 252),
        WidgetSizeDp(504, 672),
      )) {
      val options =
        options(size.widthDp, size.heightDp).apply {
          // Use the portrait pair, not landscape width/height from the same options bundle.
          putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 400)
          putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 40)
        }
      assertEquals(size, WidgetSizing.forOptions(options))
      assertEquals(size.widthDp, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH))
      assertEquals(size.heightDp, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT))
    }
  }

  private fun options(width: Int, height: Int) =
    Bundle().apply {
      putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
      putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
    }
}
