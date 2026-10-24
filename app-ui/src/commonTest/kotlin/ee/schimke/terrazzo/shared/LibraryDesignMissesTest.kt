package ee.schimke.terrazzo.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class LibraryDesignMissesTest {
  private val card = buildJsonObject {
    put("type", "tile")
    put("entity", "sensor.s0")
  }
  private val key = LibraryDesignKey(card, "lcd", dark = true, requireExact = true)

  @Test
  fun describesWhatTheServerWasAskedAndAnswered() {
    val inexact = buildJsonObject {
      put("exact", false)
      put("key", buildJsonObject { put("shape", "device_class=temperature;domain=sensor") })
    }
    assertEquals(
      """{"type":"tile","entity":"sensor.s0"} lcd/dark → inexact (shape device_class=temperature;domain=sensor)""",
      LibraryDesignMisses.describe(key, inexact),
    )
    assertEquals(
      """{"type":"tile","entity":"sensor.s0"} lcd/dark → inexact""",
      LibraryDesignMisses.describe(key, buildJsonObject { put("exact", false) }),
    )
    assertEquals(
      """{"type":"tile","entity":"sensor.s0"} classic/light → none""",
      LibraryDesignMisses.describe(key.copy(style = "classic", dark = false), null),
    )
  }

  @Test
  fun countsEachDistinctMiss() = runTest {
    val other = key.copy(style = "braun")
    LibraryDesignMisses.record(other, null)
    LibraryDesignMisses.record(other, null)
    assertEquals(2, LibraryDesignMisses.snapshot()[LibraryDesignMisses.describe(other, null)])
  }
}
