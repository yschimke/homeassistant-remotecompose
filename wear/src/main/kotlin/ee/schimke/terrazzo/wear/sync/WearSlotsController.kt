package ee.schimke.terrazzo.wear.sync

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import ee.schimke.terrazzo.wear.widget.Slot0LargeWidgetService
import ee.schimke.terrazzo.wear.widget.Slot0SmallWidgetService
import ee.schimke.terrazzo.wear.widget.Slot1LargeWidgetService
import ee.schimke.terrazzo.wear.widget.Slot1SmallWidgetService
import ee.schimke.terrazzo.wear.widget.Slot2LargeWidgetService
import ee.schimke.terrazzo.wear.widget.Slot2SmallWidgetService
import ee.schimke.terrazzo.wear.widget.Slot3LargeWidgetService
import ee.schimke.terrazzo.wear.widget.Slot3SmallWidgetService
import ee.schimke.terrazzo.wear.widget.Slot4LargeWidgetService
import ee.schimke.terrazzo.wear.widget.Slot4SmallWidgetService
import ee.schimke.terrazzo.wearsync.proto.SlotSizePref
import ee.schimke.terrazzo.wearsync.proto.WearWidgetSlots
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Watches [WearSyncRepository.slots] and keeps the 10 slot widget
 * services in sync with the user's mobile-side assignments. Each slot
 * has two services (small + large) so the system widget picker can
 * filter by container type:
 *
 *   - **Empty slot** (`cardKey == ""`) — both Small and Large
 *     services for the slot are disabled.
 *   - **Assigned slot** — the size choice (Small / Large / Both)
 *     decides which subset is enabled. Disabled services don't appear
 *     in the picker, so the user's "Small only" choice on the phone
 *     genuinely hides the large variant.
 *
 * Below [WEAR_WIDGETS_MIN_SDK] every component stays disabled
 * regardless of phone-side assignment; the alpha library short-circuits
 * its own bind path on older Wear OS versions, and an enabled-but-
 * broken component would only confuse the picker.
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
            val slot = slots.slots.firstOrNull { it.slotIndex == i }
            val assigned = slot?.cardKey?.isNotEmpty() == true
            val sizePref = SlotSizePref.fromWire(slot?.size ?: SlotSizePref.Both.wireValue)
            val enableSmall = assigned && sizePref.advertisesSmall
            val enableLarge = assigned && sizePref.advertisesLarge
            applyComponent(pm, SLOT_SMALL_COMPONENTS[i], enableSmall)
            applyComponent(pm, SLOT_LARGE_COMPONENTS[i], enableLarge)
        }
    }

    private fun applyComponent(pm: PackageManager, component: Class<*>, enabled: Boolean) {
        val name = ComponentName(context, component)
        val target = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        val current = runCatching { pm.getComponentEnabledSetting(name) }.getOrNull()
        if (current == target) return
        runCatching {
            pm.setComponentEnabledSetting(name, target, PackageManager.DONT_KILL_APP)
        }.onFailure { Log.w(TAG, "setComponentEnabledSetting failed for $component", it) }
    }

    private fun disableAll() {
        val pm = context.packageManager
        (SLOT_SMALL_COMPONENTS + SLOT_LARGE_COMPONENTS).forEach { applyComponent(pm, it, false) }
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

    /**
     * Mirrors `SlotSizePref` — local helpers so this file doesn't take
     * a hard dep on the proto enum's display strings.
     */
    private val SlotSizePref.advertisesSmall: Boolean
        get() = this == SlotSizePref.SmallOnly || this == SlotSizePref.Both

    private val SlotSizePref.advertisesLarge: Boolean
        get() = this == SlotSizePref.LargeOnly || this == SlotSizePref.Both

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
         * Small-container service classes in slot-index order. The
         * list ordering is load-bearing: [applySlots] indexes by
         * position.
         */
        private val SLOT_SMALL_COMPONENTS: List<Class<*>> = listOf(
            Slot0SmallWidgetService::class.java,
            Slot1SmallWidgetService::class.java,
            Slot2SmallWidgetService::class.java,
            Slot3SmallWidgetService::class.java,
            Slot4SmallWidgetService::class.java,
        )

        /** Large-container service classes in slot-index order. */
        private val SLOT_LARGE_COMPONENTS: List<Class<*>> = listOf(
            Slot0LargeWidgetService::class.java,
            Slot1LargeWidgetService::class.java,
            Slot2LargeWidgetService::class.java,
            Slot3LargeWidgetService::class.java,
            Slot4LargeWidgetService::class.java,
        )
    }
}
