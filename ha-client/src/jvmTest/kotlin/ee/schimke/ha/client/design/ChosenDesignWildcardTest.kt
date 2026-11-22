package ee.schimke.ha.client.design

import ee.schimke.ha.design.DesignEntry
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignSurface
import ee.schimke.ha.design.DeviceInfo
import ee.schimke.ha.design.DeviceMatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A design chosen by id draws a card whose shape its key accepts: a device pack published with
 * `state_icon=*` keeps drawing a garage door as it opens, closes and moves.
 */
class ChosenDesignWildcardTest {
  private fun key(icon: String, variant: String = DesignKey.DEFAULT_VARIANT) =
    DesignKey(
      cardType = "tile",
      variant = variant,
      surface = DesignSurface.MobileApp,
      rcApiLevel = 8,
      shape = "device_class=garage;domain=cover;state_icon=$icon",
      theme = "baked:Material3:light",
    )

  private val garage = DeviceMatch(deviceClass = "garage")
  private val door = DeviceInfo(deviceClass = "garage")
  private val pack = key("*", variant = "pack-garage")

  @Test
  fun findDrawsTheChosenPackInEveryDoorState() {
    val byId =
      DesignsById(mapOf("pack:garage" to listOf(DesignedBlob(pack, garage, byteArrayOf(1)))))
    for (icon in listOf("garage", "garage-open", "arrow-up-box", "arrow-down-box")) {
      val found = assertNotNull(byId.find("pack:garage", key(icon), door), icon)
      assertEquals(pack, found.first)
    }
  }

  @Test
  fun thePickerOffersThePackInEveryDoorState() {
    val entry =
      DesignEntry(
        key = pack,
        sha256 = "0".repeat(64),
        bytes = 1,
        slots = 1,
        device = garage,
        designId = "pack:garage",
        title = "Garage door · Wall console",
      )
    for (icon in listOf("garage", "garage-open", "arrow-up-box")) {
      assertEquals(
        listOf("pack:garage"),
        designOptions(listOf(entry), key(icon), door).map { it.designId },
      )
    }
  }
}
