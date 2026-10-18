package ee.schimke.terrazzo.widget

import android.content.ComponentName
import android.content.Context
import android.util.Xml
import ee.schimke.ha.rc.WidgetSizeConstraints
import ee.schimke.terrazzo.R
import kotlin.math.roundToInt
import org.xmlpull.v1.XmlPullParser

/**
 * Launcher provider variants retain their component identities and card-specific maximum sizes. All
 * variants start at 1x1 and allow resizing upward in both axes. Provider metadata controls new
 * installations; existing widget IDs, host options and persisted card choices stay intact.
 */
internal enum class WidgetSizeClass(val providerClass: Class<out TerrazzoWidgetProvider>) {
  /**
   * Button-shaped single-entity cards (tile, button, entity): a small, near-square cell that pairs
   * with other small widgets. `@xml/terrazzo_widget_info_small`.
   */
  Small(TerrazzoWidgetProviderSmall::class.java),

  /**
   * Wide list/hero cards with room to grow. Backed by the base [TerrazzoWidgetProvider] so existing
   * installs keep working. `@xml/terrazzo_widget_info`.
   */
  Standard(TerrazzoWidgetProvider::class.java),

  /**
   * Tall list cards (entities/glance with many rows) with a larger vertical resize range.
   * `@xml/terrazzo_widget_info_tall`.
   */
  Tall(TerrazzoWidgetProviderTall::class.java);

  fun componentName(context: Context): ComponentName = ComponentName(context, providerClass)

  /** The `@xml/terrazzo_widget_info*` resource backing this class. */
  private val providerInfoXmlRes: Int
    get() =
      when (this) {
        Small -> R.xml.terrazzo_widget_info_small
        Standard -> R.xml.terrazzo_widget_info
        Tall -> R.xml.terrazzo_widget_info_tall
      }

  /**
   * The launcher resize range for this size class, in whole grid cells — the smallest and largest
   * home-screen slots the widget will occupy, plus the 1x1 default it pins at.
   *
   * Read straight from the matching `@xml/terrazzo_widget_info*` provider metadata — the same
   * `appwidget-provider` element the launcher itself reads — so the card-history resize preview and
   * the real launcher constraints can't drift apart. The `targetCell*` default is already in cells;
   * the `min/maxResize*` dimensions are quantised onto the [LAUNCHER_CELL_WIDTH_DP] ×
   * [LAUNCHER_CELL_HEIGHT_DP] grid (nearest whole cell, floor of one).
   */
  fun gridBounds(context: Context): LauncherGridBounds =
    readProviderGridBounds(context, providerInfoXmlRes)

  companion object {
    /** Natural height (dp) at or above which a Full-width card uses the Tall resize range. */
    private const val TALL_DEFAULT_HEIGHT_DP = 200

    /**
     * Pick the size class for a card's [constraints]. Compact bands (narrow default width) pin
     * [Small]; wide bands pin [Tall] when their default height clears [TALL_DEFAULT_HEIGHT_DP],
     * otherwise [Standard].
     */
    fun forConstraints(constraints: WidgetSizeConstraints): WidgetSizeClass =
      when {
        constraints.defaultWidthDp <= 200 -> Small
        constraints.defaultHeightDp >= TALL_DEFAULT_HEIGHT_DP -> Tall
        else -> Standard
      }
  }
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
 * A [WidgetSizeClass]'s resize range expressed in whole launcher cells: the smallest and largest
 * slots the launcher will let the widget occupy, plus the 1x1 default it pins at. See
 * [WidgetSizeClass.gridBounds].
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
  val maxW = cellsW(dimPx(android.R.attr.maxResizeWidth)).coerceAtLeast(minW)
  val maxH = cellsH(dimPx(android.R.attr.maxResizeHeight)).coerceAtLeast(minH)
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
