package ee.schimke.terrazzo.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppStorageTest {
  @Test
  fun localStoragePreservesExistingValuesAndWritesThrough() {
    val values = mutableMapOf("terrazzo.usage.sharing" to "true")
    val storage =
      LocalAppStorage(values::get, { key, value -> values[key] = value }, { values.remove(it) })
    assertEquals(StorageLocation.BROWSER, storage.location)
    assertEquals("true", storage.get("terrazzo.usage.sharing"))
    storage.set("terrazzo.usage.ledger", "ledger")
    assertEquals("ledger", values["terrazzo.usage.ledger"])
    storage.set("terrazzo.usage.ledger", null)
    assertNull(storage.get("terrazzo.usage.ledger"))
  }

  @Test
  fun deniedBrowserStorageDoesNotBreakTheApp() {
    val storage =
      LocalAppStorage({ error("denied") }, { _, _ -> error("denied") }, { error("denied") })
    assertNull(storage.get("key"))
    storage.set("key", "value")
    storage.set("key", null)
  }
}
