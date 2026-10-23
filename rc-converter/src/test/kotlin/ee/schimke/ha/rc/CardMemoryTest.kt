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
}
