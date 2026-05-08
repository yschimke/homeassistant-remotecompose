package ee.schimke.terrazzo.core.session

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.Section
import ee.schimke.ha.model.View
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Regression test for the live-HA decoding bug: `HaClient.fetchDashboard` previously decoded each
 * card via the default kotlinx-serialization decoder for `CardConfig(type, raw)`. Real Lovelace JSON
 * emits per-card fields (`entity`, `name`, …) directly on the card object — there is no `raw`
 * wrapper on the wire — so every card decoded with `raw = {}` and converters rendered blanks.
 *
 * This test runs the production decoding path (same `Json` config as [ee.schimke.ha.client.HaClient]
 * — `ignoreUnknownKeys = true`, `isLenient = true`) over real bundled Lovelace JSON and asserts that
 * card-level fields survive into [CardConfig.raw]. If `CardConfig`'s serializer regresses to the
 * default member-by-member decoder, every assertion below fails.
 */
class RealLovelaceConfigDecodingTest {

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  private fun loadDashboard(slug: String): Dashboard {
    val text =
      DemoData::class
        .java
        .getResourceAsStream("/dashboards/$slug/lovelace_config.json")
        ?.bufferedReader()
        ?.use { it.readText() }
    assertNotNull(text, "missing bundled lovelace_config.json for $slug")
    return json.decodeFromString(Dashboard.serializer(), text)
  }

  private fun walkCards(dashboard: Dashboard): Sequence<CardConfig> = sequence {
    for (view in dashboard.views) {
      for (card in view.cards) yieldAll(walk(card))
      for (section in view.sections) yieldAll(walk(section))
    }
  }

  private fun walk(section: Section): Sequence<CardConfig> = sequence {
    for (card in section.cards) yieldAll(walk(card))
  }

  private fun walk(card: CardConfig): Sequence<CardConfig> = sequence {
    yield(card)
    // Stack-style containers nest cards under "cards"; conditional/picture-entity
    // nest under "card". Walk both so nested children are exercised too.
    (card.raw["cards"] as? kotlinx.serialization.json.JsonArray)?.forEach { el ->
      val obj = el as? JsonObject ?: return@forEach
      val type = obj["type"]?.jsonPrimitive?.content ?: return@forEach
      yieldAll(walk(CardConfig(type = type, raw = obj)))
    }
    (card.raw["card"] as? JsonObject)?.let { obj ->
      val type = obj["type"]?.jsonPrimitive?.content ?: return@let
      yieldAll(walk(CardConfig(type = type, raw = obj)))
    }
  }

  @Test
  fun decodes_3d_printing_with_card_fields_populated() {
    val dashboard = loadDashboard("3d_printing")
    val cards = walkCards(dashboard).toList()
    assertTrue(cards.isNotEmpty(), "expected captured cards in 3d_printing dashboard")

    // No card config should arrive with an empty `raw` — that was the
    // exact symptom of the bug.
    val emptyRaw = cards.filter { it.raw.isEmpty() }
    assertTrue(emptyRaw.isEmpty(), "expected every card.raw populated, but ${emptyRaw.size} were empty")

    // Tile cards in this dashboard always have an `entity` and most have a `name`. Both fields are
    // top-level on the wire and would be dropped by the default serializer.
    val tilesWithEntity = cards.filter {
      it.type == "tile" && it.raw["entity"]?.jsonPrimitive?.content != null
    }
    assertTrue(
      tilesWithEntity.isNotEmpty(),
      "expected at least one tile card with an `entity` field surfaced in raw",
    )

    val tilesWithName = cards.filter {
      it.type == "tile" && it.raw["name"]?.jsonPrimitive?.content != null
    }
    assertTrue(
      tilesWithName.isNotEmpty(),
      "expected at least one tile card with a `name` field surfaced in raw",
    )
  }

  @Test
  fun decodes_every_bundled_dashboard_with_non_empty_card_raw() {
    // Cover every captured dashboard so card shapes that 3d_printing
    // doesn't include (markdown, picture-entity, weather-forecast, etc.)
    // also exercise the production decoding path.
    val slugs =
      listOf(
        "security",
        "3d_printing",
        "climate",
        "energy",
        "github",
        "meshcore",
        "networks",
      )

    for (slug in slugs) {
      val dashboard = loadDashboard(slug)
      val cards = walkCards(dashboard).toList()
      assertTrue(cards.isNotEmpty(), "$slug: expected non-empty card list")
      val empty = cards.filter { it.raw.isEmpty() }
      assertTrue(empty.isEmpty(), "$slug: ${empty.size} cards decoded with empty raw (types=${empty.map { it.type }.distinct()})")
    }
  }
}
