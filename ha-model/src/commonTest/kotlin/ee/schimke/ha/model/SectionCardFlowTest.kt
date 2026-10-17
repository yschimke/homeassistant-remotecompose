package ee.schimke.ha.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * [SectionCardFlow]: a section keeps HA's card order, and lays its cards out by their own minimum
 * widths. Widths are dp: a 328 dp phone section (360 dp minus gutters), a 500 dp tablet section.
 */
class SectionCardFlowTest {
  private val gap = 8f

  private fun item(min: Int, ownRow: Boolean = false) = SectionFlowItem(min.toFloat(), ownRow)

  private fun List<SectionFlowRow>.indices() = map { it.indices }

  private fun card(json: String): CardConfig {
    val obj: JsonObject = Json.parseToJsonElement(json).jsonObject
    return CardConfig(type = (obj["type"] as JsonPrimitive).content, obj)
  }

  @Test
  fun compactCardsShareARowWhileEachKeepsItsMinimum() {
    // Tiles want 140 dp: two fit a phone section, three a tablet one.
    val tiles = List(4) { item(140) }
    assertEquals(
      listOf(listOf(0, 1), listOf(2, 3)),
      SectionCardFlow.rows(tiles, 328f, gap).indices(),
    )
    assertEquals(
      listOf(listOf(0, 1, 2), listOf(3)),
      SectionCardFlow.rows(tiles, 500f, gap).indices(),
    )
  }

  @Test
  fun aRowIsSharedInProportionToMinimumsAndFillsTheWidth() {
    val rows = SectionCardFlow.rows(listOf(item(140), item(240)), 500f, gap)
    assertEquals(listOf(listOf(0, 1)), rows.indices())
    val (tile, list) = rows.single().widths
    assertEquals(492f, tile + list, 0.01f)
    assertTrue(list > tile, "the list gets the larger part: $tile vs $list")
    // A card alone fills its row.
    assertEquals(listOf(500f), SectionCardFlow.rows(listOf(item(140)), 500f, gap).single().widths)
  }

  @Test
  fun orderIsNeverChangedToFillAGap() {
    // A wide card that doesn't fit after the tile starts a new row; the tile after it stays after.
    val rows = SectionCardFlow.rows(listOf(item(140), item(240), item(140)), 328f, gap)
    assertEquals(listOf(listOf(0), listOf(1), listOf(2)), rows.indices())
  }

  @Test
  fun ownRowCardsStandAlone() {
    val rows =
      SectionCardFlow.rows(
        listOf(item(140), item(120, ownRow = true), item(140), item(140)),
        500f,
        gap,
      )
    assertEquals(listOf(listOf(0), listOf(1), listOf(2, 3)), rows.indices())
  }

  @Test
  fun aCardWiderThanTheSectionStillGetsItsOwnRow() {
    val rows = SectionCardFlow.rows(listOf(item(400), item(400)), 328f, gap)
    assertEquals(listOf(listOf(0), listOf(1)), rows.indices())
    assertEquals(328f, rows.first().widths.single())
  }

  @Test
  fun onlyHeadingsKeepARowToThemselves() {
    assertTrue(SectionCardFlow.ownRow(card("""{"type":"heading","heading":"Lights"}""")))
    assertFalse(SectionCardFlow.ownRow(card("""{"type":"tile","entity":"light.a"}""")))
    // HA's grid_options size HA's design of the card, so they don't pin harc's layout, `full`
    // included (HA's default for a map, and what dashboards often author on every card).
    assertFalse(SectionCardFlow.ownRow(card("""{"type":"map"}""")))
    assertFalse(
      SectionCardFlow.ownRow(card("""{"type":"tile","grid_options":{"columns":"full"}}"""))
    )
    assertFalse(SectionCardFlow.ownRow(card("""{"type":"tile","grid_options":{"columns":12}}""")))
  }

  @Test
  fun aHiddenCardTakesNoRoomAndNoGap() {
    val hidden = SectionFlowItem(0f, hidden = true)
    // 140 + 8 + 140 = 288: two tiles fit with a hidden conditional between them, with one gap.
    val rows = SectionCardFlow.rows(listOf(item(140), hidden, item(140)), 288f, gap)
    assertEquals(listOf(listOf(0, 2)), rows.indices())
    assertEquals(listOf(140f, 140f), rows.single().widths)
    // A hidden item alone makes no row.
    assertEquals(emptyList(), SectionCardFlow.rows(listOf(hidden), 288f, gap))
  }
}
