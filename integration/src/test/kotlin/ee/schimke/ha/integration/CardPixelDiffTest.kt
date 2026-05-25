package ee.schimke.ha.integration

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Pixel-level regression tests for every HA-referenced card (not just the 4 tile variants in
 * [TileCardPixelDiffTest]). Each entry pairs an RC preview's PNG filename pattern with a committed
 * reference PNG.
 *
 * Parameterized so a missing reference is a single skip, not a spray of assumeTrue failures. A
 * per-test threshold lets stacks / state variants float higher than tile while we tune.
 */
@RunWith(Parameterized::class)
class CardPixelDiffTest(
  private val previewPattern: String,
  private val referenceRel: String,
  private val maxPct: Double,
) {

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{1} <= {2}%")
    fun cases(): Collection<Array<Any>> =
      listOf(
        // tile — pinned functions
        arrayOf(
          "Tile_TemperatureSensor_tile_sensor_temperature",
          "tile/temperature_sensor_light.png",
          25.0,
        ),
        arrayOf(
          "Tile_TemperatureSensor_Dark_tile_sensor_temperature_dark",
          "tile/temperature_sensor_dark.png",
          25.0,
        ),
        arrayOf("Tile_LightOn_tile_light_on", "tile/light_on_light.png", 25.0),
        arrayOf("Tile_LightOn_Dark_tile_light_on_dark", "tile/light_on_dark.png", 25.0),

        // tile state variants — light theme
        arrayOf("Tile_Light_States_tile_light_light_off", "tile/light_off_light.png", 30.0),
        arrayOf("Tile_Lock_States_tile_lock_light_locked", "tile/lock_locked_light.png", 30.0),
        arrayOf("Tile_Cover_States_tile_cover_light_closed", "tile/cover_light.png", 40.0),

        // button
        arrayOf("Button_Light_button_light_on", "button/light_on_light.png", 45.0),
        arrayOf("Button_Light_button_light_off", "button/light_off_light.png", 45.0),
        arrayOf("Button_Dark_button_dark_on", "button/light_on_dark.png", 45.0),
        arrayOf("Button_Dark_button_dark_off", "button/light_off_dark.png", 45.0),

        // entity — TODO: HA's `entity` card is multi-row (big state,
        // small unit, icon top-right), not a single row. Our
        // RemoteHaEntityRow is the wrong primitive for this card.
        // Skip for now; see docs/followups/entity-card-redesign.md.
        //   arrayOf("Entity_Light_entity_light", "entity/temperature_sensor_light.png", 35.0),
        //   arrayOf("Entity_Dark_entity_dark", "entity/temperature_sensor_dark.png", 35.0),

        // entities / glance / markdown
        arrayOf("Entities_Light_entities_light", "entities/living_room_light.png", 40.0),
        arrayOf("Entities_Dark_entities_dark", "entities/living_room_dark.png", 40.0),
        arrayOf("Glance_Light_glance_light", "glance/overview_light.png", 40.0),
        arrayOf("Glance_Dark_glance_dark", "glance/overview_dark.png", 40.0),
        arrayOf("Markdown_Light_markdown_light", "markdown/notes_light.png", 40.0),
        arrayOf("Markdown_Dark_markdown_dark", "markdown/notes_dark.png", 40.0),

        // gauge / weather-forecast / picture-entity / logbook —
        // converters and previews exist (see CardPreviews.kt), but no HA
        // reference captures yet. The cases below are wired so that once
        // `integration/scripts/capture-references.sh` is run against the
        // seeded HA the diff pops in automatically; until then each one
        // skips via assumeTrue.
        arrayOf("Gauge_Light_gauge_light", "gauge/battery_light.png", 40.0),
        arrayOf("Gauge_Dark_gauge_dark", "gauge/battery_dark.png", 40.0),
        arrayOf(
          "WeatherForecast_Light_weather-forecast_light",
          "weather-forecast/forecast_home_light.png",
          40.0,
        ),
        arrayOf(
          "WeatherForecast_Dark_weather-forecast_dark",
          "weather-forecast/forecast_home_dark.png",
          40.0,
        ),
        arrayOf(
          "PictureEntity_Light_picture-entity_light",
          "picture-entity/driveway_light.png",
          50.0,
        ),
        arrayOf("PictureEntity_Dark_picture-entity_dark", "picture-entity/driveway_dark.png", 50.0),
        arrayOf("Logbook_Light_logbook_light", "logbook/recent_activity_light.png", 40.0),
        arrayOf("Logbook_Dark_logbook_dark", "logbook/recent_activity_dark.png", 40.0),
      )
  }

  @Test
  fun diff() {
    val rendered = locateRendered(previewPattern)
    val reference = referenceFile(referenceRel)
    assumeTrue("reference $referenceRel missing", reference.exists())
    val report = ImageDiff.compare(rendered, reference)
    println(report)
    assertTrue(
      "pixel diff above threshold: ${report.pctChanged}% > $maxPct% — $report",
      report.pctChanged <= maxPct,
    )
  }

  private fun locateRendered(previewName: String): File {
    val dir =
      File(System.getProperty("ha.rendered.dir") ?: "previews/build/compose-previews/renders")
    val exact = dir.listFiles { f -> f.name.contains(previewName) && f.extension == "png" }
    require(!exact.isNullOrEmpty()) {
      "No rendered PNG matching '$previewName' under $dir — run scripts/render-previews.sh"
    }
    return exact.first()
  }

  private fun referenceFile(rel: String): File {
    val dir = File(System.getProperty("ha.references.dir") ?: "references")
    return File(dir, rel)
  }
}
