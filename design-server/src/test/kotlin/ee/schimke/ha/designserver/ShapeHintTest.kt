package ee.schimke.ha.designserver

import ee.schimke.ha.design.DesignEntry
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignSurface
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [CardResolve.narrowByShapeHint]: how the converter-less web path picks the one design its card's
 * shape names from the shapes its slots fit.
 */
class ShapeHintTest {
  private fun design(shape: String) =
    LibraryDesign(
      id = shape,
      entry =
        DesignEntry(
          key =
            DesignKey(
              cardType = "tile",
              surface = DesignSurface.MobileApp,
              rcApiLevel = 8,
              shape = shape,
              theme = "style:lcd:light",
            ),
          sha256 = "0".repeat(64),
          bytes = 0,
          slots = 1,
        ),
      catalog = null,
    )

  private fun narrow(hint: Map<String, String>, vararg shapes: String): List<String> =
    CardResolve.narrowByShapeHint(shapes.map(::design), hint).map { it.id }

  @Test
  fun aDeviceClassPinsOneBinarySensorShape() {
    assertEquals(
      listOf("device_class=door;domain=binary_sensor"),
      narrow(
        mapOf("device_class" to "door"),
        "device_class=door;domain=binary_sensor",
        "device_class=motion;domain=binary_sensor",
      ),
    )
  }

  @Test
  fun deviceClassAndUnitPinOneSensorShape() {
    assertEquals(
      listOf("device_class=temperature;domain=sensor;unit=°C"),
      narrow(
        mapOf("device_class" to "temperature", "unit" to "°C"),
        "device_class=temperature;domain=sensor;unit=°C",
        "device_class=humidity;domain=sensor;unit=%",
      ),
    )
  }

  @Test
  fun aUnitInTheHintSelectsTheMeteredShape() {
    // The card carries a unit, so only the metered shape accounts for every observable property.
    assertEquals(
      listOf("device_class=temperature;domain=sensor;unit=°C"),
      narrow(
        mapOf("device_class" to "temperature", "unit" to "°C"),
        "device_class=temperature;domain=sensor",
        "device_class=temperature;domain=sensor;unit=°C",
      ),
    )
  }

  @Test
  fun aHintPropertyNoShapeCarriesStaysInexact() {
    // A door tile with a custom icon the library has no design for: narrowing must not borrow the
    // icon-less door design and call it exact (it would draw the default icon). Keep every shape so
    // the result stays inexact and the card falls back.
    assertEquals(
      listOf("device_class=door;domain=binary_sensor", "device_class=motion;domain=binary_sensor"),
      narrow(
        mapOf("device_class" to "door", "icon" to "mdi:custom-door"),
        "device_class=door;domain=binary_sensor",
        "device_class=motion;domain=binary_sensor",
      ),
    )
  }

  @Test
  fun noHintLeavesEveryShape() {
    assertEquals(
      listOf("device_class=door;domain=binary_sensor", "device_class=motion;domain=binary_sensor"),
      narrow(
        emptyMap(),
        "device_class=door;domain=binary_sensor",
        "device_class=motion;domain=binary_sensor",
      ),
    )
  }

  @Test
  fun aHintThatFitsNoShapeLeavesEveryShape() {
    // window is neither door nor motion: keep the pre-hint "first of the fitting shapes" fallback.
    assertEquals(
      listOf("device_class=door;domain=binary_sensor", "device_class=motion;domain=binary_sensor"),
      narrow(
        mapOf("device_class" to "window"),
        "device_class=door;domain=binary_sensor",
        "device_class=motion;domain=binary_sensor",
      ),
    )
  }
}
