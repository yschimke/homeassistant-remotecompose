package ee.schimke.terrazzo.shared

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun rememberCatalogCache(): CatalogCache {
  val context = LocalContext.current.applicationContext
  return remember(context) {
    val preferences = context.getSharedPreferences("terrazzo-catalog-v1", 0)
    object : CatalogCache {
      override fun read(key: String): String? = preferences.getString(key, null)

      override fun write(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
      }
    }
  }
}
