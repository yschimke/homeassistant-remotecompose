package ee.schimke.ha.rc

import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignSurface
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The local library's templates on disk. */
class FileLocalDesignStoreTest {
  private val key =
    DesignKey(
      cardType = "tile",
      surface = DesignSurface.MobileApp,
      rcApiLevel = 8,
      shape = "domain=light",
      theme = DesignKey.bakedTheme("Material3", dark = false),
    )
  private val bytes = "captured rc document".encodeToByteArray()

  @Test
  fun keepsATemplateForItsScopeOnly() {
    val root = Files.createTempDirectory("designs").toFile()
    var scope = "build1-320-1.0"
    val store = FileLocalDesignStore(root) { scope }
    assertNull(store.read(key))
    store.write(key, bytes)
    assertContentEquals(bytes, store.read(key))
    assertNull(store.read(key.copy(shape = "domain=switch")))

    // A new build, or a larger font: earlier captures don't apply, and pruning drops them.
    scope = "build2-320-1.0"
    assertNull(store.read(key))
    store.write(key, bytes)
    store.prune()
    assertEquals(listOf("build2-320-1.0"), root.list()?.toList())
    assertContentEquals(bytes, store.read(key))
  }
}
