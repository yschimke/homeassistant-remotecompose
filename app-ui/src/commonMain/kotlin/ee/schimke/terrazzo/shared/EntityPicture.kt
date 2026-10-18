package ee.schimke.terrazzo.shared

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.HaConfig
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.jetbrains.compose.resources.decodeToImageBitmap

internal data class EntityPictureKey(val path: String, val revision: String)

/** Authenticated HA pictures stay local; no credentials or image URLs go to the design server. */
internal class EntityPictureClient(
  private val config: HaConfig,
  private val tokenProvider: (suspend () -> String)? = null,
  engine: HttpClientEngine? = null,
) {
  private val http =
    if (engine == null) HttpClient { followRedirects = false }
    else HttpClient(engine) { followRedirects = false }

  suspend fun load(key: EntityPictureKey): ByteArray {
    val target = entityPictureUrl(config.baseUrl, key)
    val response = http.get(target) { bearerAuth(tokenProvider?.invoke() ?: config.accessToken) }
    check(response.status.isSuccess())
    return response.bodyAsBytes()
  }

  fun close() = http.close()
}

internal fun entityPictureUrl(base: String, key: EntityPictureKey): Url {
  val origin = Url(base)
  require(origin.protocol in listOf(URLProtocol.HTTP, URLProtocol.HTTPS))
  require(!key.path.startsWith("//"))
  val url =
    when {
      key.path.startsWith("/") ->
        URLBuilder(origin)
          .apply {
            encodedPath = key.path.substringBefore('?')
            encodedParameters.clear()
            key.path
              .substringAfter('?', "")
              .takeIf { it.isNotEmpty() }
              ?.let { encodedParameters.appendAll(parseQueryString(it, decode = false)) }
          }
          .build()
      else -> Url(key.path)
    }
  require(url.protocol == origin.protocol && url.host == origin.host && url.port == origin.port)
  require(url.user == null && url.password == null)
  return URLBuilder(url).apply { parameters.append("terrazzo_frame", key.revision) }.build()
}

internal fun entityPictureKey(entity: EntityState?): EntityPictureKey? {
  if (entity == null || entity.state in setOf("unknown", "unavailable")) return null
  val picture =
    (entity.attributes["entity_picture"] as? JsonPrimitive)?.contentOrNull?.takeIf {
      it.isNotBlank()
    } ?: return null
  // An image entity's state is its image timestamp. A fresh state or token must fetch a fresh
  // frame.
  return EntityPictureKey(picture, "${entity.state}|${entity.lastUpdated ?: ""}")
}

internal fun isEntityPictureCard(card: CardConfig): Boolean {
  val id = (card.raw["entity"] as? JsonPrimitive)?.contentOrNull ?: return false
  return card.type == "picture-entity" ||
    (id.substringBefore('.') in setOf("image", "camera") &&
      card.type in setOf("entity", "tile", "sensor"))
}

@Composable
internal fun EntityPictureCard(
  card: CardConfig,
  entity: EntityState?,
  cache: DashboardLoadCache<EntityPictureKey, ImageBitmap>,
  onMoreInfo: (String) -> Unit,
) {
  val id = (card.raw["entity"] as? JsonPrimitive)?.contentOrNull
  val name =
    (card.raw["name"] as? JsonPrimitive)?.contentOrNull
      ?: (entity?.attributes?.get("friendly_name") as? JsonPrimitive)?.contentOrNull
      ?: id
      ?: "Image"
  val key = entityPictureKey(entity)
  var image by remember(key) { mutableStateOf(key?.let { cache.peek(it) }) }
  var failed by remember(key) { mutableStateOf(false) }
  LaunchedEffect(key, cache) {
    if (key != null)
      try {
        image = cache.get(key)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Throwable) {
        failed = true
      }
  }
  val tap =
    ((card.raw["tap_action"] as? JsonObject)?.get("action") as? JsonPrimitive)?.contentOrNull
  val tapModifier =
    if (id != null && tap in setOf(null, "more-info"))
      Modifier.clickable(role = Role.Button) { onMoreInfo(id) }
    else Modifier
  OutlinedCard(Modifier.fillMaxWidth().entityDetails(id, onMoreInfo).then(tapModifier)) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if ((card.raw["show_name"] as? JsonPrimitive)?.booleanOrNull != false)
        Text(name, style = MaterialTheme.typography.titleSmall)
      Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f), contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null)
          Image(bitmap, name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else
          Text(
            if (key == null || failed) "Preview unavailable" else "Loading preview…",
            style = MaterialTheme.typography.bodyMedium,
          )
      }
    }
  }
}

internal fun decodeEntityPicture(bytes: ByteArray): ImageBitmap = bytes.decodeToImageBitmap()
