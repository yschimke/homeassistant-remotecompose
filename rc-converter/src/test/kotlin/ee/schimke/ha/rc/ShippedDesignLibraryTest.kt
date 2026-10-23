package ee.schimke.ha.rc

import ee.schimke.ha.client.design.BuiltDesign
import ee.schimke.ha.client.design.DesignPublisher
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignManifest
import ee.schimke.ha.design.DesignSurface
import java.io.FileNotFoundException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The designs an app ships in its assets. */
class ShippedDesignLibraryTest {
  private val key =
    DesignKey(
      cardType = "tile",
      surface = DesignSurface.MobileApp,
      rcApiLevel = 8,
      shape = "domain=light",
      theme = DesignKey.bakedTheme("TerrazzoHome", dark = false),
    )
  private val bytes = "shipped rc document".encodeToByteArray()

  private fun library(files: Map<String, ByteArray>) = ShippedDesignLibrary { path ->
    files[path]?.inputStream() ?: throw FileNotFoundException(path)
  }

  @Test
  fun readsTheDesignsItsManifestNames() {
    val files =
      DesignPublisher.publish(listOf(BuiltDesign(key, slots = 1, bytes = bytes)), 1, 0).files
    val shipped = library(files)
    assertEquals(setOf(key), shipped.keys)
    assertContentEquals(bytes, shipped.read(key))
    assertNull(shipped.read(key.copy(shape = "domain=switch")))
  }

  @Test
  fun aBlobThatDoesNotMatchItsManifestIsNeverRead() {
    val output = DesignPublisher.publish(listOf(BuiltDesign(key, slots = 1, bytes = bytes)), 1, 0)
    val blob = DesignManifest.blobPath(output.manifest.designs.single().sha256)
    val shipped = library(output.files + (blob to "tampered".encodeToByteArray()))
    assertNull(shipped.read(key))
  }

  @Test
  fun anAppWithoutShippedDesignsShipsNothing() {
    val shipped = library(emptyMap())
    assertEquals(emptySet(), shipped.keys)
    assertNull(shipped.read(key))
  }
}
