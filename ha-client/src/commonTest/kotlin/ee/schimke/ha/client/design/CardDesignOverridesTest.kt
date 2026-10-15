package ee.schimke.ha.client.design

import ee.schimke.ha.model.CardConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CardDesignOverridesTest {
  private fun card(json: String): CardConfig {
    val raw = Json.parseToJsonElement(json).jsonObject
    return CardConfig(type = raw["type"]!!.jsonPrimitive.content, raw = raw)
  }

  private val kitchen = card("""{"type":"thermostat","entity":"climate.kitchen"}""")
  private val hall = card("""{"type":"thermostat","entity":"climate.hall"}""")

  @Test
  fun aCardOverrideWinsOverItsTypeFieldByField() {
    val overrides =
      CardDesignOverrides()
        .with(
          CardDesignScope.CardType,
          kitchen,
          CardDesignOverride(cardStyle = "braun", variant = "v"),
        )
        .with(CardDesignScope.Card, kitchen, CardDesignOverride(cardStyle = "lcd"))
    assertEquals(CardDesignOverride(cardStyle = "lcd", variant = "v"), overrides.forCard(kitchen))
    assertEquals(CardDesignOverride(cardStyle = "braun", variant = "v"), overrides.forCard(hall))
  }

  @Test
  fun aCardIsRecognisedWhateverOrderItsFieldsArriveIn() {
    val reordered = card("""{"entity":"climate.kitchen","type":"thermostat"}""")
    val overrides =
      CardDesignOverrides()
        .with(CardDesignScope.Card, kitchen, CardDesignOverride(cardStyle = "lcd"))
    assertEquals("lcd", overrides.forCard(reordered)?.cardStyle)
    assertNull(overrides.forCard(hall))
  }

  @Test
  fun clearingAnOverrideRemovesIt() {
    val set =
      CardDesignOverrides()
        .with(CardDesignScope.Card, kitchen, CardDesignOverride(cardStyle = "lcd"))
    assertEquals(CardDesignOverrides(), set.with(CardDesignScope.Card, kitchen, null))
    assertEquals(
      CardDesignOverrides(),
      set.with(CardDesignScope.Card, kitchen, CardDesignOverride()),
    )
  }

  @Test
  fun stylesAndVariantsListWhatTheSyncMustFetch() {
    val overrides =
      CardDesignOverrides()
        .with(CardDesignScope.Card, kitchen, CardDesignOverride(cardStyle = "lcd"))
        .with(CardDesignScope.CardType, hall, CardDesignOverride(variant = "garage"))
    assertEquals(setOf("lcd"), overrides.cardStyles)
    assertEquals(setOf("garage"), overrides.variants)
  }

  @Test
  fun overridesRoundTripAndSurviveBadInput() {
    val overrides =
      CardDesignOverrides()
        .with(CardDesignScope.Card, kitchen, CardDesignOverride(cardStyle = "lcd"))
    assertEquals(overrides, CardDesignOverrides.decode(overrides.encode()))
    assertEquals(CardDesignOverrides(), CardDesignOverrides.decode("not json"))
    assertEquals(CardDesignOverrides(), CardDesignOverrides.decode(null))
  }
}
