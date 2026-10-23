package ee.schimke.terrazzo.dashboard

import ee.schimke.ha.client.design.DesignedBlob
import ee.schimke.ha.client.design.DesignsById
import ee.schimke.ha.client.design.ResolvedDesigns
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignQuery
import ee.schimke.ha.design.DesignSurface
import ee.schimke.ha.design.DeviceMatch
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CardDesignSnapshotTest {
  private val dir: File = createTempDirectory("designs").toFile()
  private val manifest = File(dir, "manifest.json").apply { writeText("{\"serial\":1}") }
  private val key =
    DesignKey(
      cardType = "tile",
      surface = DesignSurface.MobileApp,
      rcApiLevel = 8,
      shape = "domain=light",
      theme = "baked:Material3:light",
    )
  private val match = DeviceMatch(integration = "vaillant")
  private val designs =
    ResolvedDesigns(
      byKey = mapOf(key to byteArrayOf(1, 2, 3)),
      deviceByKey = mapOf(key to listOf(match to byteArrayOf(4, 5))),
      byDesign =
        DesignsById(
          mapOf(
            "user:fork" to
              listOf(
                DesignedBlob(key, null, byteArrayOf(6)),
                DesignedBlob(key, match, byteArrayOf(7)),
              )
          )
        ),
    )
  private val prefer = listOf(DesignQuery(author = "terrazzo"))
  private val ids = setOf("user:fork")

  @AfterTest fun cleanUp() = dir.deleteRecursively().let {}

  @Test
  fun readsBackWhatItWrote() {
    CardDesignSnapshot.write(dir, prefer, ids, CardDesignSnapshot.manifestStamp(dir), designs)

    val read = assertNotNull(CardDesignSnapshot.read(dir, prefer, ids))
    assertContentEquals(byteArrayOf(1, 2, 3), read.byKey[key])
    val device = read.deviceByKey[key].orEmpty().single()
    assertEquals(match, device.first)
    assertContentEquals(byteArrayOf(4, 5), device.second)
    assertContentEquals(byteArrayOf(6), read.byDesign.find("user:fork", key, null)?.second)
    assertContentEquals(
      byteArrayOf(7),
      read.byDesign.find("user:fork", key, ee.schimke.ha.design.DeviceInfo("vaillant"))?.second,
    )
  }

  @Test
  fun aNewManifestInvalidatesIt() {
    CardDesignSnapshot.write(dir, prefer, ids, CardDesignSnapshot.manifestStamp(dir), designs)
    manifest.writeText("{\"serial\":2, \"designs\":[]}")

    assertNull(CardDesignSnapshot.read(dir, prefer, ids))
  }

  @Test
  fun aBlobStoredSinceInvalidatesIt() {
    // A sync that only fills in blobs (a design just chosen) leaves the manifest as it was.
    val blobs = File(dir, "blobs").apply { mkdirs() }
    blobs.setLastModified(System.currentTimeMillis() - 60_000)
    CardDesignSnapshot.write(dir, prefer, ids, CardDesignSnapshot.manifestStamp(dir), designs)
    assertNotNull(CardDesignSnapshot.read(dir, prefer, ids))

    File(blobs, "${"a".repeat(64)}.rc").writeBytes(byteArrayOf(9))

    assertNull(CardDesignSnapshot.read(dir, prefer, ids))
  }

  @Test
  fun otherPreferencesHaveTheirOwn() {
    CardDesignSnapshot.write(dir, prefer, ids, CardDesignSnapshot.manifestStamp(dir), designs)

    assertNull(CardDesignSnapshot.read(dir, listOf(DesignQuery(author = "github:other")), ids))
    // Switching another card to a design is another resolution too.
    assertNull(CardDesignSnapshot.read(dir, prefer, ids + "user:other"))
  }
}
