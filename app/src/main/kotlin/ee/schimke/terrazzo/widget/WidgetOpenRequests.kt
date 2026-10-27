package ee.schimke.terrazzo.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.WidgetOpenApp
import ee.schimke.terrazzo.MainActivity
import ee.schimke.terrazzo.core.widget.WidgetStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where a launcher widget's tap asked the app to open: the widget's own card on its dashboard, the
 * entity's details, or a dashboard path (`/lovelace/kitchen`). The tap launches the app with the
 * target as an extra ([launchIntent]); the activity hands it here ([offer]) on a cold start and on
 * a new intent, and the dashboard screen opens it once ([consume]), as the card's own tap would in
 * the app.
 *
 * A tap on a widget made from a dashboard card ([WidgetStore.Origin]) also carries that card
 * ([Target.card]): the app switches to its dashboard, opens its section and scrolls it on screen.
 */
internal object WidgetOpenRequests {

  /** A widget's card on the dashboard it was made on, in the instance at [baseUrl]. */
  data class CardRef(val baseUrl: String, val dashboardUrlPath: String?, val card: CardConfig)

  sealed interface Target {
    /** The widget's card to show behind the target, if it came from one. */
    val card: CardRef?

    data class MoreInfo(val entityId: String, override val card: CardRef? = null) : Target

    data class Navigate(val path: String) : Target {
      // A navigation goes elsewhere; the widget's card has nothing to add to it.
      override val card: CardRef?
        get() = null
    }

    /** A tap on the widget itself: the app on the widget's card. */
    data class ShowCard(override val card: CardRef) : Target

    /** This target without its card: for a card the app can't show. Null when nothing is left. */
    fun withoutCard(): Target? =
      when (this) {
        is MoreInfo -> copy(card = null)
        is Navigate -> this
        is ShowCard -> null
      }
  }

  private val pending = MutableStateFlow<Target?>(null)

  /** The target waiting to be opened, if any. */
  val requests: StateFlow<Target?> = pending.asStateFlow()

  /**
   * The app's launch intent carrying [action]'s target; null for any other action. With [widgetId]
   * (the widget tapped), the widget's open-app and more-info taps also open on its card.
   */
  fun launchIntent(
    context: Context,
    action: HaAction,
    widgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID,
  ): Intent? {
    val extra =
      when (action) {
        WidgetOpenApp -> null
        is HaAction.MoreInfo -> EXTRA_MORE_INFO to action.entityId
        is HaAction.Navigate -> EXTRA_NAVIGATE to action.path
        else -> return null
      }
    // MainActivity itself, not the package's launch intent: that resolves to whichever launcher
    // alias is enabled now (LauncherIcons), and a widget's PendingIntent outlives an icon switch.
    return Intent(context, MainActivity::class.java)
      .setAction(Intent.ACTION_MAIN)
      .addCategory(Intent.CATEGORY_LAUNCHER)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      .apply {
        extra?.let { (key, value) -> putExtra(key, value) }
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) putExtra(EXTRA_WIDGET_ID, widgetId)
      }
  }

  /**
   * Records [intent]'s target, if it carries one; [widgetEntry] looks up the tapped widget, for the
   * card it was made from.
   */
  fun offer(intent: Intent?, widgetEntry: (widgetId: Int) -> WidgetStore.Entry? = { null }) {
    targetOf(intent, widgetEntry)?.let { pending.value = it }
  }

  /** Clears [target] once opened, unless a newer tap already replaced it. */
  fun consume(target: Target) {
    pending.compareAndSet(target, null)
  }

  /**
   * Replaces [target] with itself without its card ([Target.withoutCard]), unless a newer tap
   * already replaced it: the card's dashboard isn't one this app shows.
   */
  fun dropCard(target: Target) {
    pending.compareAndSet(target, target.withoutCard())
  }

  internal fun targetOf(
    intent: Intent?,
    widgetEntry: (widgetId: Int) -> WidgetStore.Entry? = { null },
  ): Target? {
    intent ?: return null
    val card by lazy {
      intent
        .getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        .takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID }
        ?.let(widgetEntry)
        ?.let { entry ->
          entry.origin?.let { CardRef(entry.baseUrl, it.dashboardUrlPath, it.card) }
        }
    }
    intent
      .getStringExtra(EXTRA_MORE_INFO)
      ?.takeIf { it.isNotBlank() }
      ?.let {
        return Target.MoreInfo(it, card)
      }
    // Only an app-relative path: the dashboard resolves it, or opens it on the instance.
    intent
      .getStringExtra(EXTRA_NAVIGATE)
      ?.takeIf { it.startsWith("/") }
      ?.let {
        return Target.Navigate(it)
      }
    return card?.let(Target::ShowCard)
  }

  const val EXTRA_MORE_INFO = "ee.schimke.terrazzo.widget.OPEN_MORE_INFO"
  const val EXTRA_NAVIGATE = "ee.schimke.terrazzo.widget.OPEN_NAVIGATE"
  const val EXTRA_WIDGET_ID = "ee.schimke.terrazzo.widget.OPEN_WIDGET_ID"
}
