package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.toTyped
import ee.schimke.ha.rc.BreakpointAxis
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.RemoteSizeBreakpoint
import ee.schimke.ha.rc.components.HaEntitiesData
import ee.schimke.ha.rc.components.HaEntityRowData
import ee.schimke.ha.rc.components.HaGlanceCellData
import ee.schimke.ha.rc.components.HaGlanceData
import ee.schimke.ha.rc.components.HaToggleAccent
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.LocalSupportsFlowLayout
import ee.schimke.ha.rc.components.RemoteHaEntities
import ee.schimke.ha.rc.components.RemoteHaGlance
import ee.schimke.ha.rc.defaultTapActionFor
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.rc.icons.HaIconMap
import ee.schimke.ha.rc.parseHaAction
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * HA `entities` card — a titled list of entity rows. Each entry is
 * either a bare `entity_id` or `{ entity, name?, icon?, tap_action? }`.
 */
class EntitiesCardConverter : CardConverter {
    override val cardType: String = CardTypes.ENTITIES

    override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int {
        val rows = card.raw["entities"]?.jsonArray?.size ?: 0
        val title = if (card.raw["title"] != null) 36 else 0
        return title + 16 + 44 * rows // title + top-pad + 44dp per entity row.
    }

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        when (LocalCardSizeMode.current) {
            CardSizeMode.Wrap -> FullList(card, snapshot, modifier, maxRows = Int.MAX_VALUE)
            CardSizeMode.Fixed ->
                // Glance Wear slots are short-and-wide and reject
                // FlowLayout: a vertical list of rows overflows the slot
                // (rows collide off the bottom edge). Reflow straight to
                // the capped icon strip — the watch's "single row of the
                // first N icons" compact tier — without a live
                // breakpoint. The launcher, which supports flow, keeps
                // the width gate below.
                if (!LocalSupportsFlowLayout.current) {
                    IconStrip(card, snapshot, modifier.fillMaxSize())
                } else {
                    // Width-axis reflow: as the widget is dragged wider,
                    // the vertical column of rows repacks into a grid of
                    // icon cells ("column of N rows -> grid of N icons").
                    //
                    // Width, not height, for two reasons: (1) it's the
                    // axis every Fixed-mode surface pins (the matrix
                    // preview and the launcher both fix width and let
                    // height follow), so the gate fires consistently and
                    // is reviewable in the matrix; a height gate reads an
                    // unstable wrap-height there. (2) SINGLE threshold — a
                    // multi-rung ladder lowers to nested
                    // RemoteStateLayouts, which alpha010 collapses to tier
                    // 0 at playback (#224, see GaugeCardConverter). The
                    // grid self-fills the large end (it grows rows to fill
                    // the cell height), so no second gate is needed.
                    RemoteSizeBreakpoint(
                        thresholdsDp = intArrayOf(260),
                        modifier = modifier,
                        axis = BreakpointAxis.Width,
                    ) { tier ->
                        when (tier) {
                            // Tier 0 (w < 260): the natural vertical list,
                            // same as Wrap — narrow cells stack rows.
                            0 ->
                                FullList(
                                    card,
                                    snapshot,
                                    RemoteModifier.fillMaxWidth(),
                                    maxRows = Int.MAX_VALUE,
                                    forceTitle = true,
                                )
                            // Tier 1 (w >= 260): wide enough to lay the
                            // entities out side by side — reflow into a
                            // grid of icon | name | state cells that fills
                            // the cell height. Each cell keeps its
                            // identity, so nothing is dropped, just
                            // repacked.
                            else -> IconStrip(card, snapshot, RemoteModifier.fillMaxSize())
                        }
                    }
                }
        }
    }

    /**
     * Reflow tier: the same entities, repacked as a [RemoteHaGlance] grid
     * of icon cells instead of a vertical list. Reuses the glance
     * component so the cells match the `glance` card exactly and the
     * whole multi-entity family shares one reflow. The title is retained
     * (cheap P2) and the grid fills the cell height rather than gluing a
     * single row to the top over a blank half-cell (Principle 8).
     */
    @Composable
    private fun IconStrip(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        val title = card.raw["title"]?.jsonPrimitive?.content
        val entries: List<JsonElement> = card.raw["entities"]?.jsonArray ?: emptyList()
        val cells =
            entries.mapNotNull { el ->
                val (eid, row) = normalize(el)
                val entity = eid?.let { snapshot.states[it] }
                val name =
                    row?.get("name")?.jsonPrimitive?.content
                        ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
                        ?: eid
                        ?: "—"
                val tapCfg = row?.get("tap_action")?.jsonObject
                val tapAction =
                    if (tapCfg != null) parseHaAction(tapCfg, eid) else defaultTapActionFor(eid)
                val isActive = entity?.toTyped()?.isActive
                HaGlanceCellData(
                    entityId = eid,
                    name = name,
                    state = LiveValues.state(eid, formatState(entity)),
                    icon = HaIconMap.resolve(row?.get("icon")?.jsonPrimitive?.content, entity),
                    accent =
                        HaToggleAccent(
                            activeAccent = HaStateColor.activeFor(entity).rc,
                            inactiveAccent = HaStateColor.inactiveFor(entity).rc,
                            initiallyOn = isActive ?: false,
                            toggleable = isActive != null,
                        ),
                    tapAction = tapAction,
                )
            }
        RemoteHaGlance(
            HaGlanceData(title = title, cells = cells),
            modifier = modifier,
            fillHeight = true,
        )
    }

    @Composable
    private fun FullList(
        card: CardConfig,
        snapshot: HaSnapshot,
        modifier: RemoteModifier,
        maxRows: Int,
        forceTitle: Boolean = true,
    ) {
        val title = if (forceTitle) card.raw["title"]?.jsonPrimitive?.content else null
        val entries: List<JsonElement> = card.raw["entities"]?.jsonArray ?: emptyList()

        val rows =
            entries.take(maxRows).mapNotNull { el ->
                val (eid, row) = normalize(el)
                val entity = eid?.let { snapshot.states[it] }
                val name =
                    row?.get("name")?.jsonPrimitive?.content
                        ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
                        ?: eid
                        ?: "—"
                val tapCfg = row?.get("tap_action")?.jsonObject
                val tapAction =
                    if (tapCfg != null) parseHaAction(tapCfg, eid) else defaultTapActionFor(eid)
                val isActive = entity?.toTyped()?.isActive
                HaEntityRowData(
                    entityId = eid,
                    name = name,
                    state = LiveValues.state(eid, formatState(entity)),
                    icon = HaIconMap.resolve(row?.get("icon")?.jsonPrimitive?.content, entity),
                    accent =
                        HaToggleAccent(
                            activeAccent = HaStateColor.activeFor(entity).rc,
                            inactiveAccent = HaStateColor.inactiveFor(entity).rc,
                            initiallyOn = isActive ?: false,
                            toggleable = isActive != null,
                        ),
                    tapAction = tapAction,
                )
            }
        RemoteHaEntities(HaEntitiesData(title = title, rows = rows), modifier = modifier)
    }

    private fun normalize(el: JsonElement): Pair<String?, JsonObject?> = when (el) {
        is JsonPrimitive -> el.content to null
        is JsonObject -> (el["entity"]?.jsonPrimitive?.content) to el
        else -> null to null
    }
}
