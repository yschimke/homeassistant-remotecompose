package ee.schimke.terrazzo

import android.content.res.Configuration
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import ee.schimke.terrazzo.core.prefs.DarkModePref
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the production Activity and preference flow, not a copy of its system-bar wiring. */
@AndroidWindowInsetsRegression
@RunWith(AndroidJUnit4::class)
class AndroidSystemBarThemeTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  @Test
  fun systemBarIconsFollowAppOverrideAndLiveThemeChanges() {
    val preferences = (compose.activity.application as TerrazzoApplication).graph.preferencesStore
    val original = runBlocking { preferences.darkMode.first() }
    try {
      for (pref in listOf(DarkModePref.Dark, DarkModePref.Light, DarkModePref.Follow)) {
        runBlocking { preferences.setDarkMode(pref) }
        compose.waitForIdle()
        // Wait for DataStore -> collectAsState -> theme recomposition before checking the
        // window.
        // The screenshot is captured even on the original buggy layout for before/after
        // evidence.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        try {
          compose.waitUntil(5_000) {
            compose.runOnIdle {
              val deviceDark =
                (compose.activity.resources.configuration.uiMode and
                  Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
              val dark =
                when (pref) {
                  DarkModePref.Follow -> deviceDark
                  DarkModePref.Dark -> true
                  DarkModePref.Light -> false
                }
              val controller =
                WindowCompat.getInsetsController(
                  compose.activity.window,
                  compose.activity.window.decorView,
                )
              controller.isAppearanceLightStatusBars == !dark &&
                controller.isAppearanceLightNavigationBars == !dark
            }
          }
        } finally {
          captureWindowEvidence("system-bars-${pref.name.lowercase()}")
        }
        compose.runOnIdle {
          val controller =
            WindowCompat.getInsetsController(
              compose.activity.window,
              compose.activity.window.decorView,
            )
          assertEquals(
            controller.isAppearanceLightStatusBars,
            controller.isAppearanceLightNavigationBars,
          )
        }
      }
    } finally {
      runBlocking { preferences.setDarkMode(original) }
    }
  }
}
