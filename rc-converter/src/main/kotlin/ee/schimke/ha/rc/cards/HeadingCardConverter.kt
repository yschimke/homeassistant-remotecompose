package ee.schimke.ha.rc.cards

import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.components.HaHeadingData
import ee.schimke.ha.rc.components.HaHeadingStyle
import ee.schimke.ha.rc.components.placeholderTitle
import ee.schimke.ha.rc.design.DesignedCardConverter
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * HA `heading` card. Its layout is its card design, `heading.uid` ([HeadingDesignParts]): the
 * heading on a dashboard, and centred in a fixed cell, stepping down to a subtitle in a one-column
 * launcher widget and set at the hero's size in a roomy one.
 */
class HeadingCardConverter : DesignedCardConverter() {
  override val cardType: String = CardTypes.HEADING

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 36

  /**
   * A plain heading renders from a card design: its style is the one generation property, and the
   * text is the `card.title` binding (its length doesn't change the font size).
   */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? {
    if (!TEMPLATE_CONFIG_KEYS.containsAll(card.raw.keys)) return null
    // No text means no `card.title` to push; the template would keep its placeholder.
    if (CardDesignSlots.cardTitle(card) == null) return null
    return DesignProperties.build { string(HEADING_STYLE, styleOf(card).name.lowercase()) }
  }

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? {
    val style = properties[HEADING_STYLE] ?: return null
    val card =
      CardConfig(
        type = cardType,
        raw =
          buildJsonObject {
            put("type", cardType)
            put("heading", placeholderTitle(0))
            put(HEADING_STYLE, style)
          },
      )
    return DesignTemplateInput(card, HaSnapshot())
  }

  /** The heading [card] shows, in its style. */
  internal fun headingData(card: CardConfig): HaHeadingData =
    HaHeadingData(
      title =
        card.raw["heading"]?.jsonPrimitive?.content
          ?: card.raw["title"]?.jsonPrimitive?.content
          ?: "",
      style = styleOf(card),
    )

  private fun styleOf(card: CardConfig): HaHeadingStyle =
    when (card.raw[HEADING_STYLE]?.jsonPrimitive?.content) {
      "subtitle" -> HaHeadingStyle.Subtitle
      else -> HaHeadingStyle.Title
    }

  internal companion object {
    private val TEMPLATE_CONFIG_KEYS = setOf("type", "heading", "title", "heading_style")

    const val HEADING_STYLE = "heading_style"

    /** Narrower than a two-column launcher widget. */
    const val NarrowWidthDp = 100
  }
}
