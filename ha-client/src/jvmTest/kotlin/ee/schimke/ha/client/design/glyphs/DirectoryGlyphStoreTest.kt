package ee.schimke.ha.client.design.glyphs

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class DirectoryGlyphStoreTest {
  @Test
  fun aPackWrittenIsReadBackByItsKeyAcrossStores() = runTest {
    val directory = Files.createTempDirectory("glyphs").toFile()
    val key = "google:lobster two 14 @2 w400 italic"
    DirectoryGlyphStore(directory).write(key, byteArrayOf(1, 2, 3))
    assertContentEquals(byteArrayOf(1, 2, 3), DirectoryGlyphStore(directory).read(key))
    assertNull(DirectoryGlyphStore(directory).read("roboto 14 @2 w400"))
    directory.deleteRecursively()
  }
}
