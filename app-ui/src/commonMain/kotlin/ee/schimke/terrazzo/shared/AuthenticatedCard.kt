package ee.schimke.terrazzo.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.ImageBitmap
import ee.schimke.ha.client.HaClient
import ee.schimke.ha.client.design.UsageSource
import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.remoteComposeCardStyle
import ee.schimke.ha.model.remoteComposeInnerCard
import ee.schimke.ha.rc.cards.defaultRegistry
import kotlinx.serialization.json.*

@Composable
internal fun AuthenticatedCard(
  card: CardConfig,
  snapshot: HaSnapshot,
  client: HaClient,
  designCache: DashboardLoadCache<LibraryDesignKey, ByteArray?>,
  pictureCache: DashboardLoadCache<EntityPictureKey, ImageBitmap>,
  style: String,
  dark: Boolean,
  onMoreInfo: (String) -> Unit,
  onAction: (String) -> Unit,
  onToggle: (String) -> Unit,
  onService: (String, String, JsonObject) -> Unit,
  // A preview (an agent's draft): no control may reach Home Assistant, not even ones that call the
  // client themselves.
  readOnly: Boolean = false,
) {
  DashboardCardTheme(style, dark) {
    CardCluster(card, snapshot) { outer ->
      // A Remote Compose card is its inner card, drawn from a card design in the style it names.
      val leaf = remoteComposeInnerCard(outer) ?: outer
      val leafStyle = remoteComposeCardStyle(outer) ?: style
      val drawLeaf: @Composable () -> Unit = {
        // Template usage (opt-in, AppCardUsage): a design-backed card reports once its lookup
        // settles; everything else is drawn by the app's own code.
        val drawnByApp: @Composable () -> Unit = {
          LaunchedEffect(leaf, leafStyle) {
            AppCardUsage.record(leaf, leafStyle, UsageSource.Converter)
          }
        }
        val conversation = conversationEntity(leaf)
        if (leaf.type == ee.schimke.ha.model.MESSAGE_CARD_TYPE) {
          drawnByApp()
          MessageComposeCard(leaf, snapshot, editable = !readOnly, onAction = onAction)
        } else if (conversation != null) {
          drawnByApp()
          ConversationCard(leaf, conversation, snapshot, client, onMoreInfo, readOnly)
        } else if (isEntityPictureCard(leaf)) {
          drawnByApp()
          EntityPictureCard(
            leaf,
            snapshot.states[(leaf.raw["entity"] as? JsonPrimitive)?.contentOrNull],
            pictureCache,
            onMoreInfo,
          )
        } else {
          val fallback: @Composable () -> Unit = {
            if (isBambuStatus(leaf)) BambuStatusFallback(leaf, snapshot, onMoreInfo)
            else
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
            it in
              setOf(
                "select",
                "input_select",
                "text",
                "input_text",
                "input_boolean",
                "fan",
                "light",
                "switch",
              )
          }
          val tap =
            ((leaf.raw["tap_action"] as? JsonObject)?.get("action") as? JsonPrimitive)
              ?.contentOrNull
          if (
            (leaf.type in setOf("tile", "entity", "sensor", "gauge", "button", "entities") ||
              hasCustomCardDesign(leaf)) &&
              !nativeControls &&
              // An entity Home Assistant no longer has: the built-in card says so, with no lookup.
              !namesMissingEntity(leaf, snapshot) &&
              cardServiceAction(leaf) == null &&
              tap in setOf(null, "toggle", "more-info", "none") &&
              (leaf.raw["features"] as? JsonArray).orEmpty().isEmpty()
          )
            LibraryCard(
              leaf,
              snapshot,
              leafStyle,
              dark,
              onToggle,
              onMoreInfo,
              designCache = designCache,
              requireExactDesign = true,
              onSource = { AppCardUsage.record(leaf, leafStyle, it) },
              fallback = fallback,
            )
          else {
            drawnByApp()
            fallback()
          }
        }
      }
      // The style a Remote Compose card names applies however it is drawn, not only to its design.
      if (leafStyle == style) drawLeaf() else DashboardCardTheme(leafStyle, dark) { drawLeaf() }
    }
  }
}

/**
 * A custom card the shared converters draw (the `custom:ha-bambulab-*` cards, for one): its design
 * is fetched like a built-in card's, rather than showing this host's placeholder.
 */
internal fun hasCustomCardDesign(card: CardConfig): Boolean =
  card.type.startsWith("custom:") && sharedConverters.get(card.type) != null

private val sharedConverters by lazy { defaultRegistry() }

/** Whether [card] names an entity [snapshot]'s states lack, once states have arrived. */
internal fun namesMissingEntity(card: CardConfig, snapshot: HaSnapshot): Boolean =
  snapshot.states.isNotEmpty() &&
    CardDesignSlots.slotEntries(card).orEmpty().any { it.entityId !in snapshot.states }
