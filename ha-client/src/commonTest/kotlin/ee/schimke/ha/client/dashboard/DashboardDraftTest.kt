package ee.schimke.ha.client.dashboard

import kotlin.test.*
import kotlinx.serialization.json.*

class DashboardDraftTest {
  private val config =
    Json.parseToJsonElement(
        """
        {"title":"Home","custom_top":1,"views":[{"title":"Main","type":"sections","x":true,
          "sections":[
            {"type":"grid","keep":"me","cards":[
              {"type":"heading","heading":"Lights"},
              {"type":"tile","entity":"light.a","custom":{"deep":[1]}},
              {"type":"tile","entity":"light.b","layout_options":{"grid_columns":2}}
            ]},
            {"type":"grid","cards":[]}
          ]}]}
        """
      )
      .jsonObject

  private fun entityOf(card: JsonObject?) = card?.get("entity")?.jsonPrimitive?.content

  @Test
  fun newDashboardIsOneSectionsViewWithOneSection() {
    val created = DashboardDraft.newDashboard("Garden")
    assertEquals("Garden", created["title"]!!.jsonPrimitive.content)
    assertTrue(DashboardDraft.isSections(created, 0))
    assertEquals(1, DashboardDraft.sections(created, 0).size)
    // It is a dashboard the agent prompt parser accepts.
    DashboardAgentPrompt.parseAnswer(created.toString())
  }

  @Test
  fun addCardAppendsAndKeepsUnknownFields() {
    val (updated, at) = DashboardDraft.addCard(config, 0, 0, DashboardDraft.cardFor("sensor.temp"))
    assertEquals(CardAddress(0, 0, 3), at)
    assertEquals("sensor.temp", entityOf(DashboardDraft.card(updated, at)))
    assertEquals(JsonPrimitive(1), updated["custom_top"])
    assertEquals(JsonPrimitive(true), DashboardDraft.views(updated)[0]["x"])
    assertEquals(JsonPrimitive("me"), DashboardDraft.sections(updated, 0)[0]["keep"])
    assertEquals(
      config.jsonObject["views"]!!
        .jsonArray[0]
        .jsonObject["sections"]!!
        .jsonArray[0]
        .jsonObject["cards"]!!
        .jsonArray[1],
      DashboardDraft.cards(updated, 0, 0)[1],
    )
  }

  @Test
  fun removeAndMoveCards() {
    val removed = DashboardDraft.removeCard(config, CardAddress(0, 0, 1))
    assertEquals(listOf(null, "light.b"), DashboardDraft.cards(removed, 0, 0).map(::entityOf))

    val (moved, to) = DashboardDraft.moveCard(config, CardAddress(0, 0, 2), -1)
    assertEquals(CardAddress(0, 0, 1), to)
    assertEquals(
      listOf(null, "light.b", "light.a"),
      DashboardDraft.cards(moved, 0, 0).map(::entityOf),
    )
    // Clamped at the ends.
    val (same, stays) = DashboardDraft.moveCard(config, CardAddress(0, 0, 2), 5)
    assertEquals(config, same)
    assertEquals(CardAddress(0, 0, 2), stays)
  }

  @Test
  fun moveCardToAnotherSection() {
    val (updated, at) = DashboardDraft.moveCardToSection(config, CardAddress(0, 0, 1), 1)
    assertEquals(CardAddress(0, 1, 0), at)
    assertEquals("light.a", entityOf(DashboardDraft.card(updated, at)))
    assertEquals(2, DashboardDraft.cards(updated, 0, 0).size)
  }

  @Test
  fun resizeWritesGridOptionsAndDropsLegacyLayoutOptions() {
    val at = CardAddress(0, 0, 2)
    // Legacy grid_columns 2 × 3 reads as 6 columns.
    assertEquals(6, DashboardDraft.size(DashboardDraft.card(config, at)!!).columns)
    val updated = DashboardDraft.resizeCard(config, at, columns = 9, rows = 2)
    val card = DashboardDraft.card(updated, at)!!
    assertNull(card["layout_options"])
    assertEquals(
      buildJsonObject {
        put("columns", 9)
        put("rows", 2)
      },
      card["grid_options"],
    )
    val size = DashboardDraft.size(card)
    assertEquals(9, size.columns)
    assertEquals(2, size.rows)

    val full = DashboardDraft.resizeCard(updated, at, columns = 12, rows = null)
    assertEquals(
      buildJsonObject {
        put("columns", "full")
        put("rows", "auto")
      },
      DashboardDraft.card(full, at)!!["grid_options"],
    )
  }

  @Test
  fun resizeClampsToTheCardTypesLimits() {
    // A tile is at least 3 columns wide in HA.
    val at = CardAddress(0, 0, 1)
    val min = DashboardDraft.size(DashboardDraft.card(config, at)!!).minColumns
    val updated = DashboardDraft.resizeCard(config, at, columns = 1, rows = null)
    assertEquals(min, DashboardDraft.size(DashboardDraft.card(updated, at)!!).columns)
  }

  @Test
  fun columnStepsWalkQuarters() {
    assertEquals(9, DashboardDraft.stepColumns(6, 1))
    assertEquals(3, DashboardDraft.stepColumns(6, -1))
    assertEquals(12, DashboardDraft.stepColumns(12, 1))
    assertEquals(3, DashboardDraft.stepColumns(3, -1))
    assertEquals(6, DashboardDraft.stepColumns(4, 1))
  }

  @Test
  fun sectionsAddRemoveAndMove() {
    val added = DashboardDraft.addSection(config, 0, "Climate")
    val sections = DashboardDraft.sections(added, 0)
    assertEquals(3, sections.size)
    assertEquals(
      "Climate",
      sections[2]["cards"]!!.jsonArray[0].jsonObject["heading"]!!.jsonPrimitive.content,
    )
    val moved = DashboardDraft.moveSection(added, 0, 2, -2)
    assertEquals(sections[2], DashboardDraft.sections(moved, 0)[0])
    val removed = DashboardDraft.removeSection(moved, 0, 0)
    assertEquals(DashboardDraft.sections(config, 0), DashboardDraft.sections(removed, 0))
  }

  @Test
  fun viewsAddWithUniquePathsAndKeepAtLeastOne() {
    val one = DashboardDraft.addView(config, "Garden")
    val two = DashboardDraft.addView(one, "Garden")
    assertEquals(
      listOf(null, "garden", "garden-2"),
      DashboardDraft.views(two).map { it["path"]?.jsonPrimitive?.content },
    )
    assertEquals(2, DashboardDraft.views(DashboardDraft.removeView(two, 1)).size)
    assertFailsWith<IllegalArgumentException> { DashboardDraft.removeView(config, 0) }
  }

  @Test
  fun masonryViewsEditTheirOwnCards() {
    val masonry =
      Json.parseToJsonElement("""{"views":[{"cards":[{"type":"entities","entities":[]}]}]}""")
        .jsonObject
    assertFalse(DashboardDraft.isSections(masonry, 0))
    val (updated, at) = DashboardDraft.addCard(masonry, 0, null, DashboardDraft.cardFor("light.x"))
    assertEquals(CardAddress(0, null, 1), at)
    assertEquals("light.x", entityOf(DashboardDraft.card(updated, at)))
    assertFailsWith<IllegalArgumentException> { DashboardDraft.addSection(masonry, 0, "No") }
  }

  @Test
  fun cardsForEntitiesSuitTheirDomain() {
    assertEquals(
      "thermostat",
      DashboardDraft.cardFor("climate.hall")["type"]!!.jsonPrimitive.content,
    )
    val graph = DashboardDraft.cardFor("sensor.t", "history-graph")
    assertEquals(JsonArray(listOf(JsonPrimitive("sensor.t"))), graph["entities"])
    assertNull(graph["entity"])
  }

  @Test
  fun urlPathsHaveAHyphenAndAreUnique() {
    assertEquals("dashboard-garden", DashboardDraft.urlPathFor("Garden", emptySet()))
    assertEquals("back-yard", DashboardDraft.urlPathFor("Back Yard!", emptySet()))
    assertEquals("back-yard-2", DashboardDraft.urlPathFor("Back yard", setOf("back-yard")))
  }

  @Test
  fun badAddressesAreRefused() {
    assertFailsWith<IllegalArgumentException> {
      DashboardDraft.removeCard(config, CardAddress(0, 0, 9))
    }
    assertFailsWith<IllegalArgumentException> {
      DashboardDraft.removeCard(config, CardAddress(3, 0, 0))
    }
    assertNull(DashboardDraft.card(config, CardAddress(0, 5, 0)))
  }

  @Test
  fun changingACardsEntityKeepsTheRestOfItsConfig() {
    val tile =
      Json.parseToJsonElement(
          """{"type":"tile","entity":"light.a","name":"Desk","grid_options":{"columns":6},
            "features":[{"type":"light-brightness"}],"tap_action":{"action":"toggle"}}"""
        )
        .jsonObject
    val changed = DashboardDraft.withEntity(tile, "light.b")
    assertEquals(JsonObject(tile + ("entity" to JsonPrimitive("light.b"))), changed)

    val list =
      Json.parseToJsonElement(
          """{"type":"entities","title":"T","entities":[{"entity":"a.x","name":"X"},"a.y"]}"""
        )
        .jsonObject
    val listChanged = DashboardDraft.withEntity(list, "a.z")
    assertEquals(
      Json.parseToJsonElement("""[{"entity":"a.z","name":"X"},"a.y"]"""),
      listChanged["entities"],
    )
    assertEquals(JsonPrimitive("T"), listChanged["title"])
  }

  @Test
  fun aOneEntityCardSwapsTemplateKeepingSharedSettings() {
    val tile =
      Json.parseToJsonElement(
          """{"type":"tile","entity":"sensor.t","name":"Hall","grid_options":{"columns":6},
            "tap_action":{"action":"more-info"},"features":[{"type":"trend-graph"}],"color":"red"}"""
        )
        .jsonObject
    val choices = DashboardDraft.templateChoices(tile)
    assertTrue("gauge" in choices && "history-graph" in choices)
    assertFalse("tile" in choices)

    val gauge = DashboardDraft.withType(tile, "gauge")
    assertEquals(JsonPrimitive("gauge"), gauge["type"])
    assertEquals(JsonPrimitive("sensor.t"), gauge["entity"])
    assertEquals(JsonPrimitive("Hall"), gauge["name"])
    assertEquals(tile["tap_action"], gauge["tap_action"])
    // Tile-only settings mean nothing to a gauge.
    assertNull(gauge["features"])
    assertNull(gauge["color"])

    // A graph takes its entity as a list, and swaps back from one.
    val graph = DashboardDraft.withType(gauge, "history-graph")
    assertEquals(JsonArray(listOf(JsonPrimitive("sensor.t"))), graph["entities"])
    assertNull(graph["entity"])
    assertEquals("sensor.t", DashboardDraft.primaryEntity(graph))
    assertEquals(JsonPrimitive("sensor.t"), DashboardDraft.withType(graph, "tile")["entity"])
  }

  @Test
  fun cardsWithSeveralEntitiesOrNoneHaveNoTemplates() {
    val list =
      Json.parseToJsonElement("""{"type":"entities","entities":["light.a","light.b"]}""").jsonObject
    assertEquals(emptyList(), DashboardDraft.templateChoices(list))
    assertEquals(emptyList(), DashboardDraft.templateChoices(DashboardDraft.heading("Hi")))
    assertFailsWith<IllegalArgumentException> { DashboardDraft.withType(list, "tile") }
  }
}
