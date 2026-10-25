package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.REMOTE_COMPOSE_CARD_TYPE
import ee.schimke.ha.model.remoteComposeInnerCard
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardRegistry
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.cardHeightDp

/**
 * `custom:remote-compose-card`, the Lovelace card that asks Home Assistant's web frontend to draw
 * its inner `card:` from a card design. Here it is that inner card: it keys, fetches, binds and
 * taps as the inner card's design ([designDelegate]), and renders through the inner card's
 * converter when there is no design, so the card works on every host, not only in the browser.
 *
 * Like [ConditionalCardConverter], pass [registryForHeight] for the inner card's height.
 */
class RemoteComposeCardConverter(private val registryForHeight: CardRegistry? = null) :
  CardConverter {
  override val cardType: String = REMOTE_COMPOSE_CARD_TYPE

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int {
    val inner = remoteComposeInnerCard(card) ?: return 0
    return registryForHeight?.cardHeightDp(inner, snapshot) ?: 160
  }

  override fun designDelegate(card: CardConfig, snapshot: HaSnapshot): CardConfig? =
    remoteComposeInnerCard(card)

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val inner = remoteComposeInnerCard(card) ?: return
    RenderChild(inner, snapshot, modifier)
  }
}
