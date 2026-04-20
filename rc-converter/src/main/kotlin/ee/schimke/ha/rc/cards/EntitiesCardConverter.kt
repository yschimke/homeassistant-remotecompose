package ee.schimke.ha.rc.cards

import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.HaStateColor
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rs
import ee.schimke.ha.rc.components.RemoteHaEntities
import ee.schimke.ha.rc.components.RemoteHaEntityRow
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
 * HA `entities` card — a titled list of entity rows. Each row can be
 * specified as either a string (bare `entity_id`) or an object
 * `{ entity: ..., name?, icon?, tap_action? }`.
 */
class EntitiesCardConverter : CardConverter {
    override val cardType: String = CardTypes.ENTITIES

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot) {
        val title = card.raw["title"]?.jsonPrimitive?.content
        val entries: List<JsonElement> = card.raw["entities"]?.jsonArray ?: emptyList()

        RemoteHaEntities(title = title?.rs) {
            entries.forEach { el ->
                val (eid, row) = normalize(el)
                val entity = eid?.let { snapshot.states[it] }
                val iconOverride = row?.get("icon")?.jsonPrimitive?.content
                val nameOverride = row?.get("name")?.jsonPrimitive?.content
                val name = nameOverride
                    ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
                    ?: eid ?: "—"
                val tapCfg = row?.get("tap_action")?.jsonObject
                val tapAction = if (tapCfg != null) parseHaAction(tapCfg, eid)
                else defaultTapActionFor(eid)

                RemoteHaEntityRow(
                    name = name.rs,
                    state = formatState(entity).rs,
                    icon = HaIconMap.resolve(iconOverride, entity),
                    accent = HaStateColor.resolve(entity).rc,
                    tapAction = tapAction,
                )
            }
        }
    }

    private fun normalize(el: JsonElement): Pair<String?, JsonObject?> = when (el) {
        is JsonPrimitive -> el.content to null
        is JsonObject -> (el["entity"]?.jsonPrimitive?.content) to el
        else -> null to null
    }
}
