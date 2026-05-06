package ee.schimke.ha.rc.cards

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaArcDialData
import ee.schimke.ha.rc.components.RemoteHaArcDial
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `light` card — brightness ring around a bulb icon.
 *
 * Reads `brightness` (0..255) for the arc fill and uses on/off state
 * to choose accent. Centre icon is a bulb so the card reads visually as
 * a light at a glance. Tap toggles the light; the +/- steppers fire
 * `light.turn_on` with `brightness_step` of 25 (~10%).
 */
class LightCardConverter : CardConverter {
    override val cardType: String = CardTypes.LIGHT

    override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 250

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        val entityId = card.raw["entity"]?.jsonPrimitive?.content
        val entity = entityId?.let { snapshot.states[it] }
        val attrs = entity?.attributes ?: JsonObject(emptyMap())
        val name = card.raw["name"]?.jsonPrimitive?.content
            ?: attrs["friendly_name"]?.jsonPrimitive?.content
            ?: entityId
            ?: "Light"
        val isOn = entity?.state == "on"
        val brightness = attrs["brightness"]?.jsonPrimitive?.content?.toFloatOrNull()
        val fraction = if (isOn) (brightness ?: 255f) / 255f else 0f
        val accent = if (isOn) Color(0xFFFFBE3E) else Color(0xFFB0B0B0)
        val centerLabel = if (isOn) "${(fraction * 100f).toInt()}%" else "Off"

        val tap = entityId?.let { HaAction.Toggle(it) } ?: HaAction.None
        val (inc, dec) = brightnessSteppers(entityId, isOn, brightness)

        RemoteHaArcDial(
            HaArcDialData(
                name = name.rs,
                valueFraction = fraction,
                targetFraction = null,
                centerLabel = centerLabel.rs,
                supportingLabel = null,
                modeChip = (if (isOn) "On" else "Off").rs,
                accent = accent,
                showSteppers = isOn,
                centerIcon = Icons.Filled.Lightbulb,
                tapAction = tap,
                incrementAction = inc,
                decrementAction = dec,
            ),
            modifier = modifier,
        )
    }
}

private fun brightnessSteppers(
    entityId: String?,
    isOn: Boolean,
    brightness: Float?,
): Pair<HaAction, HaAction> {
    if (entityId == null || !isOn) return HaAction.None to HaAction.None
    val current = brightness ?: 255f
    val step = 25f
    val up = HaAction.CallService(
        domain = "light",
        service = "turn_on",
        entityId = entityId,
        serviceData = kotlinx.serialization.json.JsonObject(mapOf(
            "brightness" to kotlinx.serialization.json.JsonPrimitive((current + step).coerceAtMost(255f).toInt()),
        )),
    )
    val down = HaAction.CallService(
        domain = "light",
        service = "turn_on",
        entityId = entityId,
        serviceData = kotlinx.serialization.json.JsonObject(mapOf(
            "brightness" to kotlinx.serialization.json.JsonPrimitive((current - step).coerceAtLeast(0f).toInt()),
        )),
    )
    return up to down
}
