@file:Suppress("RestrictedApi", "RestrictedApiAndroidX")

package ee.schimke.terrazzo.wear.widget

import android.content.Context
import android.util.Log
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.runtime.Composable
import androidx.glance.wear.GlanceWearWidget
import androidx.glance.wear.GlanceWearWidgetService
import androidx.glance.wear.WearWidgetBrush
import androidx.glance.wear.WearWidgetData
import androidx.glance.wear.WearWidgetDocument
import androidx.glance.wear.core.WearWidgetParams
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.ProvideCardSizeMode
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.cards.shutter.withEnhancedShutter
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.haThemeFor
import ee.schimke.terrazzo.wear.data.WearPrefs
import ee.schimke.terrazzo.wear.sync.WearOfflineStore
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import ee.schimke.terrazzo.wearsync.proto.LiveValues
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Per-slot wear widget. Ten concrete subclasses ([Slot0SmallWidget] /
 * [Slot0LargeWidget] … [Slot4SmallWidget] / [Slot4LargeWidget]); each
 * binds a fixed `slotIndex` and reads the matching slot from disk at
 * render time. Both small and large for the same slot share their
 * `cardKey` and therefore render the same card; the size split exists
 * only at the manifest / service level so the picker advertises both
 * container types.
 *
 * Render path mirrors the phone widget ([ee.schimke.terrazzo.widget.TerrazzoWidgetProvider]):
 *   1. [WearOfflineStore.readSlots] → find this slot's `cardKey`.
 *   2. [WearOfflineStore.readPinned] → resolve the [PinnedCard] and
 *      decode `rawJson` to a [CardConfig].
 *   3. [WearOfflineStore.readValues] → build an [HaSnapshot] from the
 *      pushed [LiveValues] (state + friendly_name + unit + device_class).
 *   4. Render via the rc-converter registry inside the
 *      [WearWidgetDocument] content lambda; Glance Wear captures the
 *      composition to RemoteCompose bytes for the watch's RC runtime.
 *
 * Wear is dark-only, so the theme is always resolved with `darkTheme = true`.
 * The user's selected [ThemeStyle] (read from [WearPrefs]) still drives
 * hue and font family.
 *
 * Limitations: cards that need history / statistics / forecasts won't
 * have that data — only the latest entity state flows over the data
 * layer today. Cards that lean on ops outside the Glance Wear capture
 * profile may fail to capture; the registry's
 * [ee.schimke.ha.rc.UNSUPPORTED_CARD_TYPE] fallback handles unknown
 * types but not in-range ops mismatches.
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
        val app = context.applicationContext
        val store = WearOfflineStore(app)
        val slot = store.readSlots()?.slots?.firstOrNull { it.slotIndex == slotIndex }
        val pinned = slot?.cardKey
            ?.takeIf { it.isNotEmpty() }
            ?.let { key -> store.readPinned()?.cards?.firstOrNull { it.cardKey == key } }

        val card = pinned?.card?.rawJson?.takeIf { it.isNotEmpty() }?.toCardConfigOrNull()
        val snapshot = store.readValues().toSnapshot()

        val style = WearPrefs(app).themeStyle.first()
        val theme = haThemeFor(style, darkTheme = true)
        val registry = defaultRegistry().withEnhancedShutter()

        return WearWidgetDocument(
            background = WearWidgetBrush.Companion,
            content = {
                ProvideCardRegistry(registry) {
                    ProvideHaTheme(theme) {
                        ProvideCardSizeMode(CardSizeMode.Fixed) {
                            if (card != null) {
                                RenderChild(card, snapshot, RemoteModifier.fillMaxWidth())
                            } else {
                                EmptySlotPlaceholder(slotIndex, theme)
                            }
                        }
                    }
                }
            },
        )
    }
}

/**
 * Tiny placeholder rendered when a slot has no assigned card or its
 * `rawJson` failed to decode. Keeps the watch surface non-empty during
 * the transient window where the slot is configured but the matching
 * `PinnedCard` hasn't synced yet.
 */
@Composable
private fun EmptySlotPlaceholder(slotIndex: Int, theme: HaTheme) {
    RemoteBox(modifier = RemoteModifier.fillMaxWidth()) {
        RemoteText(text = "Slot ${slotIndex + 1}", color = theme.primaryText.rc)
    }
}

private val cardJson = Json { ignoreUnknownKeys = true }

private fun String.toCardConfigOrNull(): CardConfig? =
    runCatching { cardJson.decodeFromString(CardConfig.serializer(), this) }
        .onFailure { Log.w("SlotWidget", "card decode failed", it) }
        .getOrNull()

/**
 * Project the proto [LiveValues] snapshot into the [HaSnapshot] shape
 * card converters expect. Only the four fields the wire format carries
 * survive the round-trip — state, friendly_name, unit_of_measurement,
 * device_class. Cards reading other attributes will see them as absent.
 */
private fun LiveValues?.toSnapshot(): HaSnapshot {
    val live = this ?: return HaSnapshot()
    val states = live.values.mapValues { (id, v) -> v.toEntityState(id) }
    return HaSnapshot(states = states)
}

private fun EntityValue.toEntityState(entityId: String): EntityState {
    val attrs = buildMap<String, JsonPrimitive> {
        if (friendlyName.isNotEmpty()) put("friendly_name", JsonPrimitive(friendlyName))
        if (unit.isNotEmpty()) put("unit_of_measurement", JsonPrimitive(unit))
        if (deviceClass.isNotEmpty()) put("device_class", JsonPrimitive(deviceClass))
    }
    return EntityState(
        entityId = entityId,
        state = state,
        attributes = JsonObject(attrs),
    )
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
