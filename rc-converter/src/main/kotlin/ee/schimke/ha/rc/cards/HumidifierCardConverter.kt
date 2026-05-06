package ee.schimke.ha.rc.cards

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
 * `humidifier` card — humidifier entity as a 270° arc dial.
 *
 * Same shape as the thermostat card but the arc fills with the current
 * humidity %, the target humidity is the supportingLabel, and the
 * mode chip reads "Humidifying" / "Drying" / "Off".
 */
class HumidifierCardConverter : CardConverter {
    override val cardType: String = CardTypes.HUMIDIFIER

    override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 290

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        val entityId = card.raw["entity"]?.jsonPrimitive?.content
        val entity = entityId?.let { snapshot.states[it] }
        val attrs = entity?.attributes ?: JsonObject(emptyMap())
        val name = card.raw["name"]?.jsonPrimitive?.content
            ?: attrs["friendly_name"]?.jsonPrimitive?.content
            ?: entityId
            ?: "Humidifier"
        val current = attrs["current_humidity"]?.jsonPrimitive?.content?.toFloatOrNull()
        val target = attrs["humidity"]?.jsonPrimitive?.content?.toFloatOrNull()
        val min = attrs["min_humidity"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f
        val max = attrs["max_humidity"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 100f
        val state = entity?.state ?: "off"
        val action = attrs["action"]?.jsonPrimitive?.content
        val modeChip = when {
            state == "off" -> "Off"
            action == "humidifying" -> "Humidifying"
            action == "drying" -> "Drying"
            action == "idle" -> "Idle"
            else -> state.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
        }

        val span = (max - min).coerceAtLeast(0.0001f)
        val valueFraction = ((current ?: target ?: min) - min) / span
        val targetFraction = target?.let { (it - min) / span }

        val centerLabel = (current ?: target)?.let { "${it.toInt()}%" } ?: "—"
        val supportingLabel = target?.let { "↑ ${it.toInt()}%" }

        val (incAction, decAction) = stepperActions(entity?.entityId, target, 5f)

        RemoteHaArcDial(
            HaArcDialData(
                name = name.rs,
                valueFraction = valueFraction.coerceIn(0f, 1f),
                targetFraction = targetFraction?.coerceIn(0f, 1f),
                centerLabel = centerLabel.rs,
                supportingLabel = supportingLabel?.rs,
                modeChip = modeChip.rs,
                accent = Color(0xFF00ACC1),
                showSteppers = target != null,
                centerIcon = null,
                incrementAction = incAction,
                decrementAction = decAction,
            ),
            modifier = modifier,
        )
    }
}

private fun stepperActions(
    entityId: String?,
    target: Float?,
    step: Float,
): Pair<HaAction, HaAction> {
    if (entityId == null || target == null) return HaAction.None to HaAction.None
    val up = HaAction.CallService(
        domain = "humidifier",
        service = "set_humidity",
        entityId = entityId,
        serviceData = kotlinx.serialization.json.JsonObject(mapOf(
            "humidity" to kotlinx.serialization.json.JsonPrimitive((target + step).coerceAtMost(100f)),
        )),
    )
    val down = HaAction.CallService(
        domain = "humidifier",
        service = "set_humidity",
        entityId = entityId,
        serviceData = kotlinx.serialization.json.JsonObject(mapOf(
            "humidity" to kotlinx.serialization.json.JsonPrimitive((target - step).coerceAtLeast(0f)),
        )),
    )
    return up to down
}
