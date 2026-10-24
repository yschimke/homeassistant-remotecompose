package ee.schimke.ha.rc

import android.content.ComponentCallbacks2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The card caches give memory back when the system asks, keeping the most recent while hidden. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CardMemoryTest {

  private fun document(i: Int) = CardDocument(ByteArray(1) { i.toByte() }, i, i)

  @Test
  fun trimToSizeKeepsTheMostRecentlyUsed() {
    val cache = InMemoryCardDocumentCache(maxEntries = 8)
    repeat(8) { cache.put(it, document(it)) }
    // Touch the oldest, so it is among the most recent.
    assertNotNull(cache.get(0))

    cache.trimToSize(3)

    assertNotNull(cache.get(0))
    assertNotNull(cache.get(7))
    assertNotNull(cache.get(6))
    assertNull(cache.get(5))
    assertNull(cache.get(1))
  }

  @Test
  fun uiHiddenShrinksTheDocumentCacheAndBackgroundEmptiesIt() {
    defaultCardDocumentCache.clear()
    val count = CardMemory.HIDDEN_DOCUMENTS + 10
    repeat(count) { defaultCardDocumentCache.put("card-$it", document(it)) }

    assertFalse(CardMemory.trim(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
    val kept = (0 until count).count { defaultCardDocumentCache.get("card-$it") != null }
    assertEquals(CardMemory.HIDDEN_DOCUMENTS, kept)
    assertNotNull(defaultCardDocumentCache.get("card-${count - 1}"))

    assertTrue(CardMemory.trim(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
    assertTrue((0 until count).none { defaultCardDocumentCache.get("card-$it") != null })
  }

  @Test
  fun aTrimReportsHowManyOfThePreviousTrimsDocumentsCameBack() {
    val cache = InMemoryCardDocumentCache(maxEntries = 16)
    repeat(10) { cache.put(it, document(it)) }

    val first = cache.trim(4)
    assertEquals(
      DocumentCacheTrim(sizeBefore = 10, kept = 4, previousDropped = 0, previousReRequested = 0),
      first,
    )
    assertEquals(6, first.dropped)

    // Back in view: two dropped documents asked for again (one twice), one never cached.
    cache.get(0)
    cache.get(0)
    cache.get(1)
    cache.get(99)
    cache.get(9) // kept: a hit

    val second = cache.trim(4)
    assertEquals(6, second.previousDropped)
    assertEquals(2, second.previousReRequested)
  }

  @Test
  fun cardMemoryReportsDocumentTrims() {
    defaultCardDocumentCache.clear()
    val reports = mutableListOf<Triple<Int, Int, DocumentCacheTrim>>()
    CardMemory.onDocumentTrim = { level, previous, trim ->
      reports += Triple(level, previous, trim)
    }
    try {
      CardMemory.trim(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) // reset the previous trim
      reports.clear()
      repeat(CardMemory.HIDDEN_DOCUMENTS + 5) {
        defaultCardDocumentCache.put("r-$it", document(it))
      }

      CardMemory.trim(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
      defaultCardDocumentCache.get("r-0")
      CardMemory.trim(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)

      val (level, previous, trim) = reports.last()
      assertEquals(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, level)
      assertEquals(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, previous)
      assertEquals(5, trim.previousDropped)
      assertEquals(1, trim.previousReRequested)
      assertEquals(2, reports.size)
    } finally {
      CardMemory.onDocumentTrim = null
    }
  }
}
