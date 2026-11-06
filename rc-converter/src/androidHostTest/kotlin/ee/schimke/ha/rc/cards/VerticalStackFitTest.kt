package ee.schimke.ha.rc.cards

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The heights a Wear-slot vertical stack fits its children by. */
class VerticalStackFitTest {
  @Test
  fun aNestedContainerIsAsTallAsItsChildren() {
    val registry = defaultRegistry()
    val entity = card("""{"type":"entity","entity":"sensor.a"}""")
    val one = stackChildHeightDp(entity, HaSnapshot(), registry)
    // Not the 160dp a container's converter would report on its own.
    val stack =
      card(
        """{"type":"vertical-stack","cards":[{"type":"entity","entity":"sensor.a"},
            {"type":"entity","entity":"sensor.b"}]}""",
      )
    assertEquals(one * 2 + 8, stackChildHeightDp(stack, HaSnapshot(), registry))
    val row =
      card(
        """{"type":"horizontal-stack","cards":[{"type":"entity","entity":"sensor.a"},
            {"type":"entity","entity":"sensor.b"}]}""",
      )
    assertEquals(one, stackChildHeightDp(row, HaSnapshot(), registry))
  }

  @Test
  fun aHiddenConditionalTakesNoRoom() {
    val registry = defaultRegistry()
    val one =
      stackChildHeightDp(card("""{"type":"entity","entity":"sensor.a"}"""), HaSnapshot(), registry)
    val stack =
      card(
        """{"type":"vertical-stack","cards":[
            {"type":"conditional","conditions":[{"entity":"sensor.gone","state":"on"}],
             "card":{"type":"entity","entity":"sensor.a"}},
            {"type":"entity","entity":"sensor.b"}]}""",
      )
    assertEquals(one, stackChildHeightDp(stack, HaSnapshot(), registry))
  }

  private fun card(json: String): CardConfig {
    val obj = Json.parseToJsonElement(json).jsonObject
    return CardConfig(obj["type"]!!.jsonPrimitive.content, obj)
  }
}
