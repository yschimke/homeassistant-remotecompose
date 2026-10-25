package ee.schimke.terrazzo.dashboard

import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ThemeStyle
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A new install draws its built-in card styles before any design server has answered: the cache is
 * seeded from the designs the app ships (assets/card-designs/, generated at build time).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BuiltInCardDesignsTest {
  private val context = RuntimeEnvironment.getApplication()

  private fun loaded(choice: CardDesignChoice) = runBlocking { loadCardDesigns(context, choice) }

  @Test
  fun aNewInstallDrawsClassicAndHomeAssistantWithTheServerDown() {
    // Nothing answers here: the sync never runs in a unit test, so only the bundle can fill it.
    val server = "https://unreachable.invalid/designs/v1"
    val classic = loaded(CardDesignChoice(server, ThemeStyle.Material3, HaUberTheme.Classic))
    val themes = classic.byKey.keys.map(DesignKey::theme).toSet()
    assertEquals(
      setOf(
        DesignKey.bakedTheme("Material3", dark = false),
        DesignKey.bakedTheme("Material3", dark = true),
        DesignKey.styledTheme("home-assistant", dark = false),
        DesignKey.styledTheme("home-assistant", dark = true),
      ),
      themes,
    )
    // Every card type the library covers, in each of those themes.
    assertTrue(classic.byKey.size > 1000, "${classic.byKey.size} designs")
    assertTrue(classic.byKey.keys.any { it.cardType == "tile" })
    assertTrue(File(CardDesignSync.storeDir(context, server), "manifest.json").isFile)
  }

  @Test
  fun withNoServerTheBuiltInStylesStillDraw() {
    val none = loaded(CardDesignChoice("", ThemeStyle.Material3, HaUberTheme.HomeAssistant))
    assertTrue(
      none.byKey.keys.any { it.theme == DesignKey.styledTheme("home-assistant", dark = false) }
    )
  }
}
