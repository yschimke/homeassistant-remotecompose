package ee.schimke.ha.cards

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Holds the measurement entries of `ui-builder.policy.json` to the code: every `presentation`
 * offers exactly [HaCardPresentations] and every `kind` exactly [HaCardMeasureKinds], so a value
 * the property editor offers is one the wrapper understands.
 */
class HaCardMeasurementsTest {
  private val components =
    Json.parseToJsonElement(File("ui-builder.policy.json").readText())
      .jsonObject["components"]!!
      .jsonObject

  private fun allowed(property: String): List<List<String>> =
    components.values
      .flatMap { it.jsonObject["propertyCapabilities"]?.jsonArray.orEmpty() }
      .map { it.jsonObject }
      .filter { it["name"]!!.jsonPrimitive.content == property }
      .map { p -> p["allowedValues"]!!.jsonArray.map { it.jsonPrimitive.content } }

  @Test
  fun presentationsMatchTheLadder() {
    val lists = allowed("presentation")
    assertEquals(3, lists.size, "thermometer, battery and measurement each take a presentation")
    lists.forEach { assertEquals(HaCardPresentations, it) }
  }

  @Test
  fun kindsMatchTheClassifier() {
    val lists = allowed("kind")
    assertTrue(lists.isNotEmpty())
    lists.forEach { assertEquals(HaCardMeasureKinds, it) }
  }
}
