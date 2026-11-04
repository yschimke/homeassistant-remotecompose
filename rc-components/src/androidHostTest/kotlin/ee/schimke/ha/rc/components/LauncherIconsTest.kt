package ee.schimke.ha.rc.components

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LauncherIconsTest {
  private val materialStyles =
    setOf(HaUberTheme.Classic.id, HaUberTheme.HomeAssistant.id, HaUberTheme.Expressive.id)

  @Test
  fun materialStylesKeepTheDefaultThemedIcon() {
    materialStyles.forEach { assertEquals(LauncherIcons.DEFAULT, LauncherIcons.keyFor(it)) }
  }

  @Test
  fun everyOtherDesignHasItsOwnIcon() {
    // Theme kits keep the default icon until one is drawn for them.
    HaUberTheme.kits.forEach { assertEquals(LauncherIcons.DEFAULT, LauncherIcons.keyFor(it.id)) }
    val own = HaUberTheme.bespoke.map { it.id }.filter { it !in materialStyles }
    own.forEach { assertEquals(it, LauncherIcons.keyFor(it)) }
    assertEquals(own.toSet(), LauncherIcons.designIcons)
    LauncherIcons.designIcons.forEach { key ->
      assertTrue(
        File("src/androidMain/res/mipmap-anydpi-v26/ic_launcher_$key.xml").exists(),
        "no icon for $key",
      )
    }
  }

  @Test
  fun everyLauncherHasAnAliasPerIcon() {
    for (manifest in
      listOf("../app/src/main/AndroidManifest.xml", "../wear/src/main/AndroidManifest.xml")) {
      val text = File(manifest).readText()
      (LauncherIcons.designIcons + LauncherIcons.DEFAULT).forEach { key ->
        assertTrue(".launcher.$key\"" in text, "$manifest has no activity-alias for $key")
      }
    }
  }
}
