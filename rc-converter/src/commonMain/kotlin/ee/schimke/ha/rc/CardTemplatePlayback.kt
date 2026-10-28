package ee.schimke.ha.rc

import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.HaAction

/**
 * Plays a card design template in place of a local capture. [bytes] is a values-free document from
 * the design server (or cache), and [bindings] derives every slot binding from a snapshot, reading
 * "now" from the given [HaClock] (the [LocalHaClock] in scope, when played).
 *
 * Taps are resolved from the card's **local** config, never from the template: the bytes are
 * remote, so the action a template encodes only picks *which* of the card's own actions runs. A tap
 * on a slot's entity (`<domain>.s<N>`) runs [slotTapActions]`[N]` — one per row of a list card — —
 * unless the converter rebuilds a more specific one from the card's config ([slotAction]) — and any
 * other tap runs the card's [tapAction].
 */
class CardTemplatePlayback(
  val bytes: ByteArray,
  val bindings: (snapshot: HaSnapshot, clock: HaClock) -> CardSnapshotBindings,
  val tapAction: HaAction,
  val slotTapActions: List<HaAction> = emptyList(),
  /** A card-specific action for a tap on a slot: see [CardConverter.designAction]. */
  /**
   * The URL to fetch for an image URL the template encodes, or null to refuse it: only the card's
   * own slot pictures resolve (`CardDesignSlots.pictureUrl`).
   */
  val bitmapUrl: (url: String, snapshot: HaSnapshot?) -> String? = { _, _ -> null },
  val slotAction: (snapshot: HaSnapshot?, slot: Int, templateAction: HaAction) -> HaAction? =
    { _, _, _ ->
      null
    },
) {
  /** The card-local action for a tap whose template-encoded action is [templateAction]. */
  fun resolveTap(templateAction: HaAction?, snapshot: HaSnapshot? = null): HaAction {
    if (templateAction == null) return tapAction
    // A tap on no slot (a card-level control) may still pick one of the card's own actions.
    val slot =
      actionEntityId(templateAction)?.let(::slotIndexOf)
        ?: return slotAction(snapshot, NO_SLOT, templateAction) ?: tapAction
    if (slot !in slotTapActions.indices) return tapAction
    return slotAction(snapshot, slot, templateAction) ?: slotTapActions[slot]
  }

  companion object {
    /** The slot passed to [slotAction] for a tap that names no slot entity. */
    const val NO_SLOT: Int = -1
  }

  private fun slotIndexOf(entityId: String): Int? =
    entityId.substringAfter('.', "").takeIf { it.startsWith("s") }?.drop(1)?.toIntOrNull()

  private fun actionEntityId(action: HaAction): String? =
    when (action) {
      is HaAction.CallService -> action.entityId
      is HaAction.Toggle -> action.entityId
      is HaAction.MoreInfo -> action.entityId
      is HaAction.AlarmKey -> action.entityId
      is HaAction.AlarmPin -> action.entityId
      is HaAction.AlarmIntent -> action.entityId
      is HaAction.SetValue -> action.entityId
      is HaAction.Navigate,
      is HaAction.Url,
      HaAction.None -> null
    }
}
