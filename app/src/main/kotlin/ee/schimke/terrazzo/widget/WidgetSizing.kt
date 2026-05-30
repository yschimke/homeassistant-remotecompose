package ee.schimke.terrazzo.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import android.util.TypedValue

internal data class WidgetSizeDp(
    val widthDp: Int,
    val heightDp: Int,
)

internal object WidgetSizing {
    const val DEFAULT_WIDTH_DP = 180
    const val DEFAULT_HEIGHT_DP = 48

    fun forPreview(cardHeightDp: Int): WidgetSizeDp =
        WidgetSizeDp(widthDp = DEFAULT_WIDTH_DP, heightDp = cardHeightDp.coerceAtLeast(DEFAULT_HEIGHT_DP))

    /**
     * Size the capture canvas to the launcher cell the user actually
     * dragged out, so the rendered document fills its slot instead of
     * wrapping to content and leaving a blank band.
     *
     * The host reports the current cell as a width/height pair: in
     * portrait it is `MIN_WIDTH × MAX_HEIGHT`, in landscape
     * `MAX_WIDTH × MIN_HEIGHT`. We capture the portrait pair (the common
     * home-screen orientation); the runtime re-measures width against
     * the live canvas anyway via `fillMaxWidth`. [cardHeightDp] is only
     * a fallback for hosts that don't report `MAX_HEIGHT` yet.
     */
    fun forWidgetCapture(
        appWidgetManager: AppWidgetManager,
        widgetId: Int,
        cardHeightDp: Int,
    ): WidgetSizeDp {
        val options = appWidgetManager.getAppWidgetOptions(widgetId)
        val width = options.getDp(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, DEFAULT_WIDTH_DP)
        val height = options
            .getDp(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, cardHeightDp)
            .coerceAtLeast(DEFAULT_HEIGHT_DP)
        return WidgetSizeDp(widthDp = width, heightDp = height)
    }

    fun dpToPx(context: Context, dp: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics,
        ).toInt()

    private fun Bundle.getDp(key: String, fallback: Int): Int =
        getInt(key, fallback).takeIf { it > 0 } ?: fallback
}
