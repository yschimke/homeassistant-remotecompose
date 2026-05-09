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

    fun forWidgetCapture(
        appWidgetManager: AppWidgetManager,
        widgetId: Int,
        cardHeightDp: Int,
    ): WidgetSizeDp =
        targetSizeFromOptions(appWidgetManager.getAppWidgetOptions(widgetId), cardHeightDp)

    internal fun targetSizeFromOptions(options: Bundle, cardHeightDp: Int): WidgetSizeDp {
        val minWidth = options.getDp(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, DEFAULT_WIDTH_DP)
        val maxWidth = options.getDp(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minWidth)
        val minHeight = options.getDp(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, DEFAULT_HEIGHT_DP)
        val maxHeight = options.getDp(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minHeight)

        val targetWidth = maxOf(minWidth, maxWidth)
        val targetHeight = maxOf(minHeight, maxHeight)
        return WidgetSizeDp(widthDp = targetWidth, heightDp = targetHeight)
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
