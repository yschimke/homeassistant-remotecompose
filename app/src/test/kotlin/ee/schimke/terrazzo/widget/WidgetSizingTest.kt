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
  fun everyProviderStartsAtOneCellAndAllowsUpwardResizing() {
    val xmls =
      listOf(
        R.xml.terrazzo_widget_info_small,
        R.xml.terrazzo_widget_info,
        R.xml.terrazzo_widget_info_tall,
      )
    for ((sizeClass, xml) in WidgetSizeClass.entries.zip(xmls)) {
      val bounds = sizeClass.gridBounds(context)
      assertEquals(1, bounds.minCellsW, sizeClass.name)
      assertEquals(1, bounds.minCellsH, sizeClass.name)
      assertEquals(1, bounds.defaultCellsW, sizeClass.name)
      assertEquals(1, bounds.defaultCellsH, sizeClass.name)
      assertTrue(bounds.maxCellsW > 1 && bounds.maxCellsH > 1, sizeClass.name)
      context.resources.getXml(xml).use { parser ->
        while (parser.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) parser.next()
        val attrs =
          context.obtainStyledAttributes(
            android.util.Xml.asAttributeSet(parser),
            intArrayOf(android.R.attr.minWidth),
          )
        try {
          assertTrue(attrs.getDimension(0, 0f) / context.resources.displayMetrics.density <= 50)
        } finally {
          attrs.recycle()
        }
        val height =
          context.obtainStyledAttributes(
            android.util.Xml.asAttributeSet(parser),
            intArrayOf(android.R.attr.minHeight),
          )
        try {
          assertTrue(height.getDimension(0, 0f) / context.resources.displayMetrics.density <= 50)
        } finally {
          height.recycle()
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
