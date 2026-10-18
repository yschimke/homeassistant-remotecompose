package ee.schimke.terrazzo.shared

import ee.schimke.ha.model.*
import kotlin.test.*
import kotlinx.serialization.json.*

class AdaptiveDashboardGroupsTest {
  private fun card(raw: String) = Json.decodeFromString(CardConfig.serializer(), raw)

  @Test
  fun headingsPartitionAnOversizedHASectionWithoutLosingOrReorderingCards() {
    val cards =
      listOf(
        card("""{"type":"heading","heading":"Hub"}"""),
        card("""{"type":"grid","cards":[{"type":"tile","entity":"sensor.hub"}]}"""),
        card("""{"type":"heading","heading":"Conversations"}"""),
        card("""{"type":"tile","entity":"binary_sensor.meshcore_radio_ch_0_messages"}"""),
        card(
          """{"type":"heading","heading":"Control","tap_action":{"action":"toggle"},"entity":"light.room"}"""
        ),
      )
    val result =
      dashboardGroups(View(sections = listOf(Section(cards = cards, columnSpan = 4))), "mesh", 0)
    assertEquals(listOf("Hub", "Conversations"), result.map { it.title })
    assertEquals(
      listOf(cards[1], cards[3], cards[4]),
      result.flatMap { it.cards }.map { it.second },
    )
    assertEquals(listOf(1, 3, 4), result.flatMap { it.cards }.map { it.first.cardIndex })
    assertEquals(result.size, result.map { it.id }.distinct().size)
  }

  @Test
  fun sectionBoundariesAndLegacyCardsRemainIndependent() {
    val tile = card("""{"type":"tile","entity":"sensor.x"}""")
    val result =
      dashboardGroups(
        View(
          cards = listOf(tile, tile),
          sections =
            listOf(
              Section(title = "Room", cards = listOf(tile)),
              Section(title = "Power", cards = listOf(tile)),
            ),
        ),
        null,
        0,
      )
    assertEquals(listOf(null, null, "Room", "Power"), result.map { it.title })
    assertEquals(4, result.flatMap { it.cards }.map { it.first.toCacheKey() }.distinct().size)
  }

  @Test
  fun readableTracksUseAvailableSpaceAndRespectTheViewLimit() {
    assertEquals(1, dashboardColumnCount(358f, 4))
    assertEquals(2, dashboardColumnCount(720f, 4))
    assertEquals(3, dashboardColumnCount(1340f, 4))
    assertEquals(4, dashboardColumnCount(1760f, 4))
    assertEquals(2, dashboardColumnCount(1760f, 2))
    assertEquals(1, dashboardColumnCount(1000f, 0))
  }
}
