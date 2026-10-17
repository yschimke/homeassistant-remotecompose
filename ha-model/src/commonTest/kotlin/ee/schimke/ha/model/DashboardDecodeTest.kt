package ee.schimke.ha.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Decoding of the `type: sections` view fields: header, max_columns, column_span and friends. */
class DashboardDecodeTest {

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  private fun decode(payload: String): Dashboard =
    json.decodeFromString(Dashboard.serializer(), payload.trimIndent())

  @Test
  fun sectionsView_readsSnakeCaseLayoutFields() {
    val view =
      decode(
          """
          {
            "views": [
              {
                "type": "sections",
                "max_columns": 3,
                "dense_section_placement": true,
                "sections": [
                  { "type": "grid", "column_span": 2, "row_span": 3, "cards": [] },
                  { "type": "grid", "cards": [] }
                ]
              }
            ]
          }
          """
        )
        .views
        .single()

    assertEquals(3, view.maxColumns)
    assertEquals(true, view.denseSectionPlacement)
    assertEquals(2, view.sections[0].columnSpan)
    assertEquals(3, view.sections[0].rowSpan)
    assertNull(view.sections[1].columnSpan)
  }

  @Test
  fun header_decodesCardLayoutAndBadgeOptions() {
    val view =
      decode(
          """
          {
            "views": [
              {
                "type": "sections",
                "header": {
                  "card": {
                    "type": "markdown",
                    "text_only": true,
                    "content": "# 3D Printing\nTwo Bambu printers"
                  },
                  "layout": "start",
                  "badges_position": "top",
                  "badges_wrap": "scroll"
                },
                "sections": []
              }
            ]
          }
          """
        )
        .views
        .single()

    val header = assertNotNull(view.header)
    val card = assertNotNull(header.card)
    assertEquals("markdown", card.type)
    assertEquals("# 3D Printing\nTwo Bambu printers", card.raw["content"]?.jsonPrimitive?.content)
    assertEquals(JsonPrimitive(true), card.raw["text_only"])
    assertEquals("start", header.layout)
    assertEquals("top", header.badgesPosition)
    assertEquals("scroll", header.badgesWrap)
    assertEquals(false, header.centered)
  }

  @Test
  fun header_defaultsToCentredAndToleratesUnknownFields() {
    val header =
      assertNotNull(
        decode(
            """
            {
              "views": [
                {
                  "header": {
                    "card": { "type": "markdown", "content": "hi" },
                    "some_future_option": { "nested": [1, 2] }
                  }
                }
              ]
            }
            """
          )
          .views
          .single()
          .header
      )
    assertTrue(header.centered)
    assertEquals("markdown", header.card?.type)
  }

  @Test
  fun header_withoutAUsableCard_decodesWithoutFailingTheDashboard() {
    val views =
      decode(
          """
          {
            "views": [
              { "header": { "layout": "center" } },
              { "header": { "card": { "content": "no type" } } },
              { "header": { "card": "not an object" } },
              { "header": { "card": null } }
            ]
          }
          """
        )
        .views

    assertEquals(4, views.size)
    views.forEach { assertNull(it.header?.card) }
    assertEquals("center", views[0].header?.layout)
  }

  @Test
  fun viewWithoutHeader_hasNullHeader() {
    assertNull(decode("""{ "views": [ { "type": "sections" } ] }""").views.single().header)
  }

  @Test
  fun header_roundTripsThroughEncoding() {
    val withDefaults = Json {
      ignoreUnknownKeys = true
      encodeDefaults = true
    }
    val original =
      decode(
        """
        {
          "views": [
            {
              "max_columns": 2,
              "header": { "card": { "type": "markdown", "content": "x" } },
              "sections": [ { "column_span": 2, "cards": [] } ]
            },
            { "header": {} }
          ]
        }
        """
      )

    val text = withDefaults.encodeToString(Dashboard.serializer(), original)
    assertEquals(original, withDefaults.decodeFromString(Dashboard.serializer(), text))
  }

  @Test
  fun legacyCamelCaseCache_stillReadsLayoutFields() {
    // Dashboards cached before the wire names were mapped were written with property names.
    val view =
      decode(
          """
          { "views": [ { "maxColumns": 2, "sections": [ { "columnSpan": 2, "rowSpan": 1 } ] } ] }
          """
        )
        .views
        .single()

    assertEquals(2, view.maxColumns)
    assertEquals(2, view.sections.single().columnSpan)
    assertEquals(1, view.sections.single().rowSpan)
  }

  @Test
  fun fromJson_readsARawHeaderElementLeniently() {
    val header =
      ViewHeader.fromJson(
        buildJsonObject {
          put("layout", "responsive")
          put("card", buildJsonObject { put("type", "markdown") })
          put("unknown", 1)
        }
      )
    assertEquals("responsive", header?.layout)
    assertEquals("markdown", header?.card?.type)
    assertNull(ViewHeader.fromJson(JsonPrimitive("nope")))
    assertNull(ViewHeader.fromJson(null))
  }

  @Test
  fun dashboardTemplates_includeTheHeaderCard() {
    val dashboard =
      decode(
        """
        {
          "views": [
            {
              "header": {
                "card": { "type": "markdown", "content": "Solar: {{ states('sensor.solar') | float(0) | round(0) }} W" }
              }
            }
          ]
        }
        """
      )

    val templates = TemplateBindings.dashboardTemplates(dashboard)
    assertEquals(
      listOf("Solar: {{ states('sensor.solar') | float(0) | round(0) }} W"),
      templates.map { it.template },
    )
  }
}
