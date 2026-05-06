package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.components.HaTodoListData
import ee.schimke.ha.rc.components.RemoteHaTodoList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * `todo-list` card. HA carries todo items in the entity's `items`
 * attribute (each item: {summary, status: needs_action | completed}).
 * The converter splits them into active and completed sections; mutating
 * items via `todo.update_item` is a follow-up.
 */
class TodoListCardConverter : CardConverter {
    override val cardType: String = CardTypes.TODO_LIST

    override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int {
        val entityId = card.raw["entity"]?.jsonPrimitive?.content ?: return 120
        val items = ((card.raw["entity"]?.jsonPrimitive?.content
            ?.let { id -> emptyList<Any>() }) ?: emptyList<Any>())
        // Without a snapshot at compute time we estimate generously.
        return 200
    }

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        val entityId = card.raw["entity"]?.jsonPrimitive?.content
        val entity = entityId?.let { snapshot.states[it] }
        val title = card.raw["title"]?.jsonPrimitive?.content
            ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
            ?: entityId
            ?: "To-do list"

        val items = entity?.attributes?.get("items") as? JsonArray ?: JsonArray(emptyList())
        val active = mutableListOf<String>()
        val completed = mutableListOf<String>()
        items.forEach { el ->
            val obj = el as? JsonObject ?: return@forEach
            val summary = obj["summary"]?.jsonPrimitive?.content ?: return@forEach
            val status = obj["status"]?.jsonPrimitive?.content
            if (status == "completed") completed += summary else active += summary
        }

        RemoteHaTodoList(
            HaTodoListData(
                title = title.rs,
                activeItems = active.map { it.rs },
                completedItems = completed.map { it.rs },
            ),
            modifier = modifier,
        )
    }
}
