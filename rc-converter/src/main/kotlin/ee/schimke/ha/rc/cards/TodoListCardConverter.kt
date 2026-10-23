package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.runtime.Composable
import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.cardDataSignature
import ee.schimke.ha.rc.cardEntityIds
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaTodoItem
import ee.schimke.ha.rc.components.HaTodoListData
import ee.schimke.ha.rc.components.MinTouchTargetDp
import ee.schimke.ha.rc.components.RemoteHaSummaryLine
import ee.schimke.ha.rc.components.RemoteHaTodoList
import ee.schimke.ha.rc.components.RemoteHaTodoListIdentity
import ee.schimke.ha.rc.components.cardText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `todo-list` card. HA carries todo items in the entity's `items` attribute (each item: {summary,
 * status: needs_action | completed}). Tapping a row fires `todo.update_item` with the entity id,
 * the item's summary as the lookup key, and the *flipped* status. Players without a service-call
 * channel will leave the row visually unchanged after a tap (no in-document optimistic flip yet —
 * would need a MutableRemoteString per item, follow-up).
 */
class TodoListCardConverter : CardConverter {
  override val cardType: String = CardTypes.TODO_LIST

  // Baked, non-bindable content (see CardConverter.dataSignature):
  // re-encode when any referenced entity's snapshot data moves.
  override fun dataSignature(card: CardConfig, snapshot: HaSnapshot): String =
    cardDataSignature(cardEntityIds(card), snapshot)

  /**
   * The card's height for its items: 12dp insets, the title, and each non-empty section's caption
   * over its 48dp item rows (touch targets stacked edge to edge), 8dp apart. A host that captures
   * at this height shows every row.
   */
  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int {
    val data = todoData(card, snapshot)
    val sections =
      listOf(data.activeItems.size, data.completedItems.size)
        .filter { it > 0 }
        .sumOf { rows -> GAP_DP + CAPTION_DP + GAP_DP + rows * MinTouchTargetDp }
    val body = if (sections > 0) sections else GAP_DP + EMPTY_LABEL_DP
    return INSETS_DP + TITLE_DP + body
  }

  /**
   * A to-do list renders from a card design: how many items are active and completed is the
   * structure; the title and every summary are bindings (`card.active.<i>.summary`,
   * `card.done.<i>.summary`). A tap on an item is matched against the template's own items and
   * rebuilt from the live item at that position.
   */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? {
    if (!setOf("type", "entity", "title").containsAll(card.raw.keys)) return null
    val entityId = card.raw["entity"]?.jsonPrimitive?.content ?: return null
    if (CardDesignSlots.domainOf(entityId) != "todo" || entityId !in snapshot.states) return null
    val data = todoData(card, snapshot)
    return DesignProperties.build {
      enum("active", data.activeItems.size)
      enum("done", data.completedItems.size)
    }
  }

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? {
    val active = properties["active"]?.toIntOrNull() ?: return null
    val done = properties["done"]?.toIntOrNull() ?: return null
    val slot = CardDesignSlots.slotId("todo", 0)
    val items =
      kotlinx.serialization.json.buildJsonArray {
        repeat(active) { i ->
          addJsonObject {
            put("summary", "Item a$i")
            put("status", "needs_action")
          }
        }
        repeat(done) { i ->
          addJsonObject {
            put("summary", "Item d$i")
            put("status", "completed")
          }
        }
      }
    val entity =
      CardDesignSlots.templateEntity(slot, placeholderState = "0").let {
        it.copy(attributes = JsonObject(it.attributes + ("items" to items)))
      }
    val card =
      CardConfig(
        type = cardType,
        raw =
          buildJsonObject {
            put("type", cardType)
            put("entity", slot)
          },
      )
    return DesignTemplateInput(card, HaSnapshot(states = mapOf(slot to entity)))
  }

  override fun designBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings {
    val data = todoData(card, snapshot)
    return CardSnapshotBindings(
      buildMap {
        put("card.title", data.title)
        put("card.summary", todoSummary(data))
        data.activeItems.forEachIndexed { i, item -> put("card.active.$i.summary", item.summary) }
        data.completedItems.forEachIndexed { i, item -> put("card.done.$i.summary", item.summary) }
      },
      emptyMap(),
      emptyMap(),
      emptyMap(),
    )
  }

  override fun designAction(
    card: CardConfig,
    snapshot: HaSnapshot?,
    slot: Int,
    templateAction: HaAction,
  ): HaAction? {
    snapshot ?: return null
    val input = designTemplate(designProperties(card, snapshot) ?: return null) ?: return null
    val placeholder = todoData(input.card, input.snapshot)
    val live = todoData(card, snapshot)
    placeholder.activeItems
      .indexOfFirst { it.tapAction == templateAction }
      .takeIf { it >= 0 }
      ?.let {
        return live.activeItems.getOrNull(it)?.tapAction
      }
    placeholder.completedItems
      .indexOfFirst { it.tapAction == templateAction }
      .takeIf { it >= 0 }
      ?.let {
        return live.completedItems.getOrNull(it)?.tapAction
      }
    return null
  }

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val data = todoData(card, snapshot)

    when (LocalCardSizeMode.current) {
      CardSizeMode.Wrap -> RemoteHaTodoList(data, modifier = modifier)
      // Narrow → "N left" identity counter; wider → the full
      // active/completed list. Single width gate (#224).
      CardSizeMode.Fixed ->
        BulkTiers(
          modifier,
          summary = {
            RemoteHaSummaryLine(
              cardText("title", data.title),
              cardText("summary", todoSummary(data)),
            )
          },
          identity = { RemoteHaTodoListIdentity(data, RemoteModifier.fillMaxWidth()) },
          full = { RemoteHaTodoList(data, RemoteModifier.fillMaxWidth()) },
        )
    }
  }
}

/**
 * Build the `todo.update_item` call-service action for one row. The service signature on the HA
 * side is:
 *
 * service: todo.update_item target: { entity_id } data: { item: <summary>, status: needs_action |
 * completed }
 *
 * The `item:` field is a positional lookup — todo entities address items by their visible summary,
 * not by an opaque id.
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
    serviceData =
      buildJsonObject {
        put("item", JsonPrimitive(summary))
        put("status", JsonPrimitive(newStatus))
      },
  )
}

/** The list's data for [card] and [snapshot]: what it draws, and what a template binds. */
private fun todoData(card: CardConfig, snapshot: HaSnapshot): HaTodoListData {
  val entityId = card.raw["entity"]?.jsonPrimitive?.content
  val entity = entityId?.let { snapshot.states[it] }
  val title =
    card.raw["title"]?.jsonPrimitive?.content
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
    val item = HaTodoItem(summary = summary, tapAction = tap)
    if (isCompleted) completed += item else active += item
  }
  return HaTodoListData(title = title, activeItems = active, completedItems = completed)
}

// RemoteHaTodoList's layout, for naturalHeightDp: vertical insets, the line heights of the 17sp
// title (20dp), the 12sp section caption (14dp) and the 13sp "No items" label (16dp), each with 2dp
// to spare for a taller theme typeface, and the column's gap.
private const val INSETS_DP = 24
private const val TITLE_DP = 22
private const val CAPTION_DP = 16
private const val EMPTY_LABEL_DP = 18
private const val GAP_DP = 8

/**
 * The Wear Small summary line: how many are left and the next, or that the list is done or empty. A
 * `card.summary` binding, so a design template shows the real list's line rather than the
 * template's.
 */
private fun todoSummary(data: HaTodoListData): String {
  val next = data.activeItems.firstOrNull()
  return when {
    next != null -> "${data.activeItems.size} left · ${next.summary}"
    data.completedItems.isNotEmpty() -> "All done"
    else -> "No items"
  }
}
