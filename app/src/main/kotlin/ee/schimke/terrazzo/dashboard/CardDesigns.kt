package ee.schimke.terrazzo.dashboard

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import ee.schimke.ha.client.design.DesignServerConfig
import ee.schimke.ha.client.design.DesignStore
import ee.schimke.ha.client.design.RefreshResult
import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.client.design.StoredManifest
import ee.schimke.ha.client.design.createDesignRepository
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.CardTemplatePlayback
import ee.schimke.ha.rc.components.ThemeStyle
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Card design templates the dashboard can play instead of capturing locally, keyed by [DesignKey].
 * Empty — the default, and always the case while no design server URL is configured — means every
 * card renders through the local converter exactly as before.
 */
val LocalCardDesigns = staticCompositionLocalOf<Map<DesignKey, ByteArray>> { emptyMap() }

/**
 * Loads the designs for [serverUrl]. With an empty URL this returns an empty map and does no work
 * at all. Otherwise it serves whatever is cached on disk straight away, then refreshes from the
 * server (respecting the manifest TTL) and swaps in the result.
 */
@Composable
fun rememberCardDesigns(context: Context, serverUrl: String): Map<DesignKey, ByteArray> {
  if (serverUrl.isBlank()) return emptyMap()
  val repository =
    remember(serverUrl) {
      createDesignRepository(
        config = DesignServerConfig(baseUrl = serverUrl, allowUnsigned = true),
        // One directory per server: switching URLs must not show the old server's designs or
        // hold the new one to the old server's manifest serial.
        store =
          FileDesignStore(
            File(
              context.filesDir,
              "terrazzo/designs/${Sha256.hex(serverUrl.trimEnd('/').encodeToByteArray())}",
            )
          ),
        clock = System::currentTimeMillis,
      )
    }
  var designs by remember(serverUrl) { mutableStateOf<Map<DesignKey, ByteArray>>(emptyMap()) }
  LaunchedEffect(repository) {
    suspend fun load() =
      CardDesignTemplates.spikeKeys()
        .mapNotNull { (key, _) -> repository.resolve(key)?.let { key to it.bytes } }
        .toMap()
    designs = load()
    // Re-check periodically while the dashboard is up: `refresh` is a no-op until the manifest's
    // TTL or the failure back-off has passed, so this costs nothing in between.
    while (true) {
      if (repository.refresh() == RefreshResult.Updated) designs = load()
      delay(REFRESH_CHECK_INTERVAL_MS)
    }
  }
  return designs
}

/** Template playback for [card] from [designs], or null to capture it locally. */
fun cardTemplateFor(
  card: CardConfig,
  designs: Map<DesignKey, ByteArray>,
  style: ThemeStyle,
  dark: Boolean,
): CardTemplatePlayback? {
  if (designs.isEmpty()) return null
  val key = CardDesignTemplates.keyFor(card, style, dark) ?: return null
  val bytes = designs[key] ?: return null
  return CardDesignTemplates.playback(card, bytes)
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
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, "${target.name}.tmp")
    tmp.writeBytes(bytes)
    if (!tmp.renameTo(target)) {
      target.delete()
      tmp.renameTo(target)
    }
  }
}

private const val REFRESH_CHECK_INTERVAL_MS: Long = 15 * 60 * 1000
