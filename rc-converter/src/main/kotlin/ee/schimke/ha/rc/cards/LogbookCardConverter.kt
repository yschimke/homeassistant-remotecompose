package ee.schimke.ha.rc.cards

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.components.HaLogbookData
import ee.schimke.ha.rc.components.HaLogbookEntry
import ee.schimke.ha.rc.components.RemoteHaLogbook
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.rc.icons.HaIconMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * `logbook` card. The dashboard JSON only carries the entity ids — real
 * logbook data lives behind the `/api/logbook` REST endpoint, which the
 * `.rc` document can't fetch at playback. We render one entry per
 * configured entity using its current state as the most-recent
 * "message" line, with the entity's last-changed timestamp where
 * available. Hosts that hydrate logbook history at capture time can
 * extend [HaSnapshot] later; for now this gives a representative slot.
 */
class LogbookCardConverter : CardConverter {
    override val cardType: String = CardTypes.LOGBOOK

    override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int {
        val rows = entityIds(card).size.coerceAtLeast(1)
        val title = if (card.raw["title"] != null) 28 else 0
        return title + 24 + 36 * rows
    }

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        val title = card.raw["title"]?.jsonPrimitive?.content
        val entries = entityIds(card).map { entityId ->
            val entity = snapshot.states[entityId]
            val name = entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
                ?: entityId
            HaLogbookEntry(
                name = name.rs,
                message = formatState(entity).rs,
                whenText = formatRelative(entity?.lastChanged).rs,
                icon = HaIconMap.resolve(null, entity),
            )
        }
        RemoteHaLogbook(
            HaLogbookData(
                title = title?.rs,
                entries = entries,
            ),
            modifier = modifier,
        )
    }
}

private fun entityIds(card: CardConfig): List<String> {
    val arr: JsonArray = card.raw["entities"]?.jsonArray ?: return emptyList()
    return arr.mapNotNull { el ->
        when (el) {
            is JsonObject -> el["entity"]?.jsonPrimitive?.content
            else -> el.jsonPrimitive.content
        }
    }
}

private fun formatRelative(instant: kotlinx.datetime.Instant?): String {
    if (instant == null) return ""
    return instant.toString().substringBefore('T')
}
