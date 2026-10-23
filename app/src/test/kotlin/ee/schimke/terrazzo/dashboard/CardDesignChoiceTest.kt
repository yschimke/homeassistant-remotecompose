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

  @Test
  fun aCardSwitchedToADesignDownloadsThatDesignWholeInItsThemes() {
    val card =
      ee.schimke.ha.model.CardConfig(
        type = "tile",
        raw = kotlinx.serialization.json.buildJsonObject { put("type", JsonPrimitive("tile")) },
      )
    val choice =
      CardDesignChoice(
        "https://designs.test",
        ThemeStyle.Material3,
        HaUberTheme.Classic,
        overrides =
          CardDesignOverrides()
            .with(CardDesignScope.Card, card, CardDesignOverride(designId = "user:printer")),
      )
    val theme = DesignKey.bakedTheme("Material3", dark = false)
    // Any variant or author of the chosen design, though neither is in the choice.
    assertTrue(
      choice.wants(entry(theme, "printer", "github:octocat").copy(designId = "user:printer"))
    )
    assertFalse(choice.wants(entry(theme, "printer", "github:octocat").copy(designId = "user:x")))
    // Only in the themes the dashboard draws.
    assertFalse(
      choice.wants(
        entry(DesignKey.styledTheme("lcd", dark = false), "printer", "github:octocat")
          .copy(designId = "user:printer")
      )
    )
    assertEquals(setOf("user:printer"), choice.designIds)
  }

  private val key = entry(DesignKey.bakedTheme("Material3", dark = false)).key
  private val printer = ee.schimke.ha.design.DeviceInfo(integration = "bambu_lab", model = "X1C")
  private val generic = byteArrayOf(1)
  private val forPrinter = byteArrayOf(2)
  private val fork = byteArrayOf(3)
  private val forkForPrinter = byteArrayOf(4)
  private val designs =
    CardDesigns(
      byKey = mapOf(key to generic),
      deviceByKey =
        mapOf(
          key to listOf(ee.schimke.ha.design.DeviceMatch(integration = "bambu_lab") to forPrinter)
        ),
      byDesign =
        ee.schimke.ha.client.design.DesignsById(
          mapOf(
            "user:fork" to
              listOf(
                ee.schimke.ha.client.design.DesignedBlob(key.copy(variant = "mine"), null, fork),
                ee.schimke.ha.client.design.DesignedBlob(
                  key.copy(variant = "mine"),
                  ee.schimke.ha.design.DeviceMatch(model = "X1*"),
                  forkForPrinter,
                ),
              )
          )
        ),
    )

  @Test
  fun aChosenDesignWinsOverTheDeviceAndGenericDesigns() {
    val (bytes, used) = serverDesignFor(key, designs, "default", printer, designId = "user:fork")
    assertEquals(forkForPrinter.toList(), bytes?.toList())
    assertEquals("mine", used.variant)
    // Its generic entry for a card on another device.
    assertEquals(
      fork.toList(),
      serverDesignFor(key, designs, "default", null, designId = "user:fork").first?.toList(),
    )
  }

  @Test
  fun withoutAChoiceOrWithAMissingOneTheOrderIsUnchanged() {
    assertEquals(
      forPrinter.toList(),
      serverDesignFor(key, designs, "default", printer).first?.toList(),
    )
    assertEquals(generic.toList(), serverDesignFor(key, designs, "default", null).first?.toList())
    // A chosen design that is gone (revoked, not fetched) or lacks the key falls through.
    assertEquals(
      forPrinter.toList(),
      serverDesignFor(key, designs, "default", printer, designId = "user:gone").first?.toList(),
    )
    val otherKey = key.copy(shape = "domain=switch")
    assertEquals(
      null to otherKey,
      serverDesignFor(otherKey, designs, "default", printer, designId = "user:fork"),
    )
  }
}
