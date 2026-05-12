@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.RemoteSizeBreakpoint
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaArcDialData
import ee.schimke.ha.rc.components.HaModeChip
import ee.schimke.ha.rc.components.RemoteHaArcDial
import ee.schimke.ha.rc.components.RemoteHaArcDialWide
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `thermostat` card — climate entity as a 270° arc dial.
 *
 * Reads `temperature` (target), `current_temperature`, `hvac_action`,
 * `hvac_mode`, plus `min_temp`/`max_temp` for the arc range. Tapping
 * the +/- steppers fires `climate.set_temperature` with `temperature`
 * adjusted by `target_temp_step` (default 0.5°). The arc fill tracks
 * the *current* temperature; the target is shown as a textual
 * supporting line.
 */
class ThermostatCardConverter : CardConverter {
    override val cardType: String = CardTypes.THERMOSTAT

    override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 290

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        val entityId = card.raw["entity"]?.jsonPrimitive?.content
        val entity = entityId?.let { snapshot.states[it] }
        val attrs = entity?.attributes ?: JsonObject(emptyMap())
        val name = card.raw["name"]?.jsonPrimitive?.content
            ?: attrs["friendly_name"]?.jsonPrimitive?.content
            ?: entityId
            ?: "Thermostat"
        val unit = attrs["temperature_unit"]?.jsonPrimitive?.content ?: "°C"
        val current = attrs["current_temperature"]?.jsonPrimitive?.content?.toFloatOrNull()
        val target = attrs["temperature"]?.jsonPrimitive?.content?.toFloatOrNull()
        val min = attrs["min_temp"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 7f
        val max = attrs["max_temp"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 35f
        val step = attrs["target_temp_step"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0.5f
        val mode = entity?.state ?: "off"
        val hvacAction = attrs["hvac_action"]?.jsonPrimitive?.content
        val modeChip = when (hvacAction) {
            "heating" -> "Heating"
            "cooling" -> "Cooling"
            "drying" -> "Drying"
            "fan" -> "Fan"
            "idle" -> "Idle"
            else -> mode.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
        }
        val accent = climateAccent(mode, hvacAction)
        val span = (max - min).coerceAtLeast(0.0001f)
        val valueFraction = ((current ?: target ?: min) - min) / span
        val targetFraction = target?.let { (it - min) / span }

        val centerLabel = current?.let { "${formatTemp(it)}$unit" }
            ?: target?.let { "${formatTemp(it)}$unit" }
            ?: "—"
        val supportingLabel = target?.let { t -> "↑ ${formatTemp(t)}$unit" }

        val (incAction, decAction) = stepperActions(entity, target, step) ?: (HaAction.None to HaAction.None)

        val data = HaArcDialData(
            entityId = entityId,
            name = name,
            valueFraction = valueFraction.coerceIn(0f, 1f),
            targetFraction = targetFraction?.coerceIn(0f, 1f),
            centerLabel = centerLabel,
            centerLabelAttribute = "current_temperature_label",
            supportingLabel = supportingLabel,
            supportingLabelAttribute = supportingLabel?.let { "temperature_label" },
            modeChip =
                HaModeChip.Static(
                    entityId = entityId,
                    attribute = "hvac_action_label",
                    initial = modeChip,
                ),
            accent = accent,
            showSteppers = target != null,
            centerIcon = null,
            incrementAction = incAction,
            decrementAction = decAction,
        )

        RenderArcDial(data, modifier)
    }
}

/**
 * Width ladder shared by thermostat / humidifier. Mirrors the gauge
 * single-gate pattern in [GaugeCardConverter]: arc-left Wide row when
 * the runtime cell is too narrow for the full vertical card with
 * steppers, full card otherwise. A single threshold avoids the alpha010
 * nested `RemoteStateLayout` quirk that collapses multi-tier ladders
 * (#224).
 */
@Composable
internal fun RenderArcDial(data: HaArcDialData, modifier: RemoteModifier) {
    when (LocalCardSizeMode.current) {
        CardSizeMode.Wrap -> RemoteHaArcDial(data, modifier)
        CardSizeMode.Fixed ->
            RemoteSizeBreakpoint(
                thresholdsDp = intArrayOf(ArcDialFullMinDp),
                modifier = modifier.fillMaxSize(),
            ) { tier ->
                when (tier) {
                    0 -> RemoteHaArcDialWide(data, RemoteModifier.fillMaxSize())
                    else -> RemoteHaArcDial(data, RemoteModifier.fillMaxSize())
                }
            }
    }
}

private const val ArcDialFullMinDp = 260

private fun stepperActions(
    entity: EntityState?,
    target: Float?,
    step: Float,
): Pair<HaAction, HaAction>? {
    val id = entity?.entityId ?: return null
    val current = target ?: return null
    val up = HaAction.CallService(
        domain = "climate",
        service = "set_temperature",
        entityId = id,
        serviceData = kotlinx.serialization.json.JsonObject(mapOf(
            "temperature" to kotlinx.serialization.json.JsonPrimitive(current + step),
        )),
    )
    val down = HaAction.CallService(
        domain = "climate",
        service = "set_temperature",
        entityId = id,
        serviceData = kotlinx.serialization.json.JsonObject(mapOf(
            "temperature" to kotlinx.serialization.json.JsonPrimitive(current - step),
        )),
    )
    return up to down
}

private fun climateAccent(mode: String, hvacAction: String?): Color = when {
    hvacAction == "heating" || mode == "heat" -> Color(0xFFE65100)
    hvacAction == "cooling" || mode == "cool" -> Color(0xFF2196F3)
    hvacAction == "drying" || mode == "dry" -> Color(0xFFFDD835)
    hvacAction == "fan" || mode == "fan_only" -> Color(0xFF00BFA5)
    mode == "auto" || mode == "heat_cool" -> Color(0xFF546E7A)
    else -> Color(0xFF9E9E9E)
}

private fun formatTemp(v: Float): String =
    if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(v)
