package ee.schimke.terrazzo.core.wearsync

import ee.schimke.terrazzo.core.pin.PinStore
import ee.schimke.terrazzo.core.pin.WearWidgetSlotsStore
import ee.schimke.terrazzo.core.prefs.PreferencesStore
import ee.schimke.terrazzo.core.session.HaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Phone-side wear sync engine surface, lifted to terrazzo-core so the dependency graph can bind it
 * without dragging the play-services-wearable stack across module boundaries. The real
 * implementation lives in `:app` (`MobileWearSyncManager`); previews and Robolectric tests swap in
 * [NoOpWearSyncManager].
 */
interface WearSyncManager {
  fun setSession(session: HaSession?)

  /**
   * Sign-out: ends the session ([setSession] `null`) and deletes the account's data — dashboards,
   * entity values, pictures, pins — from the watch, which forgets each item as its deletion
   * arrives. A later session publishes its own again.
   */
  fun clearWatchData() {}

  val streamActive: StateFlow<Boolean>

  /**
   * Whether the wear data-layer API is usable on this device. Stays `true` on unknown /
   * not-yet-probed (optimistic) and flips to `false` once a Google Play Services call comes back
   * with `API_NOT_CONNECTED` — typically a phone without a paired Wear device or without the
   * wearable component installed, though it can also fire on a transient Play Services disconnect.
   * A recovery probe flips it back once the API is reachable again, so a flap doesn't latch the
   * manager off. UI layers gate wear-only entry points on this so diagnostics / pairing actions
   * don't dangle when the platform can't honour them.
   */
  val wearableAvailable: StateFlow<Boolean>

  fun start(
    scope: CoroutineScope,
    prefs: PreferencesStore,
    pinStore: PinStore,
    slotsStore: WearWidgetSlotsStore,
    inputEditing: StateFlow<Boolean> = MutableStateFlow(false),
  )
}

/**
 * Inert [WearSyncManager] for environments without a paired wear device (previews, Robolectric,
 * unit tests). All side-effecting methods are no-ops; [streamActive] stays false and
 * [wearableAvailable] reports false so wear-only UI is hidden.
 */
class NoOpWearSyncManager : WearSyncManager {
  override fun setSession(session: HaSession?) {}

  override val streamActive: StateFlow<Boolean> = MutableStateFlow(false)
  override val wearableAvailable: StateFlow<Boolean> = MutableStateFlow(false)

  override fun start(
    scope: CoroutineScope,
    prefs: PreferencesStore,
    pinStore: PinStore,
    slotsStore: WearWidgetSlotsStore,
    inputEditing: StateFlow<Boolean>,
  ) {}
}
