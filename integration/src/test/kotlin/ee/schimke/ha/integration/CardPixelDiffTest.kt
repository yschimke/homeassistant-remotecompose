package ee.schimke.ha.integration

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Pixel-level regression tests for every HA-referenced card (not just
 * the 4 tile variants in [TileCardPixelDiffTest]). Each entry pairs an
 * RC preview's PNG filename pattern with a committed reference PNG.
 *
 * Dynamic tests so a missing reference is a single skip, not a spray
 * of assumeTrue failures. A per-test threshold lets stacks / state
 * variants float higher than tile while we tune.
 */
class CardPixelDiffTest {

    private data class Case(
        val previewPattern: String,
        val referenceRel: String,
        val maxPct: Double = 30.0,
    )

    private val cases = listOf(
        // tile — pinned functions
        Case("Tile_TemperatureSensor_tile_sensor_temperature", "tile/temperature_sensor_light.png", 25.0),
        Case("Tile_TemperatureSensor_Dark_tile_sensor_temperature_dark", "tile/temperature_sensor_dark.png", 25.0),
        Case("Tile_LightOn_tile_light_on", "tile/light_on_light.png", 25.0),
        Case("Tile_LightOn_Dark_tile_light_on_dark", "tile/light_on_dark.png", 25.0),

        // tile state variants — light theme
        Case("Tile_Light_States_tile_light_light_off", "tile/light_off_light.png", 30.0),
        Case("Tile_Lock_States_tile_lock_light_locked", "tile/lock_locked_light.png", 30.0),
        Case("Tile_Cover_States_tile_cover_light_closed", "tile/cover_light.png", 40.0),

        // button
        Case("Button_Light_button_light_on", "button/light_on_light.png", 45.0),
        Case("Button_Light_button_light_off", "button/light_off_light.png", 45.0),
        Case("Button_Dark_button_dark_on", "button/light_on_dark.png", 45.0),
        Case("Button_Dark_button_dark_off", "button/light_off_dark.png", 45.0),

        // entity — TODO: HA's `entity` card is multi-row (big state,
        // small unit, icon top-right), not a single row. Our
        // RemoteHaEntityRow is the wrong primitive for this card.
        // Skip for now; see docs/followups/entity-card-redesign.md.
        //   Case("Entity_Light_entity_light", "entity/temperature_sensor_light.png", 35.0),
        //   Case("Entity_Dark_entity_dark", "entity/temperature_sensor_dark.png", 35.0),

        // entities / glance / markdown
        Case("Entities_Light_entities_light", "entities/living_room_light.png", 40.0),
        Case("Entities_Dark_entities_dark", "entities/living_room_dark.png", 40.0),
        Case("Glance_Light_glance_light", "glance/overview_light.png", 40.0),
        Case("Glance_Dark_glance_dark", "glance/overview_dark.png", 40.0),
        Case("Markdown_Light_markdown_light", "markdown/notes_light.png", 40.0),
        Case("Markdown_Dark_markdown_dark", "markdown/notes_dark.png", 40.0),
    )

    @TestFactory
    fun allCards(): List<DynamicTest> = cases.map { case ->
        DynamicTest.dynamicTest("${case.referenceRel} <= ${case.maxPct}%") {
            val rendered = locateRendered(case.previewPattern)
            val reference = referenceFile(case.referenceRel)
            assumeTrue(reference.exists(), "reference ${case.referenceRel} missing")
            val report = ImageDiff.compare(rendered, reference)
            println(report)
            if (report.pctChanged > case.maxPct) {
                error("pixel diff above threshold: ${report.pctChanged}% > ${case.maxPct}% — $report")
            }
        }
    }

    private fun locateRendered(previewName: String): File {
        val dir = File(System.getProperty("ha.rendered.dir") ?: "previews/build/compose-previews/renders")
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
