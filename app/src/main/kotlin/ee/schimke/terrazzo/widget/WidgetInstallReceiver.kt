package ee.schimke.terrazzo.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ee.schimke.ha.model.CardConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Fired by Android after the user confirms a `requestPinAppWidget`
 * dialog. Android fills in `EXTRA_APPWIDGET_ID`; we persist the
 * mapping from that id → HA card config into [WidgetStore], then
 * nudge the provider to publish the first frame.
 */
class WidgetInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PIN_CONFIRMED) return

        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            Log.w(TAG, "pin callback fired without an appWidgetId")
            return
        }

        val baseUrl = intent.getStringExtra(EXTRA_BASE_URL).orEmpty()
        val cardType = intent.getStringExtra(EXTRA_CARD_TYPE) ?: return
        val cardJson = intent.getStringExtra(EXTRA_CARD_JSON) ?: return
        val card = CardConfig(
            type = cardType,
            raw = json.parseToJsonElement(cardJson) as JsonObject,
        )

        val store = WidgetStore(context.applicationContext)
        runBlocking {
            if (store.isFull()) {
                // Hit the cap between when the user tapped "Add" and
                // when the launcher confirmed. Drop the oldest install
                // rather than refusing silently.
                // TODO: ask the user which install to replace.
                Log.w(TAG, "widget cap hit; keeping existing installs and skipping new id=$widgetId")
                return@runBlocking
            }
            store.put(widgetId, baseUrl, card)
        }

        // Kick the provider so the first frame ships now instead of
        // waiting for the next framework poll.
        val kick = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
            component = android.content.ComponentName(context, TerrazzoWidgetProvider::class.java)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId))
        }
        context.sendBroadcast(kick)
    }

    companion object {
        const val ACTION_PIN_CONFIRMED = "ee.schimke.terrazzo.WIDGET_PIN_CONFIRMED"
        const val EXTRA_BASE_URL = "terrazzo.baseUrl"
        const val EXTRA_CARD_TYPE = "terrazzo.cardType"
        const val EXTRA_CARD_JSON = "terrazzo.cardJson"

        private const val TAG = "WidgetInstallReceiver"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
