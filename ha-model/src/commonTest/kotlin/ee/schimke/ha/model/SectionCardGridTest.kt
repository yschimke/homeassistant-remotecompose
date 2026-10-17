package ee.schimke.ha.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * [SectionCardGrid] against the HA frontend's `hui-grid-section`, `computeCardGridSize`,
 * `migrateLayoutToGridOptions` and the cards' `getGridOptions()`.
 */
class SectionCardGridTest {

  private fun card(json: String): CardConfig {
    val obj: JsonObject = Json.parseToJsonElement(json).jsonObject
    return CardConfig(type = (obj["type"] as kotlinx.serialization.json.JsonPrimitive).content, obj)
  }

  private fun size(json: String) = SectionCardGrid.size(card(json))

  private fun SectionCardPlan.cells() = placements.map { listOf(it.row, it.column, it.columnSpan) }

  @Test
  fun defaults_followEachCardsGetGridOptions() {
    assertEquals(CardGridSize(6, 1), size("""{"type":"tile","entity":"light.a"}"""))
    assertEquals(CardGridSize(6, 2), size("""{"type":"tile","vertical":true}"""))
    // Two bottom features add a row each.
    assertEquals(
      CardGridSize(6, 3),
      size("""{"type":"tile","features":[{"type":"a"},{"type":"b"}]}"""),
    )
    // Inline: the first feature sits beside the content; min_columns 12 widens the tile.
    assertEquals(
      CardGridSize(12, 1),
      size("""{"type":"tile","features_position":"inline","features":[{"type":"a"}]}"""),
    )
    assertEquals(CardGridSize(6, 2), size("""{"type":"button","entity":"light.a"}"""))
    assertEquals(CardGridSize(3, 1), size("""{"type":"button","show_name":false}"""))
    assertEquals(CardGridSize(3, 1), size("""{"type":"button","show_icon":false}"""))
    assertEquals(
      CardGridSize(6, 2),
      size("""{"type":"button","show_name":false,"show_state":true}"""),
    )
    assertEquals(CardGridSize(6, 2), size("""{"type":"entity"}"""))
    assertEquals(CardGridSize(6, 2), size("""{"type":"sensor"}"""))
    assertEquals(CardGridSize(6, 2), size("""{"type":"statistic"}"""))
    assertEquals(CardGridSize(12, null), size("""{"type":"entities","entities":[]}"""))
    assertEquals(CardGridSize(null, null), size("""{"type":"heading"}"""))
    assertEquals(CardGridSize(12, null), size("""{"type":"history-graph"}"""))
    assertEquals(CardGridSize(12, null), size("""{"type":"statistics-graph"}"""))
    assertEquals(CardGridSize(null, 4), size("""{"type":"map"}"""))
    assertEquals(CardGridSize(12, 6), size("""{"type":"logbook"}"""))
    assertEquals(CardGridSize(12, 6), size("""{"type":"calendar"}"""))
    assertEquals(CardGridSize(12, 5), size("""{"type":"thermostat"}"""))
    assertEquals(CardGridSize(12, 7), size("""{"type":"thermostat","features":[{},{}]}"""))
    assertEquals(CardGridSize(12, 3), size("""{"type":"weather-forecast"}"""))
    assertEquals(
      CardGridSize(12, 4),
      size("""{"type":"weather-forecast","forecast_type":"daily"}"""),
    )
    assertEquals(CardGridSize(12, 2), size("""{"type":"weather-forecast","show_forecast":false}"""))
    assertEquals(CardGridSize(6, 3), size("""{"type":"area"}"""))
    assertEquals(CardGridSize(6, 1), size("""{"type":"area","display_type":"compact"}"""))
    assertEquals(CardGridSize(6, 1), size("""{"type":"clock"}"""))
    assertEquals(CardGridSize(6, 2), size("""{"type":"clock","clock_style":"analog"}"""))
  }

  @Test
  fun typesWithoutGridOptions_areTwelveColumnsAuto() {
    for (type in
      listOf(
        "glance",
        "gauge",
        "markdown",
        "light",
        "picture-entity",
        "grid",
        "vertical-stack",
        "conditional",
        "custom:ha-bambulab-print_status-card",
      )) {
      assertEquals(CardGridSize(12, null), size("""{"type":"$type"}"""), type)
    }
  }

  @Test
  fun gridOptions_overrideTheDefaultsKeyByKey() {
    assertEquals(
      CardGridSize(null, 1),
      size("""{"type":"tile","grid_options":{"columns":"full"}}"""),
    )
    assertEquals(CardGridSize(12, 1), size("""{"type":"tile","grid_options":{"columns":12}}"""))
    // The tile's own min_columns (6) still clamps a narrower authored width.
    assertEquals(CardGridSize(6, 1), size("""{"type":"tile","grid_options":{"columns":3}}"""))
    // …unless the config overrides that minimum too.
    assertEquals(
      CardGridSize(3, 1),
      size("""{"type":"tile","grid_options":{"columns":3,"min_columns":3}}"""),
    )
    assertEquals(
      CardGridSize(9, null),
      size("""{"type":"markdown","grid_options":{"columns":12,"max_columns":9}}"""),
    )
    assertEquals(CardGridSize(12, 3), size("""{"type":"markdown","grid_options":{"rows":3}}"""))
    assertEquals(
      CardGridSize(6, null),
      size("""{"type":"entity","grid_options":{"rows":"auto"}}"""),
    )
    // Rows clamp to min_rows/max_rows: the entity card needs at least two.
    assertEquals(CardGridSize(6, 2), size("""{"type":"entity","grid_options":{"rows":1}}"""))
    assertEquals(
      CardGridSize(6, 4),
      size("""{"type":"clock","grid_options":{"rows":9}}"""),
    )
  }

  @Test
  fun legacyLayoutOptions_multiplyColumnsByThree() {
    assertEquals(
      CardGridSize(6, 1),
      size("""{"type":"markdown","layout_options":{"grid_columns":2,"grid_rows":1}}"""),
    )
    assertEquals(
      CardGridSize(null, null),
      size("""{"type":"tile","layout_options":{"grid_columns":"full","grid_rows":"auto"}}"""),
    )
    assertEquals(
      CardGridSize(3, 1),
      size("""{"type":"tile","layout_options":{"grid_columns":1,"grid_min_columns":1}}"""),
    )
    // grid_options wins when both are present.
    assertEquals(
      CardGridSize(12, 1),
      size("""{"type":"tile","grid_options":{"columns":12},"layout_options":{"grid_columns":2}}"""),
    )
  }

  @Test
  fun plan_wrapsInAuthoredOrderOnTwelveColumns() {
    val plan =
      SectionCardGrid.plan(
        listOf(
          card("""{"type":"heading"}"""),
          card("""{"type":"tile"}"""),
          card("""{"type":"tile"}"""),
          card("""{"type":"tile"}"""),
          card("""{"type":"history-graph"}"""),
        )
      )
    assertEquals(12, plan.columnCount)
    assertEquals(
      listOf(listOf(0, 0, 12), listOf(1, 0, 6), listOf(1, 6, 6), listOf(2, 0, 6), listOf(3, 0, 12)),
      plan.cells(),
    )
    assertNull(plan.placements[0].fixedHeightDp)
    assertEquals(56f, plan.placements[1].fixedHeightDp)
    assertNull(plan.placements[4].fixedHeightDp)
  }

  @Test
  fun plan_isSparse_notDense() {
    // [tile 6][     hole    ] / [entities 12] / then the 3-column button goes after, not back.
    val plan =
      SectionCardGrid.plan(
        listOf(
          card("""{"type":"tile"}"""),
          card("""{"type":"entities"}"""),
          card("""{"type":"button","show_name":false}"""),
        )
      )
    assertEquals(listOf(listOf(0, 0, 6), listOf(1, 0, 12), listOf(2, 0, 3)), plan.cells())
  }

  @Test
  fun plan_rowSpansReserveLaterRows() {
    // An entity card (6×2) beside two tiles (6×1): the second tile goes under the first.
    val plan =
      SectionCardGrid.plan(
        listOf(
          card("""{"type":"entity"}"""),
          card("""{"type":"tile"}"""),
          card("""{"type":"tile"}"""),
        )
      )
    assertEquals(listOf(listOf(0, 0, 6), listOf(0, 6, 6), listOf(1, 6, 6)), plan.cells())
    assertEquals(2, plan.placements[0].rowSpan)
    assertEquals(120f, plan.placements[0].fixedHeightDp)
    assertEquals(2, plan.rowCount)
  }

  @Test
  fun plan_sectionSpanMultipliesTheColumns() {
    // A section spanning two view columns is a 24-column grid: four tiles to a row.
    val tiles = List(5) { card("""{"type":"tile"}""") }
    val plan = SectionCardGrid.plan(tiles, sectionColumnSpan = 2)
    assertEquals(24, plan.columnCount)
    assertEquals(listOf(0, 0, 0, 0, 1), plan.placements.map { it.row })
    // "full" and 12 differ here: full is the whole row, 12 is half of it.
    val mixed =
      SectionCardGrid.plan(
        listOf(card("""{"type":"heading"}"""), card("""{"type":"entities"}""")),
        sectionColumnSpan = 2,
      )
    assertEquals(listOf(listOf(0, 0, 24), listOf(1, 0, 12)), mixed.cells())
  }

  @Test
  fun plan_clampsSpansToTheGrid_andRejectsNonPositiveValues() {
    val plan =
      SectionCardGrid.plan(
        listOf(
          card("""{"type":"markdown","grid_options":{"columns":40}}"""),
          card("""{"type":"markdown","grid_options":{"columns":0,"rows":0}}"""),
        )
      )
    assertEquals(listOf(listOf(0, 0, 12), listOf(1, 0, 1)), plan.cells())
    assertNull(plan.placements[1].fixedHeightDp)
  }

  @Test
  fun fixedHeight_isRowsTimes56PlusGaps() {
    assertEquals(56f, SectionCardGrid.fixedHeightDp(1))
    assertEquals(120f, SectionCardGrid.fixedHeightDp(2))
    assertEquals(376f, SectionCardGrid.fixedHeightDp(6))
  }

  @Test
  fun cellWidth_onAPhoneWideSection() {
    // A 360 dp section: a tile is (360 - 11·8) / 12 · 6 + 5·8 = 176 dp.
    val plan = SectionCardGrid.plan(listOf(card("""{"type":"tile"}""")))
    assertEquals(176f, plan.cellWidthDp(plan.placements[0], 360f))
  }
}
