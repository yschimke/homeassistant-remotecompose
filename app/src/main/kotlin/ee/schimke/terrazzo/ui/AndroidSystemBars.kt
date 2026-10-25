package ee.schimke.terrazzo.ui

import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat

/**
 * Light or dark system-bar icons to match the resolved app theme, including overrides.
 *
 * No `enableEdgeToEdge()`: minSdk is 35, where the platform enforces edge-to-edge with transparent
 * bars and its own contrast protection for three-button navigation. AndroidX's helper would only
 * add its pre-35 code paths, whose deprecated `Window.setStatusBarColor` / `setNavigationBarColor`
 * calls Play's pre-launch report flags.
 */
internal fun ComponentActivity.updateSystemBars(darkTheme: Boolean) {
  WindowCompat.getInsetsController(window, window.decorView).apply {
    isAppearanceLightStatusBars = !darkTheme
    isAppearanceLightNavigationBars = !darkTheme
  }
}
