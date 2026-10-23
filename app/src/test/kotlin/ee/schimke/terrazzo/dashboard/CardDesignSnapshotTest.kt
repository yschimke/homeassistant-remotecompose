package ee.schimke.terrazzo.dashboard

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
    )
  private val prefer = listOf(DesignQuery(author = "terrazzo"))

  @AfterTest fun cleanUp() = dir.deleteRecursively().let {}

  @Test
  fun readsBackWhatItWrote() {
    CardDesignSnapshot.write(dir, prefer, CardDesignSnapshot.manifestStamp(dir), designs)

    val read = assertNotNull(CardDesignSnapshot.read(dir, prefer))
    assertContentEquals(byteArrayOf(1, 2, 3), read.byKey[key])
    val device = read.deviceByKey[key].orEmpty().single()
    assertEquals(match, device.first)
    assertContentEquals(byteArrayOf(4, 5), device.second)
  }

  @Test
  fun aNewManifestInvalidatesIt() {
    CardDesignSnapshot.write(dir, prefer, CardDesignSnapshot.manifestStamp(dir), designs)
    manifest.writeText("{\"serial\":2, \"designs\":[]}")

    assertNull(CardDesignSnapshot.read(dir, prefer))
  }

  @Test
  fun otherPreferencesHaveTheirOwn() {
    CardDesignSnapshot.write(dir, prefer, CardDesignSnapshot.manifestStamp(dir), designs)

    assertNull(CardDesignSnapshot.read(dir, listOf(DesignQuery(author = "github:other"))))
  }
}
