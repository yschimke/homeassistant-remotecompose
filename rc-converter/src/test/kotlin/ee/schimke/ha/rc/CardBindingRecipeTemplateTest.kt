package ee.schimke.ha.rc

import androidx.test.core.app.ApplicationProvider
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.design.CardBindingRecipe
import ee.schimke.ha.design.CardBindingRecipes
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.gaugeRangeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A template captured from a converter with a [CardBindingRecipe] carries it, so a host without the
 * converters can compute the card-level bindings ([CardConverter.designRecipe]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CardBindingRecipeTemplateTest {
  private val registry = defaultRegistry()

  private fun template(card: CardConfig, snapshot: HaSnapshot): ByteArray {
    val key =
      assertNotNull(CardDesignTemplates.keyFor(card, snapshot, ThemeStyle.entries.first(), false))
    return CardDesignTemplates.capture(ApplicationProvider.getApplicationContext(), key, registry)
      .bytes
  }

  /** The recipe a template's bytes carry, or null when it has none. */
  private fun embeddedRecipe(bytes: ByteArray): JsonObject? {
    val operations = RcDocumentCodec.decode(bytes, RcWireLimits()).operations
    val variable =
      operations.filterIsInstance<RcNamedVariable>().firstOrNull {
        it.name.removePrefix("USER:") == CardBindingRecipe.BINDING
      } ?: return null
    val text = operations.filterIsInstance<RcTextData>().first { it.id == variable.id }.text
    return Json.parseToJsonElement(text) as JsonObject
  }

  private val cpu =
    EntityState(
      "sensor.cpu",
      "64",
      JsonObject(mapOf("unit_of_measurement" to JsonPrimitive("%"))),
    )

  @Test
  fun gaugeTemplateCarriesItsRecipe() {
    val card =
      CardConfig(
        "gauge",
        buildJsonObject {
          put("type", "gauge")
          put("entity", "sensor.cpu")
        },
      )
    val bytes = template(card, HaSnapshot(states = mapOf(cpu.entityId to cpu)))
    assertEquals(CardBindingRecipes.GAUGE.toJson(), embeddedRecipe(bytes))
  }

  @Test
  fun tileTemplateHasNoRecipe() {
    val light = EntityState("light.kitchen", "on")
    val card =
      CardConfig(
        "tile",
        buildJsonObject {
          put("type", "tile")
          put("entity", light.entityId)
        },
      )
    assertNull(embeddedRecipe(template(card, HaSnapshot(states = mapOf(light.entityId to light)))))
  }

  @Test
  fun gaugeRecipeMatchesTheConvertersBindings() {
    val card =
      CardConfig(
        "gauge",
        buildJsonObject {
          put("type", "gauge")
          put("entity", "sensor.cpu")
          put("min", 20)
          put("max", "90.25")
          put("severity", buildJsonObject { put("red", 80) })
        },
      )
    val bindings =
      assertNotNull(
        registry
          .converterFor("gauge")
          ?.designBindings(card, HaSnapshot(states = mapOf(cpu.entityId to cpu)))
      )
    assertEquals(20f, bindings.floats["card.min"])
    assertEquals(90.25f, bindings.floats["card.max"])
    assertEquals(80f, bindings.floats["card.severity_red"])
    assertEquals(1e30f, bindings.floats["card.severity_green"])
    assertEquals(64f, bindings.floats["sensor.s0.numeric_state"])
    assertEquals(gaugeRangeText(20.0, 90.25, "%"), bindings.strings["card.range"])
  }

  /** The live render's range line and the recipe's must be the same text. */
  @Test
  fun rangeTextAgreesWithTheRecipe() {
    for (value in listOf(0.0, -0.04, -0.05, -0.5, 0.25, 0.15, 1.05, 12.34, 99.95, -12.25, 1e6)) {
      assertEquals(
        gaugeRangeText(value, value, "u"),
        CardBindingRecipe.rangeText(value, value, "u"),
        "$value",
      )
    }
  }
}
