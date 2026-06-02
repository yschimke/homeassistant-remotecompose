package ee.schimke.ha.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CardKeyTest {

  @Test
  fun nullPathsFallBackToDefaultMarker() {
    val key = CardKey(dashboardUrlPath = null, viewPath = null, cardIndex = 0, type = "tile")
    assertEquals("_default/_default/0#tile", key.toCacheKey())
  }

  @Test
  fun fullPathWithoutSectionOmitsSectionSegment() {
    val key =
      CardKey(dashboardUrlPath = "lovelace", viewPath = "home", cardIndex = 2, type = "entities")
    assertEquals("lovelace/home/2#entities", key.toCacheKey())
  }

  @Test
  fun sectionIndexAddsPrefixedSegment() {
    val key =
      CardKey(
        dashboardUrlPath = "lovelace",
        viewPath = "home",
        sectionIndex = 1,
        cardIndex = 3,
        type = "button",
      )
    assertEquals("lovelace/home/s1/3#button", key.toCacheKey())
  }

  @Test
  fun reorderingCardsChangesTheKey() {
    val base = CardKey("lovelace", "home", sectionIndex = 0, cardIndex = 0, type = "tile")
    val moved = base.copy(cardIndex = 1)
    assertNotEquals(base.toCacheKey(), moved.toCacheKey())
  }

  @Test
  fun sectionZeroIsDistinctFromNoSection() {
    val noSection = CardKey("lovelace", "home", sectionIndex = null, cardIndex = 0, type = "tile")
    val sectionZero = CardKey("lovelace", "home", sectionIndex = 0, cardIndex = 0, type = "tile")
    assertNotEquals(noSection.toCacheKey(), sectionZero.toCacheKey())
  }
}
