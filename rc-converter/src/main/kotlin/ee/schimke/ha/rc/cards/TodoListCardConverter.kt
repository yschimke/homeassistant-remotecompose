package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaTodoItem
import ee.schimke.ha.rc.components.HaTodoListData
import ee.schimke.ha.rc.components.RemoteHaTodoList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `todo-list` card. HA carries todo items in the entity's `items`
 * attribute (each item: {summary, status: needs_action | completed}).
 * Tapping a row fires `todo.update_item` with the entity id, the
 * item's summary as the lookup key, and the *flipped* status. Players
 * without a service-call channel will leave the row visually
 * unchanged after a tap (no in-document optimistic flip yet — would
 * need a MutableRemoteString per item, follow-up).
 */
class TodoListCardConverter : CardConverter {
    override val cardType: String = CardTypes.TODO_LIST

    override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 220

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        val entityId = card.raw["entity"]?.jsonPrimitive?.content
        val entity = entityId?.let { snapshot.states[it] }
        val title = card.raw["title"]?.jsonPrimitive?.content
            ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
            ?: entityId
            ?: "To-do list"

        val items = entity?.attributes?.get("items") as? JsonArray ?: JsonArray(emptyList())
        val active = mutableListOf<HaTodoItem>()
        val completed = mutableListOf<HaTodoItem>()
        items.forEach { el ->
            val obj = el as? JsonObject ?: return@forEach
            val summary = obj["summary"]?.jsonPrimitive?.content ?: return@forEach
            val status = obj["status"]?.jsonPrimitive?.content
            val isCompleted = status == "completed"
            val tap = updateItemAction(entityId, summary, isCompleted)
            val item = HaTodoItem(summary = summary.rs, tapAction = tap)
            if (isCompleted) completed += item else active += item
        }

        RemoteHaTodoList(
            HaTodoListData(
                title = title.rs,
                activeItems = active,
                completedItems = completed,
            ),
            modifier = modifier,
        )
    }
}

/**
 * Build the `todo.update_item` call-service action for one row. The
 * service signature on the HA side is:
 *
 *   service: todo.update_item
 *   target:  { entity_id }
 *   data:    { item: <summary>, status: needs_action | completed }
 *
 * The `item:` field is a positional lookup — todo entities address
 * items by their visible summary, not by an opaque id.
 */
private fun updateItemAction(
    entityId: String?,
    summary: String,
    currentlyCompleted: Boolean,
): HaAction {
    if (entityId == null) return HaAction.None
    val newStatus = if (currentlyCompleted) "needs_action" else "completed"
    return HaAction.CallService(
        domain = "todo",
        service = "update_item",
        entityId = entityId,
        serviceData = buildJsonObject {
            put("item", JsonPrimitive(summary))
            put("status", JsonPrimitive(newStatus))
        },
    )
}
