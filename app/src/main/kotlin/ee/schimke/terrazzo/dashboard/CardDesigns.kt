package ee.schimke.terrazzo.dashboard

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import ee.schimke.ha.client.design.DesignStore
import ee.schimke.ha.client.design.StoredManifest
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardDesignLibrary
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.CardRegistry
import ee.schimke.ha.rc.CardTemplatePlayback
import ee.schimke.ha.rc.components.ThemeStyle
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Designs from the card design server, cached on disk by [CardDesignSync], keyed by [DesignKey].
 * Empty while no server is configured. These only override: every templatable card renders from a
 * design either way, falling back to the local [CardDesignLibrary] for keys the server doesn't
 * cover. See [cardTemplateFor].
 */
val LocalCardDesigns = staticCompositionLocalOf<Map<DesignKey, ByteArray>> { emptyMap() }

/**
 * The server designs cached on disk for [serverUrl]. With an empty URL this returns an empty map
 * and does no work. It never touches the network: [CardDesignSync]'s nightly job fills the cache,
 * and a newly synced design shows the next time the dashboard is composed.
 */
@Composable
fun rememberCardDesigns(context: Context, serverUrl: String): Map<DesignKey, ByteArray> {
  if (serverUrl.isBlank()) return emptyMap()
  val repository = remember(serverUrl) { CardDesignSync.cachedRepository(context, serverUrl) }
  var designs by remember(serverUrl) { mutableStateOf<Map<DesignKey, ByteArray>>(emptyMap()) }
  LaunchedEffect(repository) { designs = repository.resolveAll() }
  return designs
}

/**
 * The design [card] renders with: the server's design for its key when one is cached, otherwise the
 * local library's. Null only when the card has no template shape (an unsupported type or config, or
 * an entity not loaded yet); those cards capture through their converter.
 */
fun cardTemplateFor(
  context: Context,
  card: CardConfig,
  snapshot: HaSnapshot,
  serverDesigns: Map<DesignKey, ByteArray>,
  style: ThemeStyle,
  dark: Boolean,
  registry: CardRegistry,
): CardTemplatePlayback? {
  val key = CardDesignTemplates.keyFor(card, snapshot, style, dark, registry) ?: return null
  val bytes = serverDesigns[key] ?: CardDesignLibrary.template(context, key, registry)
  return CardDesignTemplates.playback(card, bytes, registry)
}

/**
 * On-disk [DesignStore]: `manifest.json` plus content-addressed `blobs/<sha256>.rc`. Not per HA
 * instance (designs hold no user data), so sign-out leaves it alone. Writes go to a temp file and
 * are renamed into place.
 */
class FileDesignStore(private val dir: File) : DesignStore {
  private val json = Json { ignoreUnknownKeys = true }

  override suspend fun readManifest(): StoredManifest? =
    withContext(Dispatchers.IO) {
      val file = File(dir, "manifest.json")
      if (!file.isFile) return@withContext null
      runCatching { json.decodeFromString(StoredManifest.serializer(), file.readText()) }
        .getOrNull()
    }

  override suspend fun writeManifest(manifest: StoredManifest) =
    withContext(Dispatchers.IO) {
      write(
        File(dir, "manifest.json"),
        json.encodeToString(StoredManifest.serializer(), manifest).encodeToByteArray(),
      )
    }

  override suspend fun readBlob(sha256: String): ByteArray? =
    withContext(Dispatchers.IO) { blob(sha256).takeIf { it.isFile }?.readBytes() }

  override suspend fun writeBlob(sha256: String, bytes: ByteArray) =
    withContext(Dispatchers.IO) { write(blob(sha256), bytes) }

  private fun blob(sha256: String): File {
    require(sha256.all { it in '0'..'9' || it in 'a'..'f' }) { "not a sha256: $sha256" }
    return File(dir, "blobs/$sha256.rc")
  }

  private fun write(target: File, bytes: ByteArray) {
    val dir = requireNotNull(target.parentFile).apply { mkdirs() }
    // A unique temp file per write, so two writers can never clobber each other's partial file.
    val tmp = File.createTempFile("${target.name}.", ".tmp", dir)
    try {
      tmp.writeBytes(bytes)
      java.nio.file.Files.move(
        tmp.toPath(),
        target.toPath(),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
      )
    } finally {
      tmp.delete()
    }
  }
}
