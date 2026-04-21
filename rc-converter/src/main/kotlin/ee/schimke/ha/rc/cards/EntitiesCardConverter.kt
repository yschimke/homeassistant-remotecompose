package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.toTyped
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.LiveBindings
import ee.schimke.ha.rc.components.HaEntitiesData
import ee.schimke.ha.rc.components.HaEntityRowData
import ee.schimke.ha.rc.components.HaToggleAccent
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

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        val title = card.raw["title"]?.jsonPrimitive?.content
        val entries: List<JsonElement> = card.raw["entities"]?.jsonArray ?: emptyList()

        val rows = entries.mapNotNull { el ->
            val (eid, row) = normalize(el)
            val entity = eid?.let { snapshot.states[it] }
            val name = row?.get("name")?.jsonPrimitive?.content
                ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
                ?: eid ?: "—"
            val tapCfg = row?.get("tap_action")?.jsonObject
            val tapAction = if (tapCfg != null) parseHaAction(tapCfg, eid)
            else defaultTapActionFor(eid)
            HaEntityRowData(
                name = name.rs,
                state = LiveBindings.state(entity, formatState(entity)),
                icon = HaIconMap.resolve(row?.get("icon")?.jsonPrimitive?.content, entity),
                accent = HaToggleAccent(
                    activeAccent = HaStateColor.activeFor(entity).rc,
                    inactiveAccent = HaStateColor.inactiveFor(entity).rc,
                    isOn = LiveBindings.isOn(entity),
                    initiallyOn = entity?.toTyped()?.isActive == true,
                ),
                tapAction = tapAction,
            )
        }
        RemoteHaEntities(HaEntitiesData(title = title?.rs, rows = rows), modifier = modifier)
    }

    private fun normalize(el: JsonElement): Pair<String?, JsonObject?> = when (el) {
        is JsonPrimitive -> el.content to null
        is JsonObject -> (el["entity"]?.jsonPrimitive?.content) to el
        else -> null to null
    }
}
