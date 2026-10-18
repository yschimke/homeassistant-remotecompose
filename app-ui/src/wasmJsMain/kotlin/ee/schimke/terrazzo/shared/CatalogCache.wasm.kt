@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.terrazzo.shared

import androidx.compose.runtime.*

@JsFun(
  "(key) => { try { return localStorage.getItem('terrazzo-catalog-v1:' + key); } catch (_) { return null; } }"
)
private external fun readCatalogCache(key: String): String?

@JsFun(
  "(key, value) => { try { localStorage.setItem('terrazzo-catalog-v1:' + key, value); } catch (_) {} }"
)
private external fun writeCatalogCache(key: String, value: String)

@Composable
internal actual fun rememberCatalogCache(): CatalogCache = remember {
  object : CatalogCache {
    override fun read(key: String) = readCatalogCache(key)

    override fun write(key: String, value: String) = writeCatalogCache(key, value)
  }
}
