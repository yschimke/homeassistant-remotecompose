package ee.schimke.terrazzo.dashboard

import ee.schimke.ha.client.design.CardDesignOverride
import ee.schimke.ha.client.design.CardDesignOverrides
import ee.schimke.ha.client.design.CardDesignScope
import ee.schimke.ha.design.DesignEntry
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignQuery
import ee.schimke.ha.design.DesignSurface
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ThemeStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

/** What a design choice downloads from the manifest, and which author it prefers. */
class CardDesignChoiceTest {
  private fun entry(theme: String, variant: String = "default", author: String = "terrazzo") =
    DesignEntry(
      key =
        DesignKey(
          cardType = "tile",
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

  private fun CardDesignChoice.wants(entry: DesignEntry) = queries.any { it.matches(entry) }

  @Test
  fun websiteStyleDoesNotNeedAnEnumEntry() {
    val choice =
      CardDesignChoice(
        "https://designs.test",
        ThemeStyle.Material3,
        HaUberTheme.Classic,
        catalogStyleId = "brand-new",
      )
    assertTrue(choice.wants(entry(DesignKey.styledTheme("brand-new", dark = false))))
    assertTrue(choice.wants(entry(DesignKey.styledTheme("brand-new", dark = true))))
    assertFalse(choice.wants(entry(DesignKey.bakedTheme("Material3", dark = false))))
  }

  @Test
  fun classicDownloadsItsPaletteInBothModes() {
    val choice =
      CardDesignChoice("https://designs.test", ThemeStyle.TerrazzoHome, HaUberTheme.Classic)
    assertTrue(choice.wants(entry(DesignKey.bakedTheme("TerrazzoHome", dark = false))))
    assertTrue(choice.wants(entry(DesignKey.bakedTheme("TerrazzoHome", dark = true))))
    assertFalse(choice.wants(entry(DesignKey.bakedTheme("Material3", dark = false))))
    assertFalse(choice.wants(entry(DesignKey.styledTheme("console", dark = true))))
  }

  @Test
  fun aCardStyleDownloadsOnlyItsOwnModes() {
    val console =
      CardDesignChoice("https://designs.test", ThemeStyle.Material3, HaUberTheme.Console)
    assertEquals(1, console.queries.size)
    assertTrue(console.wants(entry(DesignKey.styledTheme("console", dark = true))))
    assertFalse(console.wants(entry(DesignKey.bakedTheme("Material3", dark = true))))
  }

  @Test
  fun aVariantAndAuthorKeepTheDefaultsToFallBackOn() {
    val theme = DesignKey.bakedTheme("Material3", dark = false)
    val choice =
      CardDesignChoice(
        "https://designs.test",
        ThemeStyle.Material3,
        HaUberTheme.Classic,
        variant = "modern",
        author = "github:someone",
      )
    assertTrue(choice.wants(entry(theme, variant = "modern", author = "github:someone")))
    assertTrue(choice.wants(entry(theme)))
    assertFalse(choice.wants(entry(theme, author = "github:other")))
    assertEquals(
      listOf(DesignQuery(author = "github:someone"), DesignQuery(author = "terrazzo")),
      choice.prefer,
    )
    // With no author picked, only the built-in designs.
    val builtIn = choice.copy(author = "")
    assertFalse(builtIn.wants(entry(theme, author = "github:someone")))
    assertEquals(listOf(DesignQuery(author = "terrazzo")), builtIn.prefer)
  }

  @Test
  fun overridesAlsoDownloadTheirStylesAndVariants() {
    val card =
      ee.schimke.ha.model.CardConfig(
        type = "tile",
        raw = kotlinx.serialization.json.buildJsonObject { put("type", JsonPrimitive("tile")) },
      )
    val overrides =
      CardDesignOverrides()
        .with(CardDesignScope.Card, card, CardDesignOverride(cardStyle = "console"))
        .with(CardDesignScope.CardType, card, CardDesignOverride(variant = "garage"))
    val choice =
      CardDesignChoice(
        "https://designs.test",
        ThemeStyle.Material3,
        HaUberTheme.Classic,
        overrides = overrides,
      )
    assertTrue(choice.wants(entry(DesignKey.styledTheme("console", dark = true))))
    assertTrue(choice.wants(entry(DesignKey.bakedTheme("Material3", dark = false))))
    assertTrue(choice.wants(entry(DesignKey.bakedTheme("Material3", dark = false), "garage")))
    assertFalse(choice.wants(entry(DesignKey.styledTheme("lcd", dark = false))))
  }

  @Test
  fun anEmptyServerIsOff() {
    assertFalse(CardDesignChoice("", ThemeStyle.Material3, HaUberTheme.Classic).enabled)
  }
}
