package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.components.HaHeadingData
import ee.schimke.ha.rc.components.HaHeadingStyle
import ee.schimke.ha.rc.components.RemoteHaHeading
import ee.schimke.ha.rc.components.placeholderTitle
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class HeadingCardConverter : CardConverter {
  override val cardType: String = CardTypes.HEADING

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 36

  /**
   * A plain heading renders from a card design: its style is the one generation property, and the
   * text is the `card.title` binding (its length doesn't change the font size).
   */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? {
    if (!TEMPLATE_CONFIG_KEYS.containsAll(card.raw.keys)) return null
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

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val heading =
      card.raw["heading"]?.jsonPrimitive?.content ?: card.raw["title"]?.jsonPrimitive?.content ?: ""
    RemoteHaHeading(HaHeadingData(title = heading, style = styleOf(card)), modifier = modifier)
  }

  private fun styleOf(card: CardConfig): HaHeadingStyle =
    when (card.raw[HEADING_STYLE]?.jsonPrimitive?.content) {
      "subtitle" -> HaHeadingStyle.Subtitle
      else -> HaHeadingStyle.Title
    }

  private companion object {
    val TEMPLATE_CONFIG_KEYS = setOf("type", "heading", "title", "heading_style")

    const val HEADING_STYLE = "heading_style"
  }
}
