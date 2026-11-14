package ee.schimke.ha.rc.cards

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.HaHeadingStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class HeadingCardConverterTest {
  private val converter = HeadingCardConverter()

  @Test
  fun resolvesOptionalMdiIcon() {
    val card =
      CardConfig(
        type = CardTypes.HEADING,
        raw =
          buildJsonObject {
            put("type", CardTypes.HEADING)
            put("heading", "Indoor climate")
            put("heading_style", "subtitle")
            put("icon", "mdi:home-floor-l")
          },
      )
    val data = converter.headingData(card)
    assertEquals("Indoor climate", data.title)
    assertEquals(HaHeadingStyle.Subtitle, data.style)
    assertEquals("mdi:home-floor-l", assertNotNull(data.icon).name)
    // Heading with an icon skips the icon-less template path so the icon is baked.
    assertNull(converter.designProperties(card, HaSnapshot()))
  }

  @Test
  fun headingWithoutIconKeepsTemplatePath() {
    val card =
      CardConfig(
        type = CardTypes.HEADING,
        raw =
          buildJsonObject {
            put("type", CardTypes.HEADING)
            put("heading", "Plain")
          },
      )
    val data = converter.headingData(card)
    assertEquals("Plain", data.title)
    assertNull(data.icon)
    assertNotNull(converter.designProperties(card, HaSnapshot()))
  }
}
