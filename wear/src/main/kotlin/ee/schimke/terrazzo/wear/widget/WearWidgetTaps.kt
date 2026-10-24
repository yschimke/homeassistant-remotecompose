@file:Suppress("RestrictedApi", "RestrictedApiAndroidX")

package ee.schimke.terrazzo.wear.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.remote.creation.compose.action.PendingIntentAction
import androidx.glance.wear.WearWidgetData
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.core.WearWidgetRawContent
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcHostNamedAction
import ee.schimke.composeai.rcplayer.protocol.RcHostNamedActionValue
import ee.schimke.composeai.rcplayer.protocol.RcIntegerConstant
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.rc.components.HA_ACTION_NAME
import ee.schimke.terrazzo.wear.WearMainActivity
import ee.schimke.terrazzo.wearsync.proto.PinnedSection
import ee.schimke.terrazzo.wearsync.proto.PinnedSectionSet

/**
 * Taps on a Wear widget: every one opens the Wear app on the widget's card.
 *
 * A widget can't run a card's own taps. It has no session and no keypad buffer, and the Wear widget
 * host only sends a `PendingIntent` — the `SendPendingIntent` named host action that Remote
 * Compose's `pendingIntentAction` writes for a Wear widget host
 * ([PendingIntentAction.ACTION_NAME]), whose integer value indexes the `PendingIntent`s in the
 * widget's extras under [PENDING_INTENTS]. A card's own taps are `ha` host actions the host
 * ignores, and they cover most of a card (a tile's whole body), so [openingApp] rewrites each of
 * them into that action, naming one activity `PendingIntent` ([openCardIntent]) it puts in the
 * extras.
 *
 * The rewrite is on the widget's final document, so the templates stay shared with the Wear app,
 * whose cards keep their `ha` taps (`WearTapDispatcher`).
 */
internal object WearWidgetTaps {

  /** The index of the widget's one `PendingIntent`: the first a capture would store. */
  const val OPEN_APP_INDEX: Int = 0

  /**
   * The extras key of a widget's `PendingIntent`s, by index: Glance Wear's own
   * (`WearWidgetCapture.PENDING_INTENT_KEY`, internal to it), which `WearWidgetTapsTest` pins.
   */
  const val PENDING_INTENTS: String = "pending_intents"

  /**
   * [document] with each of its `ha` host actions sending the widget's `PendingIntent`
   * [OPEN_APP_INDEX]. Unchanged when it has none; null when it can't be read.
   */
  fun rewrite(document: ByteArray): ByteArray? = runCatching {
    val decoded = RcDocumentCodec.decode(document, RcWireLimits())
    val texts = decoded.operations.filterIsInstance<RcTextData>().associateBy { it.id }
    val haNames = texts.values.filter { it.text == HA_ACTION_NAME }.mapTo(HashSet()) { it.id }
    val haTaps =
      decoded.operations.filterIsInstance<RcHostNamedAction>().filter { it.nameTextId in haNames }
    if (haTaps.isEmpty()) return@runCatching document
    // The ids stay; only what they hold changes, so nothing new needs an id. The name text becomes
    // `SendPendingIntent`, and each tap's payload (its action's JSON, which only taps read) the
    // integer index of the widget's PendingIntent: the host reads an Integer value only.
    val payloads =
      haTaps
        .mapNotNull { (it.value as? RcHostNamedActionValue.TextValue)?.valueId }
        .filterTo(HashSet()) { it in texts }
    val operations =
      decoded.operations.map { op ->
        when {
          op is RcTextData && op.id in haNames -> RcTextData(op.id, PendingIntentAction.ACTION_NAME)
          op is RcTextData && op.id in payloads -> RcIntegerConstant(op.id, OPEN_APP_INDEX)
          op is RcHostNamedAction && op.nameTextId in haNames -> {
            val payload = (op.value as? RcHostNamedActionValue.TextValue)?.valueId
            if (payload in payloads)
              RcHostNamedAction(op.nameTextId, RcHostNamedActionValue.IntegerValue(payload!!))
            else op
          }
          else -> op
        }
      }
    RcDocumentCodec.encode(decoded.copy(operations = operations))
  }
    .getOrNull()

  /** [content] whose taps send [intent]: see [rewrite]. Its extras keep everything else. */
  fun openingApp(content: WearWidgetRawContent, intent: PendingIntent): WearWidgetRawContent {
    val document = rewrite(content.rcDocument) ?: return content
    val extras =
      Bundle(content.extras).apply {
        putParcelable(
          PENDING_INTENTS,
          Bundle().apply { putParcelable(OPEN_APP_INDEX.toString(), intent) },
        )
      }
    return WearWidgetRawContent(document, extras, content.widgetTitle)
  }

  /**
   * The activity [PendingIntent] a widget [instanceId] showing [cardKey] opens: the Wear app on
   * that card ([WearMainActivity.EXTRA_WIDGET_CARD_KEY]), or just the app when [cardKey] is null.
   */
  fun openCardIntent(context: Context, instanceId: Int, cardKey: String?): PendingIntent =
    PendingIntent.getActivity(
      context,
      instanceId,
      Intent(context, WearMainActivity::class.java).apply {
        action = WearMainActivity.ACTION_OPEN_WIDGET_CARD
        // A PendingIntent's identity ignores extras: one per widget, so each keeps its own card.
        data = Uri.parse("terrazzo-wear://widget/$instanceId")
        cardKey?.let { putExtra(WearMainActivity.EXTRA_WIDGET_CARD_KEY, it) }
        // The running app moves to the card rather than stacking a second copy.
        addFlags(
          Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
      },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * This widget data with its taps opening the Wear app on [cardKey]'s card: see [WearWidgetTaps].
 */
internal fun WearWidgetData.openingApp(cardKey: String?): WearWidgetData {
  val inner = this
  return object : WearWidgetData {
    override suspend fun captureRawContent(
      context: Context,
      params: WearWidgetParams,
      isPreview: Boolean,
    ): WearWidgetRawContent {
      val content = inner.captureRawContent(context, params, isPreview)
      return WearWidgetTaps.openingApp(
        content,
        WearWidgetTaps.openCardIntent(context, params.instanceId.id, cardKey),
      )
    }
  }
}

/**
 * The pinned section a widget's card [cardKey] is in ([widgetCardOptions] names a section card by
 * its section), or null for a pinned card or one the phone no longer sends.
 */
internal fun widgetCardSection(cardKey: String, sections: PinnedSectionSet?): PinnedSection? =
  sections?.sections.orEmpty().firstOrNull { section ->
    section.cards.any { sectionCardKey(section, it) == cardKey }
  }
