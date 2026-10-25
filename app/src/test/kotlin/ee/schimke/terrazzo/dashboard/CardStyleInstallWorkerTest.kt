package ee.schimke.terrazzo.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

class CardStyleInstallWorkerTest {
  @Test
  fun selectionWaitsForACompleteInstallThenRefreshesWidgets() = runTest {
    val download = CompletableDeferred<Boolean>()
    val events = mutableListOf<String>()
    val result = async {
      installAndSelect(
        install = {
          events += "download"
          download.await()
        },
        select = { events += "select" },
        refresh = { events += "refresh" },
      )
    }
    testScheduler.runCurrent()
    assertEquals(listOf("download"), events)
    download.complete(true)
    assertTrue(result.await())
    assertEquals(listOf("download", "select", "refresh"), events)
  }

  @Test
  fun failedInstallKeepsTheExistingSelection() = runTest {
    assertFalse(
      installAndSelect(
        install = { false },
        select = { error("A failed install must not be selected") },
        refresh = { error("A failed install must not refresh widgets") },
      )
    )
  }
}
