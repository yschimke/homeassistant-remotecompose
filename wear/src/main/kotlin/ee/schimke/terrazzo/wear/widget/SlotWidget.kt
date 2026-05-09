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
 * Per-slot wear widget. Ten concrete subclasses ([Slot0SmallWidget] /
 * [Slot0LargeWidget] … [Slot4SmallWidget] / [Slot4LargeWidget]); each
 * binds a fixed `slotIndex` and reads the matching slot from disk at
 * render time. The size dimension only exists in the manifest /
 * service split — both small and large widgets for the same slot
 * share their `cardKey` and therefore render the same content; the
 * Glance Wear lib hands us [WearWidgetParams.containerType] so we
 * could specialise per size, but the v1 layout is identical for
 * either container.
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
abstract class SlotWidget(internal val slotIndex: Int) : GlanceWearWidget() {

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
internal fun SlotContent(title: String, state: String) {
    RemoteColumn(modifier = RemoteModifier.fillMaxWidth()) {
        RemoteText(text = title)
        if (state.isNotEmpty()) {
            RemoteText(text = state)
        }
    }
}

// One concrete widget per (slot, size) pair. The widget classes
// themselves don't read the size — both Small and Large for slot N
// resolve the same row from the offline store. The split exists so
// the manifest can advertise distinct container types per service,
// which is what the system widget picker filters on.

class Slot0SmallWidget : SlotWidget(slotIndex = 0)

class Slot0LargeWidget : SlotWidget(slotIndex = 0)

class Slot1SmallWidget : SlotWidget(slotIndex = 1)

class Slot1LargeWidget : SlotWidget(slotIndex = 1)

class Slot2SmallWidget : SlotWidget(slotIndex = 2)

class Slot2LargeWidget : SlotWidget(slotIndex = 2)

class Slot3SmallWidget : SlotWidget(slotIndex = 3)

class Slot3LargeWidget : SlotWidget(slotIndex = 3)

class Slot4SmallWidget : SlotWidget(slotIndex = 4)

class Slot4LargeWidget : SlotWidget(slotIndex = 4)

/**
 * One [GlanceWearWidgetService] per (slot, size) pair. The system
 * binds these via `androidx.glance.wear.action.BIND_WIDGET_PROVIDER`
 * (or the legacy `androidx.wear.tiles.action.BIND_TILE_PROVIDER` for
 * tile-compat surfaces). The `widget` property exposed by
 * [GlanceWearWidgetService] (synthesised from its Java `getWidget()`
 * accessor) returns a stable instance per service.
 *
 * Per-size manifest entries point at distinct
 * `wearwidget-provider` XML descriptors (`@xml/wear_slot_widget_provider_small`
 * vs `@xml/wear_slot_widget_provider_large`) so the picker filters
 * each service to one container type.
 */
class Slot0SmallWidgetService : GlanceWearWidgetService() {
    override val widget: GlanceWearWidget = Slot0SmallWidget()
}

class Slot0LargeWidgetService : GlanceWearWidgetService() {
    override val widget: GlanceWearWidget = Slot0LargeWidget()
}

class Slot1SmallWidgetService : GlanceWearWidgetService() {
    override val widget: GlanceWearWidget = Slot1SmallWidget()
}

class Slot1LargeWidgetService : GlanceWearWidgetService() {
    override val widget: GlanceWearWidget = Slot1LargeWidget()
}

class Slot2SmallWidgetService : GlanceWearWidgetService() {
    override val widget: GlanceWearWidget = Slot2SmallWidget()
}

class Slot2LargeWidgetService : GlanceWearWidgetService() {
    override val widget: GlanceWearWidget = Slot2LargeWidget()
}

class Slot3SmallWidgetService : GlanceWearWidgetService() {
    override val widget: GlanceWearWidget = Slot3SmallWidget()
}

class Slot3LargeWidgetService : GlanceWearWidgetService() {
    override val widget: GlanceWearWidget = Slot3LargeWidget()
}

class Slot4SmallWidgetService : GlanceWearWidgetService() {
    override val widget: GlanceWearWidget = Slot4SmallWidget()
}

class Slot4LargeWidgetService : GlanceWearWidgetService() {
    override val widget: GlanceWearWidget = Slot4LargeWidget()
}
