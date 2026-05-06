package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.LiveBindings
import ee.schimke.ha.rc.components.HaGaugeData
import ee.schimke.ha.rc.components.HaGaugeSeverity
import ee.schimke.ha.rc.components.RemoteHaGauge
import ee.schimke.ha.rc.defaultTapActionFor
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.rc.parseHaAction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `gauge` card. Maps the entity's numeric state into a half-circle dial
 * with HA's severity bands (`severity.green`/`yellow`/`red` thresholds).
 * Sweep is captured statically — alpha08 doesn't expose a `RemoteFloat`
 * binding, so live updates re-encode.
 */
class GaugeCardConverter : CardConverter {
    override val cardType: String = CardTypes.GAUGE

    override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 150

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        val entityId = card.raw["entity"]?.jsonPrimitive?.content
        val entity = entityId?.let { snapshot.states[it] }
        val name = card.raw["name"]?.jsonPrimitive?.content
            ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
            ?: entityId
            ?: "(no entity)"
        val unit = card.raw["unit"]?.jsonPrimitive?.content
            ?: entity?.attributes?.get("unit_of_measurement")?.jsonPrimitive?.content
        val min = card.raw["min"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
        val max = card.raw["max"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 100.0
        val value = entity?.state?.toDoubleOrNull() ?: min
        val severity = parseSeverity(card.raw["severity"] as? JsonObject, value)
        val tapCfg = card.raw["tap_action"]?.jsonObject
        val tapAction = if (tapCfg != null) parseHaAction(tapCfg, entityId)
        else defaultTapActionFor(entityId)

        RemoteHaGauge(
            HaGaugeData(
                name = name.rs,
                valueText = LiveBindings.state(entity, formatState(entity)),
                unit = unit,
                value = value,
                min = min,
                max = max,
                severity = severity,
                tapAction = tapAction,
            ),
            modifier = modifier,
        )
    }
}

/**
 * HA's severity config is a map of `green` / `yellow` / `red` to numeric
 * thresholds. The active band is the highest threshold the value
 * crosses, regardless of the threshold ordering (HA accepts both
 * "green=60, yellow=30, red=0" descending and "red=50, yellow=10, green=0"
 * ascending — see the meshcore dashboard for examples).
 */
private fun parseSeverity(cfg: JsonObject?, value: Double): HaGaugeSeverity {
    if (cfg == null) return HaGaugeSeverity.None
    val green = cfg["green"]?.jsonPrimitive?.content?.toDoubleOrNull()
    val yellow = cfg["yellow"]?.jsonPrimitive?.content?.toDoubleOrNull()
    val red = cfg["red"]?.jsonPrimitive?.content?.toDoubleOrNull()
    val bands = listOfNotNull(
        green?.let { HaGaugeSeverity.Normal to it },
        yellow?.let { HaGaugeSeverity.Warning to it },
        red?.let { HaGaugeSeverity.Critical to it },
    )
    if (bands.isEmpty()) return HaGaugeSeverity.None
    val ascending = bands.sortedBy { it.second }
    return ascending.lastOrNull { value >= it.second }?.first ?: HaGaugeSeverity.None
}
