package ee.schimke.ha.rc

import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.design.CardEntityActions
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.decodeHaEntityAction

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
  /** The real entity of each slot, in slot order ([CardDesignSlots.slotEntities]). */
  val slotEntities: List<String> = emptyList(),
) {
  /**
   * The service call an **entity action** the template sent ([CardEntityActions.NAME], payload
   * [value]) runs: the named action on the real entity of the slot it names, as
   * [CardEntityActions.resolve] gives it for [snapshot]. [HaAction.None] for a payload that isn't
   * one, a slot the card doesn't have or of another domain, or an action that entity's domain
   * doesn't list. The template picks only the action's name and the slot; never a service or data.
   */
  fun resolveEntityAction(value: Any?, snapshot: HaSnapshot? = null): HaAction {
    val action = decodeHaEntityAction(value) ?: return HaAction.None
    val slot = slotIndexOf(action.entityId) ?: return HaAction.None
    val real = slotEntities.getOrNull(slot) ?: return HaAction.None
    if (CardDesignSlots.slotId(CardDesignSlots.domainOf(real), slot) != action.entityId) {
      return HaAction.None
    }
    return entityActionCall(real, action.action, snapshot)
  }

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

/**
 * [action] on the real entity [entityId] as the call [CardEntityActions.resolve] gives, or
 * [HaAction.None] when it gives none.
 */
fun entityActionCall(entityId: String, action: String, snapshot: HaSnapshot?): HaAction =
  CardEntityActions.resolve(entityId, action, snapshot)?.let {
    HaAction.CallService(it.domain, it.service, it.entityId, it.data)
  } ?: HaAction.None
