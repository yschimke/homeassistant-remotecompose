package ee.schimke.terrazzo.wear.sync

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import ee.schimke.terrazzo.wear.widget.Slot0WidgetService
import ee.schimke.terrazzo.wear.widget.Slot1WidgetService
import ee.schimke.terrazzo.wear.widget.Slot2WidgetService
import ee.schimke.terrazzo.wear.widget.Slot3WidgetService
import ee.schimke.terrazzo.wear.widget.Slot4WidgetService
import ee.schimke.terrazzo.wearsync.proto.WearWidgetSlots
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Watches [WearSyncRepository.slots] and keeps the 5 slot widget
 * services in sync with the user's mobile-side assignments:
 *
 *   - **Empty slot** (`cardKey == ""`) — disable the matching service
 *     component so the system widget picker hides it.
 *   - **Assigned slot** — enable the component, but only when the
 *     runtime is at least [WEAR_WIDGETS_MIN_SDK]. Below that floor
 *     every slot stays disabled regardless of phone-side assignment;
 *     the alpha library short-circuits its own bind path on older
 *     Wear OS versions, and an enabled-but-broken component would
 *     only confuse the picker.
 *
 * Capability advertisement: when the runtime supports widgets, this
 * controller adds the `terrazzo_wear_widgets` local capability so the
 * phone's [WearCapabilityProbe] can discover us. Removed when the
 * controller stops, so the phone gracefully hides the slot UI again.
 */
class WearSlotsController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repo: WearSyncRepository,
) {
    private val capabilityClient: CapabilityClient by lazy {
        Wearable.getCapabilityClient(context.applicationContext)
    }

    fun start() {
        if (supportsWearWidgets()) {
            advertiseCapability()
        } else {
            // Older Wear OS — keep all components disabled and don't
            // advertise the capability so the phone hides the slot UI.
            disableAll()
            return
        }
        scope.launch {
            repo.slots.collectLatest { applySlots(it) }
        }
    }

    fun stop() {
        // Fire-and-forget; the Task completes asynchronously and we
        // only care about clearing the local advertisement on
        // best-effort basis.
        runCatching {
            capabilityClient.removeLocalCapability(CAPABILITY)
                .addOnFailureListener { Log.w(TAG, "removeLocalCapability failed", it) }
        }
    }

    private fun applySlots(slots: WearWidgetSlots) {
        val pm = context.packageManager
        for (i in 0 until SLOT_COUNT) {
            val component = SLOT_COMPONENTS[i]
            val assigned = slots.slots.firstOrNull { it.slotIndex == i }?.cardKey?.isNotEmpty() == true
            val target = if (assigned) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            val name = ComponentName(context, component)
            val current = runCatching { pm.getComponentEnabledSetting(name) }.getOrNull()
            if (current == target) continue
            runCatching {
                pm.setComponentEnabledSetting(name, target, PackageManager.DONT_KILL_APP)
            }.onFailure { Log.w(TAG, "setComponentEnabledSetting failed for $component", it) }
        }
    }

    private fun disableAll() {
        val pm = context.packageManager
        for (component in SLOT_COMPONENTS) {
            val name = ComponentName(context, component)
            runCatching {
                pm.setComponentEnabledSetting(
                    name,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }.onFailure { Log.w(TAG, "disable $component failed", it) }
        }
    }

    private fun advertiseCapability() {
        runCatching {
            capabilityClient.addLocalCapability(CAPABILITY)
                .addOnFailureListener { Log.w(TAG, "addLocalCapability failed", it) }
        }
    }

    /**
     * Whether this device's runtime supports Glance Wear widgets.
     * Mirrors the alpha library's internal `forceIsAtLeast37ForTesting`
     * test knob — the public companion check is `internal` in alpha09
     * so we can't call it directly. Bump this constant if the lib
     * settles on a different floor before stable.
     */
    private fun supportsWearWidgets(): Boolean =
        Build.VERSION.SDK_INT >= WEAR_WIDGETS_MIN_SDK

    companion object {
        private const val TAG = "WearSlots"

        /**
         * Capability advertised when the runtime supports Glance Wear
         * widgets. Mirrored on the phone in
         * `WearCapabilityProbe.CAPABILITY`.
         */
        const val CAPABILITY: String = "terrazzo_wear_widgets"

        /**
         * Minimum SDK_INT where Glance Wear widgets actually render.
         * Inferred from the alpha09 lib's `forceIsAtLeast37ForTesting`
         * static field: production code branches on `>= 37`. Bump if
         * the lib's gate moves.
         */
        const val WEAR_WIDGETS_MIN_SDK: Int = 37

        const val SLOT_COUNT: Int = 5

        /**
         * Service classes in slot-index order. The list ordering is
         * load-bearing: [applySlots] indexes by position.
         */
        private val SLOT_COMPONENTS: List<Class<*>> = listOf(
            Slot0WidgetService::class.java,
            Slot1WidgetService::class.java,
            Slot2WidgetService::class.java,
            Slot3WidgetService::class.java,
            Slot4WidgetService::class.java,
        )
    }
}
