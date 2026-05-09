package ee.schimke.terrazzo.widget

import android.appwidget.AppWidgetManager
import android.os.Bundle
import kotlin.test.Test
import kotlin.test.assertEquals

class WidgetSizingTest {
    @Test
    fun usesMaxWidgetBoundsWhenAvailable() {
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 120)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 360)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 48)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 220)
        }

        val target = WidgetSizing.targetSizeFromOptions(options, cardHeightDp = 96)

        assertEquals(360, target.widthDp)
        assertEquals(220, target.heightDp)
    }

    @Test
    fun staysAtContainerHeightEvenWhenCardWantsMore() {
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 240)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 72)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 90)
        }

        val target = WidgetSizing.targetSizeFromOptions(options, cardHeightDp = 160)

        assertEquals(240, target.widthDp)
        assertEquals(90, target.heightDp)
    }
}
