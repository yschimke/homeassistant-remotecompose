package ee.schimke.ha.integration

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Pixel-level regression tests for the `tile` converter.
 *
 * Inputs:
 *  - `:previews:renderAllPreviews` output (absolute path via sysprop
 *    `ha.rendered.dir`), naming `<classFqn>.<fun>_<previewName>.png`.
 *  - Committed reference PNGs under `references/<card-type>/<name>.png`
 *    (absolute path via sysprop `ha.references.dir`), captured by
 *    `integration/scripts/capture-references.sh` against a real HA demo.
 *
 * Thresholds are intentionally generous while we iterate on the
 * converter — the immediate signal we care about is "regression from our
 * own previous output," not exact HA parity. Tighten `maxPctChanged` as
 * specific cards approach pixel parity.
 */
class TileCardPixelDiffTest {

    @Test fun temperatureSensor_light_matchesReference() = diff(
        previewName = "Tile_TemperatureSensor_tile — sensor temperature",
        referenceRelPath = "tile/temperature_sensor_light.png",
    )

    @Test fun lightOn_light_matchesReference() = diff(
        previewName = "Tile_LightOn_tile — light on",
        referenceRelPath = "tile/light_on_light.png",
    )

    @Test fun temperatureSensor_dark_matchesReference() = diff(
        previewName = "Tile_TemperatureSensor_Dark_tile — sensor temperature (dark)",
        referenceRelPath = "tile/temperature_sensor_dark.png",
    )

    @Test fun lightOn_dark_matchesReference() = diff(
        previewName = "Tile_LightOn_Dark_tile — light on (dark)",
        referenceRelPath = "tile/light_on_dark.png",
    )

    private fun diff(previewName: String, referenceRelPath: String, maxPctChanged: Double = 25.0) {
        val reference = referenceFile(referenceRelPath)
        assumeTrue(
            reference.exists(),
            "reference $referenceRelPath missing — run integration/scripts/capture-references.sh",
        )
        val rendered = locateRendered(previewName)
        assumeTrue(
            rendered != null,
            "no rendered PNG matching '$previewName' — run ./gradlew :previews:renderAllPreviews",
        )
        val report = ImageDiff.compare(rendered!!, reference)
        println(report)
        assertTrue(
            report.pctChanged <= maxPctChanged,
            "pixel diff above threshold: ${report.pctChanged}% > $maxPctChanged% — $report",
        )
    }

    private fun locateRendered(previewName: String): File? {
        val dir = File(System.getProperty("ha.rendered.dir") ?: "previews/build/compose-previews/renders")
        val exact = dir.listFiles { f -> f.name.contains(previewName) && f.extension == "png" }
        return exact?.firstOrNull()
    }

    private fun referenceFile(rel: String): File {
        val dir = File(System.getProperty("ha.references.dir") ?: "references")
        return File(dir, rel)
    }
}
