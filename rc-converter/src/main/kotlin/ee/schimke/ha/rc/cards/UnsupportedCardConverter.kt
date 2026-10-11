package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.UNSUPPORTED_CARD_TYPE
import ee.schimke.ha.rc.components.HaUnsupportedData
import ee.schimke.ha.rc.components.RemoteHaUnsupported
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Fallback for card types we haven't implemented. Renders a visible placeholder so the dashboard
 * still lays out correctly and missing pieces are obvious.
 */
class UnsupportedCardConverter(private val declaredType: String? = null) : CardConverter {
  override val cardType: String = declaredType ?: UNSUPPORTED_CARD_TYPE

  // Icon (24) + 8 gap + "Not yet supported" (14sp) + card type (12sp)
  // + 12 padding top/bottom ≈ 88 dp; round to 96 to give descenders
  // room. The 160 dp default left ~70 dp of empty space below the
  // placeholder content in every dashboard slot.
  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 96

  /**
   * The placeholder renders from a card design: it shows nothing but the card's type, which is
   * already part of the design key, so it has no generation properties of its own.
   */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties =
    DesignProperties.build {}

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput =
    DesignTemplateInput(
      CardConfig(type = cardType, raw = buildJsonObject { put("type", cardType) }),
      HaSnapshot(),
    )

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    RemoteHaUnsupported(HaUnsupportedData(cardType = card.type), modifier = modifier)
  }
}
