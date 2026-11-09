package ee.schimke.terrazzo.wear.sync

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import ee.schimke.terrazzo.wear.widget.CardLargeWidgetService
import ee.schimke.terrazzo.wear.widget.CardSmallWidgetService
import ee.schimke.terrazzo.wear.widget.publishPlacedWidgets
import ee.schimke.terrazzo.wear.widget.refreshAllCardWidgets
import ee.schimke.terrazzo.wear.widget.widgetCardOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Keeps the two Terrazzo widget providers ([CardSmallWidgetService], [CardLargeWidgetService]) in
 * step with what the phone sends, and the placed widgets up to date:
 *
 * - **Enabled** while the watch has any card to show (a pinned card, or a pinned section's), so the
 *   system widget picker offers Terrazzo only when there is something to choose. The phone chooses
 *   each placed widget's card ([ee.schimke.terrazzo.wear.widget.WearWidgetCardStore]).
 * - **Disabled** while there is none (never paired, or signed out). A host may drop the placed
 *   widgets of a disabled provider, so a widget showing a card the phone has since unpinned keeps
 *   drawing its kept copy only while some card is still synced.
 * - **Reported** on start: the phone hears what is placed ([publishPlacedWidgets]), and a widget
 *   removed while the app wasn't running (so never reaching `onRemoved`) loses its saved card.
 * - **Refreshed** when the pins, sections, phone appearance or selected Wear designs change: a
 *   placed widget may show any of them.
 *
 * Every supported Wear OS version gets the providers: Glance Wear serves API 37+ hosts through its
 * widget binder (push updates) and older ones through the tile-provider compat binder (pull
 * updates), so nothing here gates on SDK level.
 *
 * Capability advertisement: the `terrazzo_wear_widgets` capability is declared statically
 * (`res/values/wear.xml`). The phone no longer sets up Wear widgets; it reads the capability only
 * to recognise watch builds older than the Horologist install marker as having the app.
 */
class WearWidgetsController(
  private val context: Context,
  private val scope: CoroutineScope,
  private val repo: WearSyncRepository,
) {
  fun start() {
    // An older build kept the phone's widget slot assignments: nothing reads them now.
    WearOfflineStore(context.applicationContext).forgetLegacySlots()
    scope.launch(Dispatchers.IO) { publishPlacedWidgets(context.applicationContext) }
    scope.launch {
      combine(repo.pinned, repo.sections) { pinned, sections ->
          widgetCardOptions(pinned, sections).isNotEmpty()
        }
        .collectLatest { anyCard -> applyEnabled(anyCard) }
    }
    scope.launch {
      // A changed card, or a newly synced design for one, must redraw the placed widgets showing
      // it, even when the providers' enabled state did not change. Skips the first, on-disk value:
      // the widgets already drew it.
      combine(repo.pinned, repo.sections, repo.designRevision, repo.settings) { _, _, _, _ -> }
        .drop(1)
        .collectLatest { refreshAllCardWidgets(context.applicationContext) }
    }
  }

  private fun applyEnabled(enabled: Boolean) {
    val pm = context.packageManager
    COMPONENTS.forEach { applyComponent(pm, it, enabled) }
  }

  private fun applyComponent(pm: PackageManager, component: Class<*>, enabled: Boolean) {
    val name = ComponentName(context, component)
    val target =
      if (enabled) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
      } else {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
      }
    val current = runCatching { pm.getComponentEnabledSetting(name) }.getOrNull()
    if (current == target) return
    runCatching { pm.setComponentEnabledSetting(name, target, PackageManager.DONT_KILL_APP) }
      .onFailure { Log.w(TAG, "setComponentEnabledSetting failed for $component", it) }
  }

  companion object {
    private const val TAG = "WearWidgets"

    /** Capability advertised by the watch app, declared in `res/values/wear.xml`. */
    const val CAPABILITY: String = "terrazzo_wear_widgets"

    private val COMPONENTS: List<Class<*>> =
      listOf(CardSmallWidgetService::class.java, CardLargeWidgetService::class.java)
  }
}
