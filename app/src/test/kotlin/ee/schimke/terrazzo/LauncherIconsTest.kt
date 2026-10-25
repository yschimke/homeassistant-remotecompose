package ee.schimke.terrazzo

import ee.schimke.terrazzo.core.prefs.CardStylePref
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LauncherIconsTest {
  @Test
  fun materialStylesKeepTheDefaultThemedIcon() {
    listOf(CardStylePref.Classic, CardStylePref.HomeAssistant, CardStylePref.Expressive).forEach {
      assertEquals(LauncherIcons.DEFAULT, LauncherIcons.keyFor(it))
    }
  }

  @Test
  fun everyOtherDesignHasItsOwnIcon() {
    val own =
      CardStylePref.entries.filter {
        it !in setOf(CardStylePref.Classic, CardStylePref.HomeAssistant, CardStylePref.Expressive)
      }
    own.forEach { assertEquals(it.id, LauncherIcons.keyFor(it)) }
    assertEquals(own.map { it.id }.toSet(), LauncherIcons.designIcons)
  }

  @Test
  fun everyIconHasAManifestAliasAndAMipmap() {
    val manifest = File("src/main/AndroidManifest.xml").readText()
    (LauncherIcons.designIcons + LauncherIcons.DEFAULT).forEach { key ->
      assertTrue(".launcher.$key\"" in manifest, "no activity-alias for $key")
    }
    LauncherIcons.designIcons.forEach { key ->
      assertTrue(
        File("src/main/res/mipmap-anydpi-v26/ic_launcher_$key.xml").exists(),
        "no icon for $key",
      )
    }
  }
}
