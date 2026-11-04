package ee.schimke.ha.rc.cards

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A vertical stack on a fixed canvas shows only the children that fit whole. */
class VerticalStackFitTest {
  @Test
  fun keepsTheChildrenThatFitWhole() {
    // 60 + 8 + 60 = 128 fits a 130dp canvas; the third would need 196.
    assertEquals(2, fittingChildren(listOf(60, 60, 60), availableDp = 130))
    assertEquals(3, fittingChildren(listOf(60, 60, 60), availableDp = 196))
  }

  @Test
  fun dropsAChildThatWouldOnlyShowAsASliver() {
    // The second child would start 8dp above the bottom edge: it is left out, not cut off.
    assertEquals(1, fittingChildren(listOf(100, 100), availableDp = 116))
  }

  @Test
  fun alwaysShowsTheFirstChild() {
    assertEquals(1, fittingChildren(listOf(200, 60), availableDp = 112))
  }

  @Test
  fun aNestedContainerIsAsTallAsItsChildren() {
    val registry = defaultRegistry()
    val entity = card("""{"type":"entity","entity":"sensor.a"}""")
    val one = stackChildHeightDp(entity, HaSnapshot(), registry)
    // Not the 160dp a container's converter would report on its own.
    val stack =
      card(
        """{"type":"vertical-stack","cards":[{"type":"entity","entity":"sensor.a"},
            {"type":"entity","entity":"sensor.b"}]}"""
      )
    assertEquals(one * 2 + 8, stackChildHeightDp(stack, HaSnapshot(), registry))
    val row =
      card(
        """{"type":"horizontal-stack","cards":[{"type":"entity","entity":"sensor.a"},
            {"type":"entity","entity":"sensor.b"}]}"""
      )
    assertEquals(one, stackChildHeightDp(row, HaSnapshot(), registry))
  }

  private fun card(json: String): CardConfig {
    val obj = Json.parseToJsonElement(json).jsonObject
    return CardConfig(obj["type"]!!.jsonPrimitive.content, obj)
  }
}
