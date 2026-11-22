package ee.schimke.ha.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesignPropertiesTest {

  @Test
  fun shapeIsCanonicalWhateverTheBuildOrder() {
    val a = DesignProperties.build {
      string("domain", "sensor")
      string("device_class", "temperature")
      enum("state_index", 2)
    }
    val b = DesignProperties.build {
      enum("state_index", 2)
      string("device_class", "temperature")
      string("domain", "sensor")
    }
    assertEquals("device_class=temperature;domain=sensor;state_index=2", a.shape)
    assertEquals(a, b)
    assertEquals(a.shape, b.shape)
  }

  @Test
  fun parseRoundTrips() {
    val properties = DesignProperties.build {
      string("domain", "light")
      string("icon", "mdi:lamp")
    }
    assertEquals(properties, DesignProperties.parse(properties.shape))
    assertEquals("mdi:lamp", DesignProperties.parse(properties.shape)["icon"])
  }

  @Test
  fun nullValuesAreLeftOut() {
    val properties = DesignProperties.build {
      string("domain", "switch")
      string("device_class", null)
    }
    assertEquals("domain=switch", properties.shape)
  }

  @Test
  fun separatorsAreRejected() {
    assertFailsWith<IllegalArgumentException> { DesignProperties.build { string("icon", "a;b") } }
    assertFailsWith<IllegalArgumentException> { DesignProperties.build { string("a=b", "x") } }
  }

  @Test
  fun aPropertyPublishedAsAnyAcceptsEveryValueOfIt() {
    val door = DesignProperties.parse("device_class=garage;domain=cover;state_icon=garage")
    val published = door.anyExcept(setOf("domain", "device_class"))
    assertEquals("device_class=garage;domain=cover;state_icon=*", published.shape)
    for (icon in listOf("garage", "garage-open", "arrow-up-box", "arrow-down-box")) {
      assertTrue(
        published.accepts(
          DesignProperties.parse("device_class=garage;domain=cover;state_icon=$icon"),
        ),
      )
    }
    // The kept properties still decide, and a card's own key never matches a wildcard it lacks.
    assertFalse(
      published.accepts(DesignProperties.parse("device_class=gate;domain=cover;state_icon=gate")),
    )
    assertFalse(published.accepts(DesignProperties.parse("device_class=garage;domain=cover")))
    assertFalse(
      door.accepts(
        DesignProperties.parse("device_class=garage;domain=cover;state_icon=garage-open"),
      ),
    )
  }

  @Test
  fun aKeyAcceptsACardKeyOfAnotherVariantAndAWildcardShape() {
    val card =
      DesignKey(
        cardType = "tile",
        surface = DesignSurface.MobileApp,
        rcApiLevel = 8,
        shape = "device_class=garage;domain=cover;state_icon=garage-open",
        theme = "baked:Material3:light",
      )
    val pack =
      card.copy(variant = "pack-garage", shape = "device_class=garage;domain=cover;state_icon=*")
    assertTrue(pack.accepts(card))
    assertTrue(card.copy(variant = "x").accepts(card), "a variant doesn't split a design")
    assertFalse(pack.accepts(card.copy(theme = "baked:Material3:dark")))
    assertFalse(pack.accepts(card.copy(cardType = "button")))
  }
}
