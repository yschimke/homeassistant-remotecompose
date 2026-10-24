package ee.schimke.ha.cards

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Holds `ui-builder.policy.json` to the module: every icon property offers exactly [HaCardIcons]'
 * keys, and every HA component the policy names a record for is a function this module declares.
 */
class HaCardIconsTest {
  private val policy = Json.parseToJsonElement(File("ui-builder.policy.json").readText()).jsonObject

  private val components = policy["components"]!!.jsonObject

  @Test
  fun iconPropertiesOfferExactlyTheDrawnKeys() {
    val iconProperties =
      components.values
        .flatMap { (it.jsonObject["propertyCapabilities"] as? JsonArray).orEmpty() }
        .map { it.jsonObject }
        .filter {
          val name = it["name"]!!.jsonPrimitive.content
          name == "icon" || name.endsWith("Icon")
        }
    assertTrue(iconProperties.isNotEmpty())
    for (property in iconProperties) {
      assertEquals(
        HaCardIcons.keys,
        property["allowedValues"]!!.jsonArray.map { it.jsonPrimitive.content },
        "${property["name"]} offers a different icon list from HaCardIcons",
      )
    }
  }

  @Test
  fun everyHaRecordIsDeclaredHere() {
    val records =
      (components.values + policy["builtins"]!!.jsonObject.values)
        .mapNotNull {
          (it.jsonObject["record"] ?: it.jsonObject["implementation"])?.jsonPrimitive?.content
        }
        .toSet()
    for (file in listOf("HaCardComponentsKt", "HaCardDesignPartsKt")) {
      val prefix = "ha-cards-catalog/ee.schimke.ha.cards.$file."
      val declared = Class.forName("ee.schimke.ha.cards.$file").declaredMethods.map { it.name }
      for (name in records.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }) {
        assertTrue(name in declared, "the policy names $name, which $file lacks")
      }
    }
  }
}
