package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.runtime.Composable
import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.LocalWidgetCaptureSizeDp
import ee.schimke.ha.rc.components.HaHeadingData
import ee.schimke.ha.rc.components.HaHeadingStyle
import ee.schimke.ha.rc.components.HaSpace
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

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val heading =
      card.raw["heading"]?.jsonPrimitive?.content ?: card.raw["title"]?.jsonPrimitive?.content ?: ""
    val data = HaHeadingData(title = heading, style = styleOf(card))
    // A widget or Wear slot pins a frame taller than the heading: centre it rather than glue it to
    // the top (Principle 8).
    if (LocalCardSizeMode.current == CardSizeMode.Fixed) {
      // A one-column launcher widget steps the heading down to a subtitle before cutting it short.
      val narrow = (LocalWidgetCaptureSizeDp.current?.widthDp ?: Int.MAX_VALUE) < NarrowWidthDp
      CenteredCell {
        RemoteBox(
          modifier.fillMaxWidth().padding(horizontal = (if (narrow) HaSpace.XS else HaSpace.L).rdp)
        ) {
          // A roomy widget sets the heading at the hero's value size: a section's name across a
          // 4×3 widget, not a line at the top of a blank one.
          val hero = captureHeroScale()
          if (hero != null && data.style == HaHeadingStyle.Title)
            RemoteHaHeading(data, textSize = hero.state)
          else RemoteHaHeading(if (narrow) data.copy(style = HaHeadingStyle.Subtitle) else data)
        }
      }
    } else {
      RemoteHaHeading(data, modifier = modifier)
    }
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

/** Narrower than a two-column launcher widget. */
private const val NarrowWidthDp = 100
