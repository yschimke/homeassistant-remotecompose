package ee.schimke.terrazzo.wear.ui

import ee.schimke.terrazzo.wearsync.proto.CardSummary
import kotlin.test.Test
import kotlin.test.assertEquals

/** A pinned section's list on the watch shows its title once. */
class WearSectionScreenTest {
  private val heading = CardSummary(type = "heading", title = "🚪 Garage door")
  private val tile = CardSummary(type = "tile", title = "Garage light")

  @Test
  fun aLeadingHeadingRepeatingTheTitleIsDropped() {
    assertEquals(listOf(tile), withoutRepeatedHeading(listOf(heading, tile), "🚪 Garage door"))
  }

  @Test
  fun anyOtherHeadingIsKept() {
    val cards = listOf(heading, tile)
    assertEquals(cards, withoutRepeatedHeading(cards, "Garage"))
    assertEquals(
      listOf(tile, heading),
      withoutRepeatedHeading(listOf(tile, heading), heading.title),
    )
  }
}
