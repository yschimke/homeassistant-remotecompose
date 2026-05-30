package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
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
                // Height-axis ladder: the list's natural layout is a
                // vertical stack of 44 dp rows, so it's *height* that
                // decides how much of it fits — break on that, not
                // width (a wide-but-short cell still can't stack rows).
                // Single axis keeps us clear of the alpha010 2-D-gate
                // collapse (#224, see GaugeCardConverter).
                RemoteSizeBreakpoint(
                    thresholdsDp = intArrayOf(96, 200),
                    modifier = modifier,
                    axis = BreakpointAxis.Height,
                ) { tier ->
                    when (tier) {
                        // Tier 0: too short to stack rows — reflow the
                        // column of rows into a horizontal strip of icon
                        // cells (the "column of N rows -> row of N icons"
                        // reflow). Each cell still carries icon + name +
                        // state, so identity survives the repack.
                        0 -> IconStrip(card, snapshot, RemoteModifier.fillMaxWidth())
                        // Tier 1: some vertical room — first three rows,
                        // no title chrome.
                        1 ->
                            FullList(
                                card,
                                snapshot,
                                RemoteModifier.fillMaxWidth(),
                                maxRows = 3,
                                forceTitle = false,
                            )
                        // Tier 2: roomy — everything (matches Wrap).
                        else ->
                            FullList(
                                card,
                                snapshot,
                                RemoteModifier.fillMaxWidth(),
                                maxRows = Int.MAX_VALUE,
                                forceTitle = true,
                            )
                    }
                }
        }
    }

    /**
     * Wide-thin reflow: the same entities, repacked as a horizontal
     * strip of [RemoteHaGlance] icon cells instead of a vertical list.
     * Reuses the glance component so the cells match the `glance` card
     * exactly. Title chrome is dropped — at this height the strip is
     * all that fits.
     */
    @Composable
    private fun IconStrip(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
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
        RemoteHaGlance(HaGlanceData(title = null, cells = cells), modifier = modifier)
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
