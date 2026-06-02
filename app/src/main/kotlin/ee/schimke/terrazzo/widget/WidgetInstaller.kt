package ee.schimke.terrazzo.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cardSizeConstraints
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.cards.shutter.withEnhancedShutter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Ships a Lovelace card to the home screen as a live widget.
 *
 * Flow:
 * 1. App code calls `installer.requestPin(baseUrl, card)`.
 * 2. We build a PendingIntent targeting [WidgetInstallReceiver] with the card config in its extras,
 *    then call `AppWidgetManager.requestPinAppWidget(provider, null, pi)`.
 * 3. Android asks the user to pick a launcher slot.
 * 4. On accept, Android fires the PendingIntent with `EXTRA_APPWIDGET_ID` appended — our receiver
 *    then writes the mapping into [WidgetStore] and triggers an immediate update on
 *    [TerrazzoWidgetProvider].
 *
 * `AppWidgetManager.isRequestPinAppWidgetSupported()` returns false on launchers that don't
 * implement pinning — the caller should gate the "Add to home screen" button behind it.
 */
class WidgetInstaller(private val context: Context) {

  fun isSupported(): Boolean = AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported

  /**
   * Returns true when the request was accepted by Android (not necessarily that the user confirmed
   * — the success signal comes later via [WidgetInstallReceiver]).
   */
  fun requestPin(baseUrl: String, card: CardConfig): Boolean {
    val appWidgetManager = AppWidgetManager.getInstance(context)
    if (!appWidgetManager.isRequestPinAppWidgetSupported) return false

    // Pick the provider variant whose appwidget-provider metadata
    // matches the card's supported-size band, so the launcher offers
    // the right default cell + resize bounds. Rendering is identical
    // across variants (all inherit TerrazzoWidgetProvider). The
    // snapshot only feeds payload-dependent sizing (e.g. entities
    // row count comes from card.raw), so an empty one is fine here.
    val registry = defaultRegistry().withEnhancedShutter()
    val constraints = registry.cardSizeConstraints(card, HaSnapshot())
    val provider = WidgetSizeClass.forConstraints(constraints).componentName(context)

    val callback =
      Intent(context, WidgetInstallReceiver::class.java).apply {
        action = WidgetInstallReceiver.ACTION_PIN_CONFIRMED
        putExtra(WidgetInstallReceiver.EXTRA_BASE_URL, baseUrl)
        putExtra(WidgetInstallReceiver.EXTRA_CARD_TYPE, card.type)
        putExtra(
          WidgetInstallReceiver.EXTRA_CARD_JSON,
          json.encodeToString(JsonObject.serializer(), card.raw),
        )
      }
    val pendingIntent =
      PendingIntent.getBroadcast(
        context,
        /* requestCode = */ card.hashCode(),
        callback,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
      )
    return appWidgetManager.requestPinAppWidget(provider, /* extras= */ null, pendingIntent)
  }

  private companion object {
    val json = Json { ignoreUnknownKeys = true }
  }
}
