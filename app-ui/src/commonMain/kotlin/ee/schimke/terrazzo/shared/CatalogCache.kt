package ee.schimke.terrazzo.shared

import androidx.compose.runtime.*
import ee.schimke.ha.client.design.Sha256
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

/** Public catalog data only. Keys contain hashes of anonymous card shapes, never HA credentials. */
internal interface CatalogCache {
  fun read(key: String): String?

  fun write(key: String, value: String)
}

@Composable internal expect fun rememberCatalogCache(): CatalogCache

internal fun cachedTemplate(cache: CatalogCache, key: String): ByteArray? = runCatching {
  val data = Json.parseToJsonElement(cache.read("template-$key") ?: return null).jsonObject
  val bytes = Base64.decode(data.getValue("bytes").jsonPrimitive.content)
  require(
    bytes.size <= 8 * 1024 * 1024 &&
      Sha256.hex(bytes) == data.getValue("sha256").jsonPrimitive.content
  )
  bytes
}
  .getOrNull()

internal fun cacheTemplate(cache: CatalogCache, key: String, bytes: ByteArray) {
  // Quota/storage failures must never turn a playable design into a failed network load.
  runCatching {
    cache.write(
      "template-$key",
      buildJsonObject {
        put("sha256", Sha256.hex(bytes))
        put("bytes", Base64.encode(bytes))
      }
        .toString(),
    )
  }
}

private val fallbackStyles =
  listOf(
    "classic",
    "braun",
    "lcd",
    "console",
    "ikea",
    "mushroom",
    "bubble",
    "minimalist",
    "cupertino",
    "catppuccin",
    "obsidian",
    "noctis",
    "lcars",
    "arctic",
    "aeon",
  )

/** A last-good discovery cache plus bundled styles; refresh while the application stays open. */
@Composable
internal fun rememberCatalogStyles(refresh: Boolean): List<String> {
  val cache = rememberCatalogCache()
  var styles by remember {
    mutableStateOf(
      runCatching {
          Json.parseToJsonElement(cache.read("styles") ?: "[]").jsonArray.map {
            it.jsonPrimitive.content
          }
        }
        .getOrDefault(emptyList())
        .ifEmpty { fallbackStyles }
    )
  }
  LaunchedEffect(refresh) {
    val http = HttpClient()
    try {
      while (true) {
        try {
          val response = http.get("https://ha.coo.ee/api/designs/catalog/styles")
          check(response.status.isSuccess())
          val discovered =
            Json.parseToJsonElement(response.bodyAsText()).jsonArray.map {
              it.jsonPrimitive.content
            }
          check(
            discovered.isNotEmpty() &&
              discovered.all { Regex("[a-z0-9][a-z0-9-]{0,79}").matches(it) }
          )
          styles = discovered
          runCatching { cache.write("styles", JsonArray(styles.map(::JsonPrimitive)).toString()) }
        } catch (e: CancellationException) {
          throw e
        } catch (_: Throwable) {
          /* keep last good */
        }
        delay(60_000)
      }
    } finally {
      http.close()
    }
  }
  return styles
}
