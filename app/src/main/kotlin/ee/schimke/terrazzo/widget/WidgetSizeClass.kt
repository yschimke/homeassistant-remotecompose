package ee.schimke.terrazzo.widget

import android.content.ComponentName
import android.content.Context
import ee.schimke.ha.rc.WidgetSizeConstraints

/**
 * Quantises a card's continuous [WidgetSizeConstraints] band onto the
 * small, fixed set of launcher provider variants we ship.
 *
 * The launcher learns a widget's default cell and resize limits from
 * static `appwidget-provider` metadata, which is per-provider — a
 * single provider can only advertise one band. So instead of one
 * provider, each size class is backed by its own [AppWidgetProvider]
 * subclass + `appwidget-provider` XML (see [providerClass] and the
 * `@xml/terrazzo_widget_info*` resources). [WidgetInstaller] reads the
 * card's [WidgetSizeConstraints], maps it here, and pins the matching
 * component.
 *
 * Each class is an **opinionated range** — a `targetCell*` default plus
 * `min/maxResize*` bounds — so the launcher offers a sensible default
 * cell and won't let the card be dragged out to an oversized slot it
 * would only half-fill:
 *
 *   * [Small]    — ~1x1 .. 2x2 (single-entity chips)
 *   * [Standard] — ~2x1 .. 4x3 (default list/hero)
 *   * [Tall]     — ~2x2 .. 4x4 (many-row lists)
 *
 * Rendering itself is identical across variants — every provider
 * subclass inherits [TerrazzoWidgetProvider]'s id-driven capture — so
 * adding a class is just a new subclass + XML + manifest receiver, no
 * render logic.
 */
internal enum class WidgetSizeClass(val providerClass: Class<out TerrazzoWidgetProvider>) {
    /**
     * Button-shaped single-entity cards (tile, button, entity): a small,
     * near-square cell that pairs with other small widgets.
     * `@xml/terrazzo_widget_info_small`.
     */
    Small(TerrazzoWidgetProviderSmall::class.java),

    /**
     * The default: wide list/hero cards at a comfortable couple of rows.
     * Backed by the base [TerrazzoWidgetProvider] so existing installs
     * keep working. `@xml/terrazzo_widget_info`.
     */
    Standard(TerrazzoWidgetProvider::class.java),

    /**
     * Tall list cards (entities/glance with many rows) that want vertical
     * room by default. `@xml/terrazzo_widget_info_tall`.
     */
    Tall(TerrazzoWidgetProviderTall::class.java);

    fun componentName(context: Context): ComponentName =
        ComponentName(context, providerClass)

    /**
     * The launcher resize range for this size class, in whole grid
     * cells — the smallest and largest home-screen slots the widget
     * will occupy, plus the comfortable default it pins at. Quantises
     * the `min/maxResize*` and `targetCell*` values in the matching
     * `@xml/terrazzo_widget_info*` provider metadata onto the
     * [LAUNCHER_CELL_WIDTH_DP] × [LAUNCHER_CELL_HEIGHT_DP] grid, and
     * matches the `~AxB .. CxD` ranges in each constant's KDoc above.
     * The card-history screen draws this range as a resizable preview.
     */
    val gridBounds: LauncherGridBounds
        get() = when (this) {
            Small -> LauncherGridBounds(minCellsW = 1, minCellsH = 1, defaultCellsW = 2, defaultCellsH = 1, maxCellsW = 2, maxCellsH = 2)
            Standard -> LauncherGridBounds(minCellsW = 2, minCellsH = 1, defaultCellsW = 4, defaultCellsH = 2, maxCellsW = 4, maxCellsH = 3)
            Tall -> LauncherGridBounds(minCellsW = 2, minCellsH = 2, defaultCellsW = 4, defaultCellsH = 3, maxCellsW = 4, maxCellsH = 4)
        }

    companion object {
        /** Default height (dp) at or above which a Full-width card pins Tall. */
        private const val TALL_DEFAULT_HEIGHT_DP = 200

        /**
         * Pick the size class for a card's [constraints]. Compact bands
         * (narrow default width) pin [Small]; wide bands pin [Tall] when
         * their default height clears [TALL_DEFAULT_HEIGHT_DP], otherwise
         * [Standard].
         */
        fun forConstraints(constraints: WidgetSizeConstraints): WidgetSizeClass = when {
            constraints.defaultWidthDp <= 200 -> Small
            constraints.defaultHeightDp >= TALL_DEFAULT_HEIGHT_DP -> Tall
            else -> Standard
        }
    }
}

/**
 * Approximate size of one launcher home-screen cell on a typical phone,
 * in dp. Widget slots are an integer number of these cells; the
 * card-history resize preview tiles its grid on this unit. Mirrors the
 * `LauncherCellWidthDp` / `LauncherCellHeightDp` constants the
 * `CardPreviewMatrix` @Preview renders against, so the in-app preview
 * and the tooling matrix quantise to the same grid.
 */
internal const val LAUNCHER_CELL_WIDTH_DP = 72
internal const val LAUNCHER_CELL_HEIGHT_DP = 84

/**
 * A [WidgetSizeClass]'s resize range expressed in whole launcher cells:
 * the smallest and largest slots the launcher will let the widget
 * occupy, plus the comfortable default it pins at. See
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
