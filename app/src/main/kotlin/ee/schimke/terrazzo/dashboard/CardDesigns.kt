package ee.schimke.terrazzo.dashboard

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import ee.schimke.ha.client.design.DesignStore
import ee.schimke.ha.client.design.StoredManifest
import ee.schimke.ha.design.DesignEntry
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignQuery
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardDesignLibrary
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.CardRegistry
import ee.schimke.ha.rc.CardTemplatePlayback
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ThemeStyle
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Which card designs this app wants from the design server: those for the user's palette and card
 * style, in either light or dark, of their preferred variant (and `default` to fall back on), by
 * their preferred author (and the built-in library's). The sync downloads what [queries] match out
 * of the whole manifest; the dashboard prefers designs in [prefer]'s order.
 */
data class CardDesignChoice(
  val serverUrl: String,
  val style: ThemeStyle,
  val cardStyle: HaUberTheme,
  val variant: String = DesignKey.DEFAULT_VARIANT,
  /** Preferred author, or blank for the built-in designs only. */
  val author: String = "",
) {
  val enabled: Boolean
    get() = serverUrl.isNotBlank()

  private val authors: List<String>
    get() = listOf(author.trim(), DesignEntry.BUILTIN_AUTHOR).filter { it.isNotEmpty() }.distinct()

  /** The designs to download: every theme this palette and style draw in, by variant and author. */
  val queries: List<DesignQuery>
    get() {
      val themes =
        listOf(false, true).map { CardDesignTemplates.bakedTheme(style, it, cardStyle) }.distinct()
      val variants = listOf(variant, DesignKey.DEFAULT_VARIANT).distinct()
      return themes.flatMap { theme ->
        variants.flatMap { v ->
          authors.map { DesignQuery(variant = v, author = it, theme = theme) }
        }
      }
    }

  /** Between designs for one key, the preferred author's first. */
  val prefer: List<DesignQuery>
    get() = authors.map { DesignQuery(author = it) }
}

/**
 * Designs from the card design server, cached on disk by [CardDesignSync], keyed by [DesignKey],
 * and the variant to prefer. Empty while no server is configured. Server designs override: every
 * templatable card renders from a design either way, falling back to the local [CardDesignLibrary]
 * for keys the server doesn't cover. See [cardTemplateFor].
 */
data class CardDesigns(
  val byKey: Map<DesignKey, ByteArray> = emptyMap(),
  val variant: String = DesignKey.DEFAULT_VARIANT,
)

val LocalCardDesigns = staticCompositionLocalOf { CardDesigns() }

/**
 * The server designs cached on disk for [choice]. With no server this is empty and does no work. It
 * never touches the network: [CardDesignSync] fills the cache, and when a sync stores new designs
 * ([CardDesignSync.revision]) they are read again, so they show without restarting the app.
 */
@Composable
fun rememberCardDesigns(context: Context, choice: CardDesignChoice): CardDesigns {
  if (!choice.enabled) return CardDesigns(variant = choice.variant)
  val revision by CardDesignSync.revision.collectAsState()
  val repository =
    remember(choice.serverUrl) { CardDesignSync.cachedRepository(context, choice.serverUrl) }
  var designs by
    remember(repository, choice.prefer) { mutableStateOf<Map<DesignKey, ByteArray>>(emptyMap()) }
  LaunchedEffect(repository, choice.prefer, revision) {
    designs = repository.resolveAll(prefer = choice.prefer)
  }
  return CardDesigns(designs, choice.variant)
}

/**
 * The design [card] renders with: the server's design for its key in the preferred variant, then in
 * the `default` variant, otherwise the local library's. Null only when the card has no template
 * shape (an unsupported type or config, or an entity not loaded yet); those cards capture through
 * their converter.
 */
fun cardTemplateFor(
  context: Context,
  card: CardConfig,
  snapshot: HaSnapshot,
  designs: CardDesigns,
  style: ThemeStyle,
  dark: Boolean,
  registry: CardRegistry,
  cardStyle: HaUberTheme = HaUberTheme.Classic,
): CardTemplatePlayback? {
  val key =
    CardDesignTemplates.keyFor(card, snapshot, style, dark, registry, cardStyle = cardStyle)
      ?: return null
  val bytes =
    designs.byKey[key.copy(variant = designs.variant)]
      ?: designs.byKey[key]
      ?: CardDesignLibrary.template(context, key, registry)
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
