@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteStateLayout
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteState
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.components.HaGaugeData
import ee.schimke.ha.rc.components.HaGaugeSeverity
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.RemoteHaGauge
import ee.schimke.ha.rc.components.RemoteHaGaugeCompact
import ee.schimke.ha.rc.components.RemoteHaGaugeWide
import ee.schimke.ha.rc.defaultTapActionFor
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.rc.parseHaAction
import java.text.DecimalFormat
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `gauge` card. Maps the entity's numeric state into a half-circle dial
 * with HA's severity bands (`severity.green`/`yellow`/`red` thresholds).
 * The sweep is derived from `<entity>.numeric_state` so live value
 * updates animate without re-encoding; severity-band colours are baked
 * at encode time and re-encode when the active band changes.
 *
 * In [CardSizeMode.Fixed] the converter encodes a 3-tier ladder driven
 * by the runtime canvas:
 *
 *   * Below ~80 dp wide → centred state chip
 *     (no room for the dial).
 *   * Wide-thin (`width > 1.5×height`) → [RemoteHaGaugeWide]
 *     (arc-on-left + value/name on the right). Targets Wear S and
 *     `2×1` launcher pills.
 *   * Otherwise → [RemoteHaGaugeCompact] (arc fills the cell, value
 *     overlaid inside the arc). Targets `2×2` / `3×2` launcher
 *     squares and Wear L.
 *
 * Aspect detection uses `componentWidth() / componentHeight()` and
 * lowers to nested `RemoteStateLayout(RemoteBoolean)` (the only
 * state-layout overload that respects live state in alpha010 — see
 * [ee.schimke.ha.rc.RemoteSizeBreakpoint]). Both float expressions
 * are materialised via transparent text so the runtime resolves them
 * correctly (#224).
 */
class GaugeCardConverter : CardConverter {
    override val cardType: String = CardTypes.GAUGE

    override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 150

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        when (LocalCardSizeMode.current) {
            CardSizeMode.Wrap -> FullGauge(card, snapshot, modifier)
            CardSizeMode.Fixed -> AdaptiveGauge(card, snapshot, modifier)
        }
    }

    @Composable
    private fun AdaptiveGauge(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        val widthName = remember { "__gauge_w_${UUID.randomUUID()}" }
        val heightName = remember { "__gauge_h_${UUID.randomUUID()}" }
        val widthExpr =
            RemoteFloat.createNamedRemoteFloatExpression(widthName, RemoteState.Domain.User) {
                componentWidth()
            }
        val heightExpr =
            RemoteFloat.createNamedRemoteFloatExpression(heightName, RemoteState.Domain.User) {
                componentHeight()
            }
        val isAboveChip = widthExpr.ge(80.rdp.toPx())
        val isWideThin = widthExpr.gt(heightExpr * 1.5f.rf)

        RemoteBox(modifier = modifier.fillMaxSize()) {
            // Materialise both named expressions in the document. The
            // state-layouts below read them via derived booleans; the
            // alpha010 quirk (#224) means the floats won't reach the
            // captured doc unless something visible references them.
            RemoteText(
                text = widthExpr.toRemoteString(IntFormat),
                color = Color.Transparent.rc,
            )
            RemoteText(
                text = heightExpr.toRemoteString(IntFormat),
                color = Color.Transparent.rc,
            )

            RemoteStateLayout(isAboveChip) { hasRoom ->
                if (!hasRoom) {
                    CompactStateChip(card, snapshot)
                } else {
                    RemoteStateLayout(isWideThin) { wide ->
                        if (wide) {
                            RemoteHaGaugeWide(
                                gaugeData(card, snapshot),
                                modifier = RemoteModifier.fillMaxSize(),
                            )
                        } else {
                            RemoteHaGaugeCompact(
                                gaugeData(card, snapshot),
                                modifier = RemoteModifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun FullGauge(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        RemoteHaGauge(gaugeData(card, snapshot), modifier = modifier)
    }
}

/** Build the [HaGaugeData] payload from card config + live snapshot. */
@Composable
private fun gaugeData(card: CardConfig, snapshot: HaSnapshot): HaGaugeData {
    val entityId = card.raw["entity"]?.jsonPrimitive?.content
    val entity = entityId?.let { snapshot.states[it] }
    val name =
        card.raw["name"]?.jsonPrimitive?.content
            ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
            ?: entityId
            ?: "(no entity)"
    val unit =
        card.raw["unit"]?.jsonPrimitive?.content
            ?: entity?.attributes?.get("unit_of_measurement")?.jsonPrimitive?.content
    val min = card.raw["min"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
    val max = card.raw["max"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 100.0
    val value = entity?.state?.toDoubleOrNull() ?: min
    val severity = parseSeverity(card.raw["severity"] as? JsonObject, value)
    val tapCfg = card.raw["tap_action"]?.jsonObject
    val tapAction =
        if (tapCfg != null) parseHaAction(tapCfg, entityId) else defaultTapActionFor(entityId)
    return HaGaugeData(
        entityId = entityId,
        name = name,
        valueText = LiveValues.state(entityId, formatState(entity)),
        unit = unit,
        value = LiveValues.numericState(entityId, value.toFloat()),
        min = min,
        max = max,
        severity = severity,
        tapAction = tapAction,
    )
}

private val IntFormat = DecimalFormat("0")

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
    val bands =
        listOfNotNull(
            green?.let { HaGaugeSeverity.Normal to it },
            yellow?.let { HaGaugeSeverity.Warning to it },
            red?.let { HaGaugeSeverity.Critical to it },
        )
    if (bands.isEmpty()) return HaGaugeSeverity.None
    val ascending = bands.sortedBy { it.second }
    return ascending.lastOrNull { value >= it.second }?.first ?: HaGaugeSeverity.None
}
