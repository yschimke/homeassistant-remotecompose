@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.BreakpointAxis
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.RemoteSizeBreakpoint
import ee.schimke.ha.rc.components.HaGaugeData
import ee.schimke.ha.rc.components.HaGaugeSeverity
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.RemoteHaGauge
import ee.schimke.ha.rc.components.RemoteHaGaugeStacked
import ee.schimke.ha.rc.components.RemoteHaGaugeWide
import ee.schimke.ha.rc.defaultTapActionFor
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.rc.parseHaAction
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
 * In [CardSizeMode.Fixed] the converter encodes a 2-tier ladder driven
 * by the runtime canvas (mirrors the gauge ladder in
 * `docs/architecture/adaptive-card-layouts.md` §gauge):
 *
 *   * **Too short for stacked** (`h < 70 dp`) → [RemoteHaGaugeWide].
 *     Arc on the left, value · name on the right. The Wear S
 *     200×60 chip is the canonical hit; cells with no vertical room
 *     for an arc-as-backdrop layout reach for the row instead.
 *   * **Stacked Box overlay** (otherwise) → [RemoteHaGaugeStacked].
 *     Half-arc as a backdrop with value · name · range overlaid at
 *     the bottom of the box. Covers everything from a 1×1 launcher
 *     chip up to a 3×2 dashboard tile and Wear L. The secondary
 *     text lines ellipsise on cells that can't fit them.
 *
 * Tier selection runs through the shared
 * [ee.schimke.ha.rc.RemoteSizeBreakpoint] on the **height** axis (the
 * discriminating cell — the 200×60 Wear S slot — pins height). Going
 * through the shared helper rather than a hand-rolled `RemoteStateLayout`
 * is what gives the gauge the `immediateSwap` workaround for the #309
 * overlay artifact; without it both tiers paint on top of each other
 * (double arc + double value). A single threshold keeps it off the
 * nested-state-layout collapse path (#224).
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
        val data = gaugeData(card, snapshot)
        // Single height gate — short cells (Wear S) get the side-by-side
        // Wide row, everything else gets the Stacked Box overlay. Routed
        // through the shared [RemoteSizeBreakpoint] so the gauge inherits
        // the arc-dial family's `immediateSwap` workaround for the #309
        // overlay artifact (without it the raw `RemoteStateLayout` paints
        // both the Wide and Stacked tiers on top of each other — double
        // arc + double value — making the family un-reviewable).
        //
        // Height is the right axis here: the discriminating cell is the
        // 200×60 Wear S slot, which pins height, unlike the width-pinned
        // launcher reflows. A single threshold avoids the nested
        // state-layout collapse (#224).
        RemoteSizeBreakpoint(
            thresholdsDp = intArrayOf(MinStackedHeightDp),
            axis = BreakpointAxis.Height,
            modifier = modifier.fillMaxSize(),
        ) { tier ->
            when (tier) {
                0 -> RemoteHaGaugeWide(data, RemoteModifier.fillMaxSize())
                else -> RemoteHaGaugeStacked(data, RemoteModifier.fillMaxSize())
            }
        }
    }

    private companion object {
        const val MinStackedHeightDp = 70
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
