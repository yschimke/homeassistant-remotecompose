package ee.schimke.terrazzo.ui

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/** Match both icons and legacy navigation scrims to the resolved app theme, including overrides. */
internal fun ComponentActivity.updateSystemBars(darkTheme: Boolean) {
  enableEdgeToEdge(
    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
    // AndroidX's default navigation scrims preserve contrast on API 26–28. On API 29+ the
    // platform supplies contrast protection for three-button navigation instead.
    navigationBarStyle =
      SystemBarStyle.auto(
        Color.argb(0xe6, 0xff, 0xff, 0xff),
        Color.argb(0x80, 0x1b, 0x1b, 0x1b),
      ) {
        darkTheme
      },
  )
}
