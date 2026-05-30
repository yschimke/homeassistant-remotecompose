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
 * component so the launcher offers the right default cell and resize
 * handles.
 *
 * Rendering itself is identical across variants — every provider
 * subclass inherits [TerrazzoWidgetProvider]'s id-driven capture — so
 * adding a class is just a new subclass + XML + manifest receiver, no
 * render logic.
 */
internal enum class WidgetSizeClass(val providerClassName: String) {
    /**
     * Button-shaped single-entity cards (tile, button, entity): a small,
     * near-square cell that pairs with other small widgets.
     * `@xml/terrazzo_widget_info_small`.
     */
    Small("ee.schimke.terrazzo.widget.TerrazzoWidgetProviderSmall"),

    /**
     * The default: wide list/hero cards at a comfortable couple of rows.
     * Backed by the base [TerrazzoWidgetProvider] so existing installs
     * keep working. `@xml/terrazzo_widget_info`.
     */
    Standard("ee.schimke.terrazzo.widget.TerrazzoWidgetProvider"),

    /**
     * Tall list cards (entities/glance with many rows) that want vertical
     * room by default. `@xml/terrazzo_widget_info_tall`.
     */
    Tall("ee.schimke.terrazzo.widget.TerrazzoWidgetProviderTall");

    fun componentName(context: Context): ComponentName =
        ComponentName(context.packageName, providerClassName)

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
