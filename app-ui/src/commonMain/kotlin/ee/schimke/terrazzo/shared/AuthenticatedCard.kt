package ee.schimke.terrazzo.shared

import androidx.compose.runtime.Composable
import ee.schimke.ha.client.HaClient
import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import kotlinx.serialization.json.*

@Composable
internal fun AuthenticatedCard(
  card: CardConfig,
  snapshot: HaSnapshot,
  client: HaClient,
  style: String,
  dark: Boolean,
  onMoreInfo: (String) -> Unit,
  onAction: (String) -> Unit,
  onToggle: (String) -> Unit,
  onService: (String, String, JsonObject) -> Unit,
) {
  CardCluster(card) { leaf ->
    val conversation = conversationEntity(leaf)
    if (leaf.type == ee.schimke.ha.model.MESSAGE_CARD_TYPE)
      MessageComposeCard(leaf, snapshot, editable = true, onAction = onAction)
    else if (conversation != null)
      ConversationCard(leaf, conversation, snapshot, client, onMoreInfo)
    else {
      val fallback: @Composable () -> Unit = {
        NativeCard(
          leaf,
          snapshot,
          onMoreInfo = onMoreInfo,
          onService = onService,
          toggle = onToggle,
        )
      }
      // Input helpers and declared actions keep their native controls instead of a generic
      // template.
      val domains =
        CardDesignSlots.slotEntries(leaf).orEmpty().map { it.entityId.substringBefore('.') }
      val nativeControls = domains.any {
        it in setOf("select", "input_select", "text", "input_text", "input_boolean", "fan")
      }
      val tap =
        ((leaf.raw["tap_action"] as? JsonObject)?.get("action") as? JsonPrimitive)?.contentOrNull
      if (
        leaf.type in setOf("tile", "entity", "sensor", "gauge", "button", "entities") &&
          !nativeControls &&
          cardServiceAction(leaf) == null &&
          tap in setOf(null, "toggle", "more-info", "none") &&
          (leaf.raw["features"] as? JsonArray).orEmpty().isEmpty()
      )
        LibraryCard(
          leaf,
          snapshot,
          style,
          dark,
          onToggle,
          onMoreInfo,
          showFallbackNotice = false,
          fallback = fallback,
        )
      else fallback()
    }
  }
}
