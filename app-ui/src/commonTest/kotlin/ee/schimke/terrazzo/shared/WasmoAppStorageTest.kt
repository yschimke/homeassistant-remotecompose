package ee.schimke.terrazzo.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WasmoAppStorageTest {
  private class Transport : AppStorageTransport {
    var values = mutableMapOf<String, String>()
    val writes = mutableListOf<Pair<String, String?>>()
    var fail = false
    var beforeImport: (() -> Unit)? = null
    var gate: CompletableDeferred<Unit>? = null
    var seeded = CompletableDeferred<Unit>().apply { complete(Unit) }

    override suspend fun read(): Map<String, String> {
      seeded.await()
      return values.toMap()
    }

    override suspend fun importIfEmpty(values: Map<String, String>): Map<String, String> {
      if (fail) error("offline")
      beforeImport?.invoke()
      if (this.values.isEmpty()) this.values.putAll(values)
      return this.values.toMap()
    }

    override suspend fun write(key: String, value: String?) {
      writes += key to value
      gate?.await()
      if (fail) error("offline")
      if (value == null) values.remove(key) else values[key] = value
    }
  }

  private fun browser(values: MutableMap<String, String>) =
    LocalAppStorage(values::get, { key, value -> values[key] = value }, { values.remove(it) })

  @Test
  fun seedsBeforeReturningAndNeverOverwritesAnExistingInstall() = runTest {
    val transport =
      Transport().apply {
        values["sharing"] = "cloud"
        seeded = CompletableDeferred()
      }
    val local = mutableMapOf("sharing" to "browser")
    val loading = async {
      WasmoAppStorage.create(transport, backgroundScope, browser(local), setOf("sharing"))
    }
    runCurrent()
    assertEquals(false, loading.isCompleted)
    transport.seeded.complete(Unit)
    val storage = loading.await()
    assertEquals(StorageLocation.WASMO, storage.location)
    assertEquals("cloud", storage.get("sharing"))
    assertEquals("browser", local["sharing"])
  }

  @Test
  fun atomicMigrationOnlyRemovesAcknowledgedKnownKeys() = runTest {
    val transport = Transport()
    val local =
      mutableMapOf("sharing" to "yes", "ledger" to "ledger", "terrazzo-oauth" to "private")
    val storage =
      WasmoAppStorage.create(transport, backgroundScope, browser(local), setOf("sharing", "ledger"))
    assertEquals("yes", storage.get("sharing"))
    assertEquals(mapOf("sharing" to "yes", "ledger" to "ledger"), transport.values)
    assertEquals(mapOf("terrazzo-oauth" to "private"), local)
  }

  @Test
  fun concurrentDeviceImportKeepsConflictingBrowserValues() = runTest {
    val transport = Transport().apply { beforeImport = { values["ledger"] = "other-device" } }
    val local = mutableMapOf("ledger" to "browser")
    val storage =
      WasmoAppStorage.create(transport, backgroundScope, browser(local), setOf("ledger"))
    assertEquals("other-device", storage.get("ledger"))
    assertEquals("browser", local["ledger"])
  }

  @Test
  fun failedImportPreservesBrowserDataAndFailsStartup() = runTest {
    val transport = Transport().apply { fail = true }
    val local = mutableMapOf("ledger" to "pending")
    assertFailsWith<IllegalStateException> {
      WasmoAppStorage.create(transport, backgroundScope, browser(local), setOf("ledger"))
    }
    assertEquals("pending", local["ledger"])
  }

  @Test
  fun rapidWritesCoalesceAndDeleteWritesThrough() = runTest {
    val transport = Transport()
    val storage = WasmoAppStorage.create(transport, this, browser(mutableMapOf()), emptySet())
    storage.set("key", "a")
    storage.set("key", "b")
    assertEquals("b", storage.get("key"))
    assertEquals(StorageSyncState.SAVING, storage.syncState.value)
    advanceUntilIdle()
    assertEquals(listOf<Pair<String, String?>>("key" to "b"), transport.writes)
    storage.set("key", null)
    assertNull(storage.get("key"))
    advanceUntilIdle()
    assertEquals(listOf("key" to "b", "key" to null), transport.writes)
    assertEquals(StorageSyncState.SAVED, storage.syncState.value)
  }

  @Test
  fun writesDuringAnInflightRequestRemainOrdered() = runTest {
    val transport = Transport().apply { gate = CompletableDeferred() }
    val storage = WasmoAppStorage.create(transport, this, browser(mutableMapOf()), emptySet())
    storage.set("key", "old")
    advanceTimeBy(101)
    runCurrent()
    storage.set("key", "new")
    transport.gate!!.complete(Unit)
    advanceUntilIdle()
    assertEquals(listOf<Pair<String, String?>>("key" to "old", "key" to "new"), transport.writes)
    assertEquals("new", transport.values["key"])
  }

  @Test
  fun failureKeepsMemoryAndRetriesOnNextWrite() = runTest {
    val transport = Transport().apply { fail = true }
    val storage = WasmoAppStorage.create(transport, this, browser(mutableMapOf()), emptySet())
    storage.set("pending", "keep")
    advanceUntilIdle()
    assertEquals("keep", storage.get("pending"))
    assertEquals(StorageSyncState.FAILED, storage.syncState.value)
    transport.fail = false
    storage.set("other", "next")
    advanceUntilIdle()
    assertEquals(mapOf("pending" to "keep", "other" to "next"), transport.values)
    assertEquals(StorageSyncState.SAVED, storage.syncState.value)
  }
}
