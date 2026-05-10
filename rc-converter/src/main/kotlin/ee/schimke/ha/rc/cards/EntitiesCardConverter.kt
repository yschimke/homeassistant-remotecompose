package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.toTyped
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.RemoteSizeBreakpoint
import ee.schimke.ha.rc.components.HaEntitiesData
import ee.schimke.ha.rc.components.HaEntityRowData
import ee.schimke.ha.rc.components.HaToggleAccent
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.RemoteHaEntities
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
                RemoteSizeBreakpoint(
                    thresholdsDp = intArrayOf(160, 240),
                    modifier = modifier,
                ) { tier ->
                    // Tier 0: cramped — top row only.
                    // Tier 1: medium — first three rows, no title chrome.
                    // Tier 2: roomy — everything (matches Wrap output).
                    val (rows, keepTitle) =
                        when (tier) {
                            0 -> 1 to false
                            1 -> 3 to false
                            else -> Int.MAX_VALUE to true
                        }
                    FullList(
                        card,
                        snapshot,
                        RemoteModifier.fillMaxWidth(),
                        maxRows = rows,
                        forceTitle = keepTitle,
                    )
                }
        }
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
