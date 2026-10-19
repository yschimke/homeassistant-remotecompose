package ee.schimke.terrazzo.wearsync

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.MESSAGE_CARD_TYPE
import ee.schimke.ha.model.messageAction
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.decodeHaAction

/** A paired watch supplies values, while the phone owns the service/action configuration. */
internal fun wearInputAction(
  rawCard: String?,
  values: Map<String, String>,
  publishedCards: Map<String, CardConfig>,
  snapshot: HaSnapshot,
  readOnly: Boolean,
): HaAction.CallService {
  require(!readOnly) { "This host is read-only" }
  val card = requireNotNull(publishedCards[rawCard]) { "Message card is no longer available" }
  require(card.type == MESSAGE_CARD_TYPE)
  return requireNotNull(
    decodeHaAction(messageAction(card, snapshot, values).toString()) as? HaAction.CallService
  )
}
