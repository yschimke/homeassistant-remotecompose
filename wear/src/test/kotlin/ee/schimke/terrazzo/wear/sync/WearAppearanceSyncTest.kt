package ee.schimke.terrazzo.wear.sync

import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.terrazzo.wear.ui.wearCardLook
import ee.schimke.terrazzo.wearsync.proto.WearSettings
import ee.schimke.terrazzo.wearsync.proto.WearSyncPaths
import ee.schimke.terrazzo.wearsync.proto.decodeProto
import ee.schimke.terrazzo.wearsync.proto.encodeProto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WearAppearanceSyncTest {
  private val context = RuntimeEnvironment.getApplication()

  private fun receive(settings: WearSettings) {
    WearOfflineStore(context).persistChanged(WearSyncPaths.SETTINGS, encodeProto(settings))
  }

  @Test
  fun currentSettingsChangesOfflineRestartAndReconnectUsePhoneAppearance() {
    val first = WearSettings(themeStyle = "TerrazzoMushroom", cardStyle = "lcd", updatedAtMs = 100)
    receive(first)
    assertEquals(first, WearOfflineStore(context).readSettings())
    // A new store models a cold process, with no phone connected and no event to wait for.
    assertEquals(
      ThemeStyle.TerrazzoMushroom,
      WearOfflineStore(context).readSettings().wearCardLook().palette,
    )
    assertEquals("lcd", WearOfflineStore(context).readSettings().wearCardLook().style)
    val changed = first.copy(themeStyle = "TerrazzoKiosk", cardStyle = "braun", updatedAtMs = 300)
    receive(changed)
    // A replaced phone / old cached item must not roll back the latest mobile settings.
    receive(first.copy(updatedAtMs = 200))
    assertEquals(changed, WearOfflineStore(context).readSettings())
    assertEquals("braun", WearOfflineStore(context).readSettings().wearCardLook().style)
    val reconnected = changed.copy(cardStyle = "console", updatedAtMs = 400)
    receive(reconnected)
    assertEquals(reconnected, WearOfflineStore(context).readSettings())
    assertTrue(redrawsOnChange(listOf(WearSyncPaths.SETTINGS)))
    assertTrue(redrawsOnChange(listOf(WearSyncPaths.designPath("a"))))
  }

  @Test
  fun oldPhonesAndUnknownFutureSettingsUseMobileDefaults() {
    val legacy = decodeProto<WearSettings>(encodeProto(WearSettings()))!!
    assertEquals(ThemeStyle.Material3, legacy.wearCardLook().palette)
    assertEquals("classic", legacy.wearCardLook().style)
    assertEquals(ThemeStyle.Material3, WearSettings(themeStyle = "future").wearCardLook().palette)
  }

  @Test
  fun systemResolvesOnTheWatchWhileExplicitModesOverrideItAndSurviveRestart() {
    val system = WearSettings(darkMode = "Follow", updatedAtMs = 100)
    receive(system)
    val restarted = WearOfflineStore(context).readSettings()!!
    assertEquals(false, restarted.wearCardLook(systemDark = false).dark)
    assertEquals(true, restarted.wearCardLook(systemDark = true).dark)
    // No new phone payload is needed when the watch's own system appearance changes offline.
    assertEquals(false, WearOfflineStore(context).readSettings().wearCardLook(false).dark)
    for (watchDark in listOf(false, true)) {
      assertEquals(false, system.copy(darkMode = "Light").wearCardLook(watchDark).dark)
      assertEquals(true, system.copy(darkMode = "Dark").wearCardLook(watchDark).dark)
    }
    receive(system.copy(darkMode = "Light", updatedAtMs = 200))
    receive(system.copy(darkMode = "Dark", updatedAtMs = 300))
    // Reconnecting to an older cached System item cannot undo the explicit current mode.
    receive(system.copy(updatedAtMs = 250))
    assertEquals(true, WearOfflineStore(context).readSettings().wearCardLook(false).dark)
  }
}
