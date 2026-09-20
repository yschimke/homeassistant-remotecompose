package ee.schimke.ha.client

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardKey
import ee.schimke.ha.model.CardSize
import ee.schimke.ha.model.ClientProfile
import ee.schimke.ha.model.HaSnapshot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject

class RecordedCardGeneratorTest {
  private val key =
    CardKey(dashboardUrlPath = "lovelace", viewPath = "home", cardIndex = 0, type = "tile")
  private val size = CardSize(widthPx = 390, heightPx = 120, densityDpi = 3)
  private val tile = CardConfig(type = "tile", raw = JsonObject(emptyMap()))

  @Test
  fun generateReturnsDefensiveCopyAtRequestedSize() = runTest {
    val source = byteArrayOf(1, 2, 3)
    val generator = RecordedCardGenerator(mapOf("tile" to source))
    source[0] = 9

    val result = generator.generate(key, tile, HaSnapshot(), size, ClientProfile.Phone)!!

    assertContentEquals(byteArrayOf(1, 2, 3), result.bytes)
    assertEquals(size.widthPx, result.widthPx)
    assertEquals(size.heightPx, result.heightPx)
  }

  @Test
  fun unsupportedTypesFallThrough() = runTest {
    val generator = RecordedCardGenerator(mapOf("tile" to byteArrayOf(1)))
    val unsupported = CardConfig(type = "custom:missing", raw = JsonObject(emptyMap()))

    assertTrue(generator.supports(tile, ClientProfile.Phone))
    assertFalse(generator.supports(unsupported, ClientProfile.Phone))
    assertNull(generator.generate(key, unsupported, HaSnapshot(), size, ClientProfile.Phone))
  }
}
