package ee.schimke.ha.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesignQueryTest {
  private fun entry(
    cardType: String = "tile",
    theme: String = DesignKey.bakedTheme("Material3", dark = false),
    variant: String = DesignKey.DEFAULT_VARIANT,
    author: String = DesignEntry.BUILTIN_AUTHOR,
  ) =
    DesignEntry(
      key =
        DesignKey(
          cardType = cardType,
          variant = variant,
          surface = DesignSurface.MobileApp,
          rcApiLevel = 8,
          shape = "domain=light",
          theme = theme,
        ),
      sha256 = "00",
      bytes = 1,
      slots = 1,
      author = author,
    )

  @Test
  fun cardStyleComesFromTheTheme() {
    assertEquals(DesignKey.CLASSIC_STYLE, entry().key.cardStyle)
    assertEquals("console", entry(theme = DesignKey.styledTheme("console", true)).key.cardStyle)
    // Before card styles had their own theme form, the id rode on a baked theme.
    assertEquals("lcd", entry(theme = "baked:Material3:dark:lcd").key.cardStyle)
  }

  @Test
  fun tagsMatchWithoutCaseAndUnsetMatchesAnything() {
    val console =
      entry(cardType = "gauge", theme = DesignKey.styledTheme("console", true), author = "github:a")
    assertTrue(DesignQuery().matches(console))
    assertTrue(DesignQuery(cardType = "GAUGE", style = "Console").matches(console))
    assertTrue(DesignQuery(author = "github:a", variant = "default").matches(console))
    assertFalse(DesignQuery(style = DesignKey.CLASSIC_STYLE).matches(console))
    assertFalse(DesignQuery(author = DesignEntry.BUILTIN_AUTHOR).matches(console))
    assertFalse(DesignQuery(variant = "modern").matches(console))
  }

  @Test
  fun parametersRoundTrip() {
    val query =
      DesignQuery(
        cardType = "tile",
        style = "lcd",
        author = "github:a",
        surface = DesignSurface.MobileApp,
      )
    val parameters = query.parameters().toMap()
    assertEquals(query, DesignQuery.of(parameters::get))
    assertEquals(DesignQuery(), DesignQuery.of { " " })
    assertNull(DesignQuery.of { if (it == DesignQuery.SURFACE) "fridge" else null })
  }

  @Test
  fun tagsCountTheManifest() {
    val tags =
      DesignTags.of(
        listOf(
          entry(),
          entry(cardType = "gauge"),
          entry(theme = DesignKey.styledTheme("console", true), author = "github:a"),
        )
      )
    assertEquals(mapOf("gauge" to 1, "tile" to 2), tags.cardType)
    assertEquals(mapOf("classic" to 2, "console" to 1), tags.style)
    assertEquals(mapOf("default" to 3), tags.variant)
    assertEquals(mapOf("github:a" to 1, "terrazzo" to 2), tags.author)
  }

  @Test
  fun aDeviceQueryFindsOnlyDesignsMadeForThatDevice() {
    val generic = entry()
    val forBoiler = entry().copy(device = DeviceMatch(integration = "vaillant", model = "ecoTEC*"))
    val boiler = DesignQuery(device = DeviceInfo(integration = "vaillant", model = "ecoTEC plus"))
    assertTrue(boiler.matches(forBoiler))
    assertFalse(boiler.matches(generic))
    assertFalse(DesignQuery(device = DeviceInfo(integration = "nibe")).matches(forBoiler))
    // Without a device, a query keeps matching both.
    assertTrue(DesignQuery().matches(forBoiler))
    val parsed = DesignQuery.of(mapOf("integration" to "vaillant", "model" to "ecoTEC plus")::get)
    assertEquals(DeviceInfo(integration = "vaillant", model = "ecoTEC plus"), parsed?.device)
    assertEquals(
      listOf("integration" to "vaillant", "model" to "ecoTEC plus"),
      parsed?.parameters(),
    )
  }
}
