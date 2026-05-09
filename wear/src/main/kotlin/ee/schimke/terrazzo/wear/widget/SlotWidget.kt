@file:Suppress("RestrictedApi", "RestrictedApiAndroidX")

package ee.schimke.terrazzo.wear.widget

import android.content.Context
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.glance.wear.GlanceWearWidget
import androidx.glance.wear.GlanceWearWidgetService
import androidx.glance.wear.WearWidgetBrush
import androidx.glance.wear.WearWidgetData
import androidx.glance.wear.WearWidgetDocument
import androidx.glance.wear.core.WearWidgetParams
import ee.schimke.terrazzo.wear.sync.WearOfflineStore

/**
 * Per-slot wear widget. Five concrete subclasses ([Slot0Widget] …
 * [Slot4Widget]); each one binds a fixed `slotIndex` and reads the
 * matching slot from disk at render time.
 *
 * Render path:
 *   1. [WearOfflineStore.readSlots] — find this slot's `cardKey`.
 *   2. [WearOfflineStore.readPinned] — resolve the pinned card.
 *   3. [WearOfflineStore.readValues] — pull the primary entity's
 *      latest value.
 *   4. Hand the resulting (title, state) pair to the RemoteCompose
 *      composition wrapped in a [WearWidgetDocument].
 *
 * Content is intentionally minimal for v1 — title on top, state
 * underneath, both as `RemoteText`. Full RemoteCompose card capture
 * (matching the phone widget pipeline) is a follow-up; the watch
 * doesn't have `rc-converter` on its classpath today.
 *
 * Empty slots / un-paired widgets short-circuit to a placeholder
 * label; the matching service component is normally disabled via
 * [ee.schimke.terrazzo.wear.sync.WearSlotsController] before the
 * picker can surface them, but the fallback keeps a transient race
 * from showing an empty card.
 */
abstract class SlotWidget(private val slotIndex: Int) : GlanceWearWidget() {

    override suspend fun provideWidgetData(
        context: Context,
        params: WearWidgetParams,
    ): WearWidgetData {
        val store = WearOfflineStore(context.applicationContext)
        val slot = store.readSlots()?.slots?.firstOrNull { it.slotIndex == slotIndex }
        val card = slot?.cardKey
            ?.takeIf { it.isNotEmpty() }
            ?.let { key -> store.readPinned()?.cards?.firstOrNull { it.cardKey == key } }
        val value = card?.card?.primaryEntityId
            ?.takeIf { it.isNotEmpty() }
            ?.let { entityId -> store.readValues()?.values?.get(entityId) }

        val title = card?.card?.title
            ?.ifEmpty { card.card.primaryEntityId }
            .orEmpty()
            .ifEmpty { "Slot ${slotIndex + 1}" }
        val stateText = value?.let { v ->
            buildString {
                append(v.state)
                if (v.unit.isNotEmpty()) append(" ${v.unit}")
            }
        }.orEmpty()

        return WearWidgetDocument(
            background = WearWidgetBrush.Companion,
            content = { SlotContent(title, stateText) },
        )
    }
}

@Composable
private fun SlotContent(title: String, state: String) {
    RemoteColumn(modifier = RemoteModifier.fillMaxWidth()) {
        RemoteText(text = title)
        if (state.isNotEmpty()) {
            RemoteText(text = state)
        }
    }
}

class Slot0Widget : SlotWidget(slotIndex = 0)

class Slot1Widget : SlotWidget(slotIndex = 1)

class Slot2Widget : SlotWidget(slotIndex = 2)

class Slot3Widget : SlotWidget(slotIndex = 3)

class Slot4Widget : SlotWidget(slotIndex = 4)

/**
 * One [GlanceWearWidgetService] per slot. The system binds these via
 * `androidx.glance.wear.action.BIND_WIDGET_PROVIDER` (or the legacy
 * `androidx.wear.tiles.action.BIND_TILE_PROVIDER` for tile-compat
 * surfaces). The `widget` property exposed by
 * [GlanceWearWidgetService] (synthesised from its Java
 * `getWidget()` accessor) returns a stable instance per service;
 * the framework pings it whenever the system needs a fresh frame.
 */
class Slot0WidgetService : GlanceWearWidgetService() {
    override val widget: GlanceWearWidget = Slot0Widget()
}

class Slot1WidgetService : GlanceWearWidgetService() {
    override val widget: GlanceWearWidget = Slot1Widget()
}

class Slot2WidgetService : GlanceWearWidgetService() {
    override val widget: GlanceWearWidget = Slot2Widget()
}

class Slot3WidgetService : GlanceWearWidgetService() {
    override val widget: GlanceWearWidget = Slot3Widget()
}

class Slot4WidgetService : GlanceWearWidgetService() {
    override val widget: GlanceWearWidget = Slot4Widget()
}
