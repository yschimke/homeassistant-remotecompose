package ee.schimke.terrazzo.core.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class LatestWinsTest {
  @Test
  fun olderLoadFinishingLastIsDropped() = runTest {
    val guard = LatestWins()
    var shown = "seed"
    val poll = CompletableDeferred<String>()
    val tapRefetch = CompletableDeferred<String>()

    // A poll starts, then a tap's refetch starts while the poll is still in flight.
    launch { guard.load({ poll.await() }) { shown = it } }
    runCurrent()
    launch { guard.load({ tapRefetch.await() }) { shown = it } }
    runCurrent()

    // The refetch lands first, then the older poll result arrives late.
    tapRefetch.complete("after tap")
    runCurrent()
    poll.complete("before tap")
    runCurrent()

    assertEquals("after tap", shown)
  }

  @Test
  fun loadsInOrderAllApply() = runTest {
    val guard = LatestWins()
    val shown = mutableListOf<Int>()
    assertTrue(guard.load({ 1 }) { shown += it })
    assertTrue(guard.load({ 2 }) { shown += it })
    assertEquals(listOf(1, 2), shown)
  }

  @Test
  fun beginSupersedesEarlierGenerations() {
    val guard = LatestWins()
    val first = guard.begin()
    assertTrue(guard.isLatest(first))
    val second = guard.begin()
    assertFalse(guard.isLatest(first))
    assertTrue(guard.isLatest(second))
  }
}
