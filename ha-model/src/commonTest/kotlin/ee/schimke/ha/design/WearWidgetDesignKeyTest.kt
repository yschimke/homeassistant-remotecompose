package ee.schimke.ha.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class WearWidgetDesignKeyTest {
  private val mobile =
    DesignKey(
      cardType = "tile",
      variant = "neon",
      surface = DesignSurface.MobileApp,
      rcApiLevel = 8,
      shape = "domain=light",
      theme = DesignKey.styledTheme("console", dark = true),
    )

  @Test
  fun wearWidgetKeyKeepsTheCardVariantAndTheme() {
    val small = mobile.forWearWidget(DesignKey.WEAR_SMALL)
    assertEquals(DesignSurface.WearWidget, small.surface)
    assertEquals(DesignKey.WEAR_SMALL, small.container)
    assertEquals(mobile.copy(surface = DesignSurface.WearWidget, container = "small"), small)
    assertEquals("console", small.cardStyle)
    assertNotEquals(small, mobile.forWearWidget(DesignKey.WEAR_LARGE))
  }

  @Test
  fun mobileKeysEncodeWithoutAContainerAndOldKeysDecode() {
    val json = Json { ignoreUnknownKeys = true }
    val encoded = json.encodeToString(DesignKey.serializer(), mobile)
    assertFalse("container" in encoded, encoded)
    assertNull(json.decodeFromString(DesignKey.serializer(), encoded).container)
    val wear = mobile.forWearWidget(DesignKey.WEAR_LARGE)
    val wearJson = json.encodeToString(DesignKey.serializer(), wear)
    assertTrue("\"container\":\"large\"" in wearJson, wearJson)
    assertEquals(wear, json.decodeFromString(DesignKey.serializer(), wearJson))
  }

  @Test
  fun aChoiceByThemeAndVariantAlsoKeepsTheCardsWearDesigns() {
    val query = DesignQuery(variant = "neon", theme = mobile.theme)
    val wear = DesignEntry(mobile.forWearWidget(DesignKey.WEAR_SMALL), "00", 1, 1)
    assertTrue(query.matches(wear))
  }
}
