package ee.schimke.ha.designs

import ee.schimke.ha.design.DesignManifest
import ee.schimke.ha.rc.CommonCaptureHost
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * [CardDesignLibrary.rebuildCardType], what the design server runs when a library design is edited
 * in UI Builder: the card type's designs are captured again from the edit, everything else is
 * carried over as it is.
 */
class RebuildCardTypeTest {
  private val json = Json { ignoreUnknownKeys = true }
  private val host = CommonCaptureHost(LibraryCaptureProfile.OPERATIONS)

  private fun manifest(dir: File) =
    json.decodeFromString(DesignManifest.serializer(), File(dir, "manifest.json").readText())

  @Test
  fun recapturesOnlyTheCardTypeAndRecordsItsSource() {
    val source = Files.createTempDirectory("library").toFile().resolve("library")
    CardDesignLibrary.generateBuiltIn(source, host)
    val before = manifest(source)
    val tiles = before.designs.count { it.key.cardType == "tile" }
    assertTrue(tiles > 0, "the built-in library has tiles")

    val design = CheckedInCardDesigns.designFor("tile")!!
    val documentJson = File(CheckedInCardDesigns.DIRECTORY, "tile.uid").readText()
    val target = Files.createTempDirectory("rebuilt").toFile().resolve("library")
    var drawn = 0
    val captured =
      CardDesignLibrary.rebuildCardType(source, target, "tile", design, documentJson, host) {
        _,
        _,
        _ ->
        drawn++
        byteArrayOf(1)
      }

    assertEquals(tiles, captured)
    val after = manifest(target)
    // The same design captures the same bytes: every blob, the tiles' included, is unchanged.
    assertEquals(
      before.designs.map { it.key to it.sha256 },
      after.designs.map { it.key to it.sha256 },
    )
    for (entry in after.designs) {
      assertTrue(File(target, DesignManifest.blobPath(entry.sha256)).isFile, "blob ${entry.sha256}")
    }
    assertEquals(documentJson, File(target, "sources/tile.uid").readText())
    // The built-in library has no catalog, so there are no previews to draw.
    assertEquals(0, drawn)
  }
}
