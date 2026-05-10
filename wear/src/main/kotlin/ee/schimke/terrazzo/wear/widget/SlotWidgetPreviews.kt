@file:Suppress("RestrictedApi", "RestrictedApiAndroidX")

package ee.schimke.terrazzo.wear.widget

import android.content.Context
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.glance.wear.GlanceWearWidget
import androidx.glance.wear.WearWidgetBrush
import androidx.glance.wear.WearWidgetData
import androidx.glance.wear.WearWidgetDocument
import androidx.glance.wear.core.ContainerInfo
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.core.WidgetInstanceId
import androidx.glance.wear.tooling.preview.WearWidgetPreview
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Wear-specific @Preview fixtures that exercise the Glance Wear
 * capture path end-to-end (via [WearWidgetPreview] /
 * [WearWidgetDocument]). The cross-surface "how does this card look at
 * each size" picture lives in
 * [ee.schimke.ha.previews.CardPreviewMatrix] — these previews are kept
 * thin and deliberately wear-shaped: small + large slot containers,
 * dark theme, one fixture per shape so we catch wear-profile
 * regressions in CI.
 */

private class PreviewSlotWidget(
    private val card: CardConfig?,
    private val snapshot: HaSnapshot,
    private val slotIndex: Int = 0,
) : GlanceWearWidget() {
    override suspend fun provideWidgetData(
        context: Context,
        params: WearWidgetParams,
    ): WearWidgetData {
        val theme = haThemeFor(ThemeStyle.TerrazzoHome, darkTheme = true)
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
                                PreviewEmptyPlaceholder(slotIndex, theme)
                            }
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun PreviewEmptyPlaceholder(slotIndex: Int, theme: HaTheme) {
    RemoteBox(modifier = RemoteModifier.fillMaxWidth()) {
        RemoteText(text = "Slot ${slotIndex + 1}", color = theme.primaryText.rc)
    }
}

private enum class ContainerType { Small, Large }

private fun cardConfig(
    type: String,
    build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
): CardConfig {
    val obj = buildJsonObject {
        put("type", type)
        build()
    }
    return CardConfig(type = type, raw = obj)
}

private fun snapshotOf(vararg states: EntityState): HaSnapshot =
    HaSnapshot(states = states.associateBy { it.entityId })

private fun entityState(
    id: String,
    state: String,
    friendlyName: String? = null,
    unit: String? = null,
    deviceClass: String? = null,
): EntityState {
    val attrs = buildMap<String, JsonPrimitive> {
        friendlyName?.let { put("friendly_name", JsonPrimitive(it)) }
        unit?.let { put("unit_of_measurement", JsonPrimitive(it)) }
        deviceClass?.let { put("device_class", JsonPrimitive(it)) }
    }
    return EntityState(entityId = id, state = state, attributes = JsonObject(attrs))
}

@Composable
private fun SlotWidgetPreviewFixture(
    card: CardConfig?,
    snapshot: HaSnapshot,
    container: ContainerType,
) {
    val params = if (container == ContainerType.Large) largePreviewParams else smallPreviewParams
    WearWidgetPreview(
        widget = PreviewSlotWidget(card = card, snapshot = snapshot),
        params = params,
    )
}

@Preview(name = "Slot widget — tile (small)")
@Composable
fun SlotWidgetPreview_TileSmall() {
    val card = cardConfig("tile") {
        put("entity", "sensor.living_room_temperature")
        put("name", "Living Room")
    }
    val snapshot = snapshotOf(
        entityState(
            id = "sensor.living_room_temperature",
            state = "21.5",
            friendlyName = "Living Room",
            unit = "°C",
            deviceClass = "temperature",
        ),
    )
    SlotWidgetPreviewFixture(card, snapshot, ContainerType.Small)
}

@Preview(name = "Slot widget — entities (large)")
@Composable
fun SlotWidgetPreview_EntitiesLarge() {
    val card = CardConfig(
        type = "entities",
        raw = buildJsonObject {
            put("type", "entities")
            put("title", "Living Room")
            put(
                "entities",
                kotlinx.serialization.json.buildJsonArray {
                    add(JsonPrimitive("sensor.living_room_temperature"))
                    add(JsonPrimitive("light.kitchen"))
                },
            )
        },
    )
    val snapshot = snapshotOf(
        entityState(
            id = "sensor.living_room_temperature",
            state = "21.5",
            friendlyName = "Living Room",
            unit = "°C",
            deviceClass = "temperature",
        ),
        entityState(
            id = "light.kitchen",
            state = "on",
            friendlyName = "Kitchen",
        ),
    )
    SlotWidgetPreviewFixture(card, snapshot, ContainerType.Large)
}

@Preview(name = "Slot widget — empty placeholder")
@Composable
fun SlotWidgetPreview_Empty() {
    SlotWidgetPreviewFixture(card = null, snapshot = HaSnapshot(), container = ContainerType.Small)
}

private val largePreviewParams =
    WearWidgetParams(
        instanceId = WidgetInstanceId("widgets", 1),
        containerType = ContainerInfo.CONTAINER_TYPE_LARGE,
        widthDp = 200f,
        heightDp = 112f,
        verticalPaddingDp = 8f,
        horizontalPaddingDp = 8f,
        cornerRadiusDp = 26f,
    )

private val smallPreviewParams =
    WearWidgetParams(
        instanceId = WidgetInstanceId("widgets", 2),
        containerType = ContainerInfo.CONTAINER_TYPE_SMALL,
        widthDp = 200f,
        heightDp = 60f,
        verticalPaddingDp = 8f,
        horizontalPaddingDp = 8f,
        cornerRadiusDp = 26f,
    )
