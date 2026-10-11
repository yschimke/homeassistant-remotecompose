package ee.schimke.ha.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
