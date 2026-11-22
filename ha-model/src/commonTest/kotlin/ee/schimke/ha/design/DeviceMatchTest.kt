package ee.schimke.ha.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceMatchTest {
  private val boiler =
    DeviceInfo(
      integration = "vaillant",
      manufacturer = "Vaillant",
      model = "ecoTEC plus 832",
      translationKey = "flow_temperature",
    )

  @Test
  fun everySetFieldMustMatchIgnoringCase() {
    assertTrue(DeviceMatch(integration = "VAILLANT").matches(boiler))
    assertTrue(DeviceMatch(manufacturer = "vaillant", model = "ecoTEC plus 832").matches(boiler))
    assertFalse(DeviceMatch(integration = "vaillant", model = "aroTHERM").matches(boiler))
    assertFalse(DeviceMatch(manufacturer = "Vaillant").matches(DeviceInfo(integration = "mqtt")))
  }

  @Test
  fun aModelPatternTakesWildcards() {
    assertTrue(DeviceMatch(model = "ecoTEC*").matches(boiler))
    assertTrue(DeviceMatch(model = "*plus*").matches(boiler))
    assertFalse(DeviceMatch(model = "ecoTEC").matches(boiler))
    // Pattern text is literal apart from `*`.
    assertFalse(DeviceMatch(model = "ecoTEC.plus*").matches(boiler))
  }

  @Test
  fun anEmptyMatchMatchesNothing() {
    assertFalse(DeviceMatch().matches(boiler))
  }

  @Test
  fun theMostSpecificMatchWinsThenTheGenericDesign() {
    val candidates =
      listOf<Pair<DeviceMatch?, String>>(
        null to "generic",
        DeviceMatch(integration = "vaillant") to "vaillant",
        DeviceMatch(integration = "vaillant", model = "ecoTEC*") to "ecotec",
        DeviceMatch(integration = "nibe") to "nibe",
      )
    assertEquals("ecotec", DeviceMatch.pick(candidates, boiler))
    assertEquals("vaillant", DeviceMatch.pick(candidates, boiler.copy(model = "aroTHERM")))
    assertEquals("generic", DeviceMatch.pick(candidates, DeviceInfo(integration = "mqtt")))
    assertEquals("generic", DeviceMatch.pick(candidates, null))
    assertNull(DeviceMatch.pick(candidates.drop(1), DeviceInfo(integration = "mqtt")))
  }

  @Test
  fun aModelDesignBeatsABrandDesignThatSetsMoreFields() {
    val candidates =
      listOf<Pair<DeviceMatch?, String>>(
        DeviceMatch(integration = "vaillant", manufacturer = "Vaillant") to "brand",
        DeviceMatch(model = "ecoTEC*") to "model",
        DeviceMatch(translationKey = "flow_temperature") to "role",
      )
    assertEquals("model", DeviceMatch.pick(candidates, boiler))
    assertEquals("brand", DeviceMatch.pick(candidates, boiler.copy(model = "aroTHERM")))
    assertEquals(
      "role",
      DeviceMatch.pick(candidates, DeviceInfo(translationKey = "flow_temperature")),
    )
  }

  @Test
  fun withinAScopeMoreFieldsRankHigher() {
    val brand = DeviceMatch(integration = "vaillant")
    val brandRole = DeviceMatch(integration = "vaillant", translationKey = "flow_temperature")
    assertTrue(brandRole.rank > brand.rank)
    assertTrue(brand.rank > DeviceMatch(translationKey = "flow_temperature").rank)
    assertTrue(
      DeviceMatch(model = "ecoTEC*").rank >
        DeviceMatch(integration = "vaillant", manufacturer = "v", translationKey = "t").rank,
    )
    assertEquals(DeviceScope.Model, DeviceMatch(manufacturer = "v", model = "x").scope)
    assertEquals(0, DeviceMatch().rank)
    assertNull(DeviceMatch(model = " ").scope)
  }

  @Test
  fun matchesThatDifferOnlyInCaseNormalizeToTheSameMatch() {
    assertEquals(
      DeviceMatch(manufacturer = "vaillant", model = "ecotec*"),
      DeviceMatch(manufacturer = " Vaillant ", model = "ecoTEC*", integration = " ").normalized(),
    )
    assertEquals(0, DeviceMatch(integration = "  ").normalized().specificity)
  }
}
