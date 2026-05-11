package ee.schimke.terrazzo.core.wearsync

import ee.schimke.terrazzo.core.pin.PinStore
import ee.schimke.terrazzo.core.pin.WearWidgetSlotsStore
import ee.schimke.terrazzo.core.prefs.PreferencesStore
import ee.schimke.terrazzo.core.session.HaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Phone-side wear sync engine surface, lifted to terrazzo-core so the
 * dependency graph can bind it without dragging the play-services-wearable
 * stack across module boundaries. The real implementation lives in
 * `:app` (`MobileWearSyncManager`); previews and Robolectric tests swap
 * in [NoOpWearSyncManager].
 */
interface WearSyncManager {
    fun setSession(session: HaSession?)
    val streamActive: StateFlow<Boolean>
    fun start(
        scope: CoroutineScope,
        prefs: PreferencesStore,
        pinStore: PinStore,
        slotsStore: WearWidgetSlotsStore,
    )
}

/**
 * Inert [WearSyncManager] for environments without a paired wear device
 * (previews, Robolectric, unit tests). All side-effecting methods are
 * no-ops; [streamActive] stays false.
 */
class NoOpWearSyncManager : WearSyncManager {
    override fun setSession(session: HaSession?) {}
    override val streamActive: StateFlow<Boolean> = MutableStateFlow(false)
    override fun start(
        scope: CoroutineScope,
        prefs: PreferencesStore,
        pinStore: PinStore,
        slotsStore: WearWidgetSlotsStore,
    ) {}
}
