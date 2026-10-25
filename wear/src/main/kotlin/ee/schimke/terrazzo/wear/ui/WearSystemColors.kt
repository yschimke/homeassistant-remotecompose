package ee.schimke.terrazzo.wear.ui

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/** Watch system color changes also matter while the mobile connection is unavailable. */
internal fun observeWearSystemColors(context: Context, onChange: () -> Unit): () -> Unit {
  val app = context.applicationContext
  val observer =
    object : ContentObserver(Handler(Looper.getMainLooper())) {
      override fun onChange(selfChange: Boolean) = onChange()
    }
  val callbacks =
    object : ComponentCallbacks {
      override fun onConfigurationChanged(newConfig: Configuration) = onChange()

      @Suppress("OVERRIDE_DEPRECATION") override fun onLowMemory() = Unit
    }
  app.contentResolver.registerContentObserver(
    Settings.Global.getUriFor("dynamic_color_theme_enabled"),
    false,
    observer,
  )
  app.registerComponentCallbacks(callbacks)
  return {
    app.contentResolver.unregisterContentObserver(observer)
    app.unregisterComponentCallbacks(callbacks)
  }
}
