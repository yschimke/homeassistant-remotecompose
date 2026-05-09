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
    ): WidgetSizeDp {
        val options = appWidgetManager.getAppWidgetOptions(widgetId)
        val minWidth = options.getDp(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, DEFAULT_WIDTH_DP)
        val maxHeight = options.getDp(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, cardHeightDp)
        val targetHeight = cardHeightDp.coerceIn(DEFAULT_HEIGHT_DP, maxHeight.coerceAtLeast(DEFAULT_HEIGHT_DP))
        return WidgetSizeDp(widthDp = minWidth, heightDp = targetHeight)
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
