package ee.schimke.terrazzo.wear.sync

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import ee.schimke.terrazzo.wear.widget.CardLargeWidgetService
import ee.schimke.terrazzo.wear.widget.CardSmallWidgetService
import ee.schimke.terrazzo.wear.widget.refreshAllCardWidgets
import ee.schimke.terrazzo.wear.widget.widgetCardOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Keeps the two Terrazzo widget providers ([CardSmallWidgetService], [CardLargeWidgetService]) in
 * step with what the phone sends, and the placed widgets up to date:
 *
 * - **Enabled** while the watch has any card to show (a pinned card, or a pinned section's), so the
 *   system widget picker offers Terrazzo only when there is something to choose. Each placed widget
 *   chooses its own card on the watch ([ee.schimke.terrazzo.wear.widget.WearWidgetCardStore]).
 * - **Disabled** while there is none (never paired, or signed out). A host may drop the placed
 *   widgets of a disabled provider, so a widget showing a card the phone has since unpinned keeps
 *   drawing its kept copy only while some card is still synced.
 * - **Refreshed** when the pins, sections or the pinned cards' Wear designs change: a placed widget
 *   may show any of them.
 *
 * Below [WEAR_WIDGETS_MIN_SDK] both components stay disabled; the alpha library short-circuits its
 * own bind path on older Wear OS versions, and an enabled-but-broken component would only confuse
 * the picker.
 *
 * Capability advertisement: the `terrazzo_wear_widgets` capability is declared statically
 * (`res/values-v37/wear.xml`, the same API floor as [WEAR_WIDGETS_MIN_SDK]). The phone no longer
 * sets up Wear widgets; it reads the capability only to recognise watch builds older than the
 * Horologist install marker as having the app.
 */
class WearWidgetsController(
  private val context: Context,
  private val scope: CoroutineScope,
  private val repo: WearSyncRepository,
) {
  fun start() {
    // An older build kept the phone's widget slot assignments: nothing reads them now.
    WearOfflineStore(context.applicationContext).forgetLegacySlots()
    if (!supportsWearWidgets()) {
      disableAll()
      return
    }
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
      combine(repo.pinned, repo.sections, repo.slotDesigns) { _, _, _ -> }
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

  private fun disableAll() = applyEnabled(false)

  companion object {
    private const val TAG = "WearWidgets"

    /**
     * Capability advertised when the runtime supports Glance Wear widgets, declared in
     * `res/values-v37/wear.xml`.
     */
    const val CAPABILITY: String = "terrazzo_wear_widgets"

    /**
     * Minimum SDK_INT where Glance Wear widgets actually render. Inferred from the alpha lib's
     * `forceIsAtLeast37ForTesting` static field: production code branches on `>= 37`. Bump if the
     * lib's gate moves.
     */
    const val WEAR_WIDGETS_MIN_SDK: Int = 37

    /** Whether this watch can show Glance Wear widgets: see [WEAR_WIDGETS_MIN_SDK]. */
    fun supportsWearWidgets(): Boolean = Build.VERSION.SDK_INT >= WEAR_WIDGETS_MIN_SDK

    private val COMPONENTS: List<Class<*>> =
      listOf(CardSmallWidgetService::class.java, CardLargeWidgetService::class.java)
  }
}
