package ee.schimke.terrazzo.dashboard

import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignSurface
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ThemeStyle
import kotlin.test.Test
import kotlin.test.assertEquals

/** Where cards got their designs. */
class CardDesignSourcesTest {
  private val key =
    DesignKey(
      cardType = "tile",
      surface = DesignSurface.MobileApp,
      rcApiLevel = 8,
      shape = "domain=light",
      theme = DesignKey.bakedTheme("Material3", dark = false),
    )

  @Test
  fun sourcesCountServerDesignsAndListTheGapsForOneChoice() {
    val choice = CardDesignChoice("https://designs.test", ThemeStyle.Material3, HaUberTheme.Classic)
    val gauge = key.copy(cardType = "gauge", shape = "domain=sensor")
    CardDesignSources.record(choice, key, fromServer = true)
    CardDesignSources.record(choice, gauge, fromServer = false)
    assertEquals(CardDesignSources.Summary(1, listOf(gauge)), CardDesignSources.summary(choice))

    // Another server (or variant, or author) starts afresh, and the old record no longer applies.
    val other = choice.copy(serverUrl = "https://other.test")
    assertEquals(CardDesignSources.Summary(0, emptyList()), CardDesignSources.summary(other))
    CardDesignSources.record(other, key, fromServer = true)
    assertEquals(CardDesignSources.Summary(1, emptyList()), CardDesignSources.summary(other))
    assertEquals(CardDesignSources.Summary(0, emptyList()), CardDesignSources.summary(choice))
  }
}
