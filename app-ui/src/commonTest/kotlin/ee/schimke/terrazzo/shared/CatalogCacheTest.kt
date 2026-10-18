package ee.schimke.terrazzo.shared

import kotlin.test.*

class CatalogCacheTest {
  private class Store : CatalogCache {
    val values = mutableMapOf<String, String>()

    override fun read(key: String) = values[key]

    override fun write(key: String, value: String) {
      values[key] = value
    }
  }

  @Test
  fun verifiedLastGoodBytesSurviveAnotherReaderAndCorruptionFallsBack() {
    val store = Store()
    val bytes = byteArrayOf(1, 2, 3, 4)
    cacheTemplate(store, "anonymous-shape", bytes)
    assertContentEquals(bytes, cachedTemplate(store, "anonymous-shape"))
    assertNull(cachedTemplate(store, "other-shape"))
    store.values["template-anonymous-shape"] =
      store.values.getValue("template-anonymous-shape").replace("AQIDBA==", "AQIDBQ==")
    assertNull(cachedTemplate(store, "anonymous-shape"))
  }

  @Test
  fun storageFailureDoesNotInvalidateSuccessfulPlayback() {
    val store =
      object : CatalogCache {
        override fun read(key: String): String? = error("denied")

        override fun write(key: String, value: String) {
          error("quota exceeded")
        }
      }
    cacheTemplate(store, "shape", byteArrayOf(1))
    assertNull(cachedTemplate(store, "shape"))
  }
}
