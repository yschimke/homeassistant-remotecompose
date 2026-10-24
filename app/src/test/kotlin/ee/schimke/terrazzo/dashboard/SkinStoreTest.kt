package ee.schimke.terrazzo.dashboard

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class SkinStoreTest {
  private val dir: File = createTempDirectory("skins").toFile()
  private val file = File(dir, "skins.json")

  @AfterTest fun cleanUp() = dir.deleteRecursively().let {}

  @Test
  fun nothingIsInstalledAtFirst() {
    assertEquals(emptySet(), SkinStore(file).installed.value)
  }

  @Test
  fun anInstalledSkinIsKeptAcrossRestarts() = runBlocking {
    SkinStore(file).add("user:boiler-dial")
    assertEquals(setOf("user:boiler-dial"), SkinStore(file).installed.value)
  }

  @Test
  fun aRemovedSkinStaysRemoved() = runBlocking {
    val store = SkinStore(file)
    store.add("user:boiler-dial")
    store.add("user:big-number")
    store.remove("user:boiler-dial")
    assertEquals(setOf("user:big-number"), SkinStore(file).installed.value)
  }

  @Test
  fun anUnreadableFileIsNothingInstalled() {
    file.writeText("not json")
    assertEquals(emptySet(), SkinStore(file).installed.value)
  }
}
