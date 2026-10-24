package ee.schimke.ha.rc.cards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * How full an AMS tray's spool reads. The demo's P2S has two trays at `remain: -1` (spools the AMS
 * can't estimate), which drew "-1 %".
 */
class BambuRemainTest {
  private fun remain(vararg attrs: Pair<String, Any>) =
    bambuRemainPercent(
      attrs.associate { (k, v) ->
        k to if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v as Number)
      }
    )

  @Test
  fun aKnownRemainIsItsPercentage() {
    assertEquals(100, remain("remain" to 100))
    assertEquals(0, remain("remain" to 0))
    assertEquals(35, remain("remain" to 35))
  }

  @Test
  fun minusOneMeansTheAmsCannotTell() {
    assertNull(remain("remain" to -1))
    assertNull(remain("remain" to -1, "remain_enabled" to true))
  }

  @Test
  fun anOutOfRangeOrDisabledRemainIsUnknown() {
    assertNull(remain("remain" to 101))
    assertNull(remain("remain" to 80, "remain_enabled" to false))
    assertNull(remain())
  }
}
