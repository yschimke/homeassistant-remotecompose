package ee.schimke.terrazzo.core.pin

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import ee.schimke.terrazzo.core.di.AppScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Mobile-side wear-widget slot assignments. Five slots (indices 0..4),
 * each pointing to a pinned card by [PinStore]'s `cardKey`. An empty
 * key means the slot is unconfigured — the watch will keep its
 * matching widget provider component disabled so it doesn't appear in
 * the system widget picker.
 *
 * Storage: a single Preferences entry per slot (`slot.0.cardKey` …
 * `slot.4.cardKey`). Keeping the schema flat (one row per slot)
 * matches [ee.schimke.terrazzo.core.widget.WidgetStore] and avoids the
 * JSON-list ceremony that [PinStore] needs for its variable-cardinality
 * data.
 *
 * The store doesn't own the rendering pipeline — it just stores
 * [WearWidgetSlot] rows. [ee.schimke.terrazzo.wearsync.MobileWearSyncManager]
 * subscribes to [slots] and publishes via DataLayer to /wear/slots.
 */
@SingleIn(AppScope::class)
@Inject
class WearWidgetSlotsStore(private val context: Context) {

    val slots: Flow<List<WearWidgetSlot>>
        get() = context.store.data.map { it.readSlots() }

    suspend fun slotsNow(): List<WearWidgetSlot> = slots.first()

    suspend fun setSlot(slotIndex: Int, cardKey: String) {
        require(slotIndex in 0 until SLOT_COUNT) { "slotIndex out of range: $slotIndex" }
        context.store.edit { it[cardKeyPref(slotIndex)] = cardKey }
    }

    suspend fun clearSlot(slotIndex: Int) {
        require(slotIndex in 0 until SLOT_COUNT) { "slotIndex out of range: $slotIndex" }
        context.store.edit {
            it.remove(cardKeyPref(slotIndex))
            it.remove(sizePref(slotIndex))
        }
    }

    suspend fun setSize(slotIndex: Int, size: SlotSize) {
        require(slotIndex in 0 until SLOT_COUNT) { "slotIndex out of range: $slotIndex" }
        context.store.edit { it[sizePref(slotIndex)] = size.wireValue }
    }

    private fun Preferences.readSlots(): List<WearWidgetSlot> =
        (0 until SLOT_COUNT).map { i ->
            WearWidgetSlot(
                slotIndex = i,
                cardKey = this[cardKeyPref(i)].orEmpty(),
                size = this[sizePref(i)]?.let { SlotSize.fromWire(it) } ?: SlotSize.Both,
            )
        }

    companion object {
        const val SLOT_COUNT: Int = 5

        private val Context.store by preferencesDataStore(name = "terrazzo_wear_slots")
        private fun cardKeyPref(i: Int) = stringPreferencesKey("slot.$i.cardKey")
        private fun sizePref(i: Int) = intPreferencesKey("slot.$i.size")
    }
}

/** One slot assignment. Empty `cardKey` means unconfigured. */
data class WearWidgetSlot(
    val slotIndex: Int,
    val cardKey: String,
    val size: SlotSize = SlotSize.Both,
) {
    val isAssigned: Boolean get() = cardKey.isNotEmpty()
}

/**
 * Per-slot Glance Wear container size choice. Wire values match
 * `ee.schimke.terrazzo.wearsync.proto.SlotSizePref` so encoding stays
 * symmetric across modules without an explicit mapper.
 */
enum class SlotSize(val wireValue: Int) {
    SmallOnly(0),
    LargeOnly(1),
    Both(2);

    val advertisesSmall: Boolean get() = this == SmallOnly || this == Both
    val advertisesLarge: Boolean get() = this == LargeOnly || this == Both

    companion object {
        fun fromWire(wire: Int): SlotSize =
            entries.firstOrNull { it.wireValue == wire } ?: Both
    }
}
