package ee.schimke.terrazzo.widget

import android.content.ComponentName
import android.content.Context
import android.util.Xml
import ee.schimke.terrazzo.R
import kotlin.math.roundToInt
import org.xmlpull.v1.XmlPullParser

/** One launcher widget for every card: 1x1 initially, resizable up to the launcher's limits. */
internal object LauncherWidgetSpec {
  fun componentName(context: Context): ComponentName =
    ComponentName(context, TerrazzoWidgetProvider::class.java)

  /**
   * Minimum/default cells come from the provider metadata. Without an advertised maximum, the
   * launcher owns the upper limit; the setup sheet uses a representative 4x4 preview extent.
   */
  fun gridBounds(context: Context): LauncherGridBounds =
    readProviderGridBounds(context, R.xml.terrazzo_widget_info)
}

/**
 * Approximate size of one launcher home-screen cell on a typical phone, in dp. Widget slots are an
 * integer number of these cells; the card-history resize preview tiles its grid on this unit.
 * Mirrors the `LauncherCellWidthDp` / `LauncherCellHeightDp` constants the
 * `CardPreviewMatrix` @Preview renders against, so the in-app preview and the tooling matrix
 * quantise to the same grid.
 */
internal const val LAUNCHER_CELL_WIDTH_DP = 72
internal const val LAUNCHER_CELL_HEIGHT_DP = 84

/**
 * The provider's minimum/default and the preview grid's sample extent, in whole cells. The launcher
 * may allow resizing beyond this preview. See [LauncherWidgetSpec.gridBounds].
 */
internal data class LauncherGridBounds(
  val minCellsW: Int,
  val minCellsH: Int,
  val defaultCellsW: Int,
  val defaultCellsH: Int,
  val maxCellsW: Int,
  val maxCellsH: Int,
)

/**
 * Read the `appwidget-provider` resize range out of [xmlRes] and quantise it onto the launcher cell
 * grid. `targetCell*` is already in cells; `min/maxResize*` are dimensions resolved against the
 * device density, then rounded to the nearest whole cell (floor of one). The default is clamped
 * into `[min, max]` so the range is always valid even if the metadata's target falls outside the
 * resize bounds.
 */
private fun readProviderGridBounds(context: Context, xmlRes: Int): LauncherGridBounds {
  val parser = context.resources.getXml(xmlRes)
  // Advance to the single <appwidget-provider> element.
  var event = parser.eventType
  while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) {
    event = parser.next()
  }
  val attrs = Xml.asAttributeSet(parser)
  val density = context.resources.displayMetrics.density

  // One styled-attribute lookup per attr: a single-element array is
  // trivially sorted, so we don't have to order android.R.attr ids.
  fun dimPx(attr: Int): Int {
    val a = context.obtainStyledAttributes(attrs, intArrayOf(attr))
    return try {
      a.getDimensionPixelSize(0, 0)
    } finally {
      a.recycle()
    }
  }
  fun intVal(attr: Int): Int {
    val a = context.obtainStyledAttributes(attrs, intArrayOf(attr))
    return try {
      a.getInt(0, 0)
    } finally {
      a.recycle()
    }
  }

  fun cellsW(px: Int) = ((px / density) / LAUNCHER_CELL_WIDTH_DP).roundToInt().coerceAtLeast(1)
  fun cellsH(px: Int) = ((px / density) / LAUNCHER_CELL_HEIGHT_DP).roundToInt().coerceAtLeast(1)

  // Read every attribute while the parser is still positioned on the
  // start tag (the AttributeSet reads through the live parser), then
  // close it.
  val minW = cellsW(dimPx(android.R.attr.minResizeWidth))
  val minH = cellsH(dimPx(android.R.attr.minResizeHeight))
  val maxWidthPx = dimPx(android.R.attr.maxResizeWidth)
  val maxHeightPx = dimPx(android.R.attr.maxResizeHeight)
  val maxW = (if (maxWidthPx > 0) cellsW(maxWidthPx) else 4).coerceAtLeast(minW)
  val maxH = (if (maxHeightPx > 0) cellsH(maxHeightPx) else 4).coerceAtLeast(minH)
  val defW = intVal(android.R.attr.targetCellWidth).coerceIn(minW, maxW)
  val defH = intVal(android.R.attr.targetCellHeight).coerceIn(minH, maxH)
  parser.close()

  return LauncherGridBounds(
    minCellsW = minW,
    minCellsH = minH,
    defaultCellsW = defW,
    defaultCellsH = defH,
    maxCellsW = maxW,
    maxCellsH = maxH,
  )
}
