package ee.schimke.terrazzo.dashboard

import android.content.Context
import android.os.Trace
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalInspectionMode
import ee.schimke.ha.client.design.CardDesignOverrides
import ee.schimke.ha.client.design.DesignStore
import ee.schimke.ha.client.design.StoredManifest
import ee.schimke.ha.client.design.UsageSource
import ee.schimke.ha.design.DesignEntry
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignQuery
import ee.schimke.ha.design.DeviceInfo
import ee.schimke.ha.design.DeviceMatch
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardDesignLibrary
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.CardRegistry
import ee.schimke.ha.rc.CardTemplatePlayback
import ee.schimke.ha.rc.cardEntityIds
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.preloadForComposePlayer
import ee.schimke.terrazzo.core.prefs.PreferencesStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
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
  /** The user's per-card and per-card-type departures, whose designs are fetched too. */
  val overrides: CardDesignOverrides = CardDesignOverrides(),
  /**
   * The server's device token, so the manifest lists the user's private designs; blank for none.
   */
  val token: String = "",
) {
  val enabled: Boolean
    get() = serverUrl.isNotBlank()

  private val authors: List<String>
    get() = listOf(author.trim(), DesignEntry.BUILTIN_AUTHOR).filter { it.isNotEmpty() }.distinct()

  /** The designs to download: every theme this palette and style draw in, by variant and author. */
  val queries: List<DesignQuery>
    get() {
      val cardStyles = listOf(cardStyle) + overrides.cardStyles.map(HaUberTheme::byId)
      val themes =
        cardStyles
          .flatMap { s -> listOf(false, true).map { CardDesignTemplates.bakedTheme(style, it, s) } }
          .distinct()
      val variants = (listOf(variant) + overrides.variants + DesignKey.DEFAULT_VARIANT).distinct()
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
  /**
   * The server choice [byKey] was read for, once its cache has loaded; null with no server or
   * before the first read. Only then does a missing key mean the server lacks it.
   */
  val loadedFor: CardDesignChoice? = null,
  /**
   * Device-specific designs by key: a card whose entity's device matches one renders with it
   * instead of the generic design for the same key ([DeviceMatch.pick]).
   */
  val deviceByKey: Map<DesignKey, List<Pair<DeviceMatch, ByteArray>>> = emptyMap(),
)

val LocalCardDesigns = staticCompositionLocalOf { CardDesigns() }

/**
 * The server designs cached on disk for [choice]. With no server this is empty and does no work. It
 * never touches the network: [CardDesignSync] fills the cache.
 */
suspend fun loadCardDesigns(context: Context, choice: CardDesignChoice): CardDesigns {
  if (!choice.enabled) return CardDesigns(variant = choice.variant)
  val resolved =
    withContext(Dispatchers.IO) {
      val dir = CardDesignSync.storeDir(context, choice.serverUrl)
      CardDesignSnapshot.read(dir, choice.prefer)
        ?: run {
          val stamp = CardDesignSnapshot.manifestStamp(dir)
          CardDesignSync.cachedRepository(context, choice.serverUrl)
            .resolveAllWithDevices(prefer = choice.prefer)
            .also { CardDesignSnapshot.write(dir, choice.prefer, stamp, it) }
        }
    }
  return CardDesigns(resolved.byKey, choice.variant, choice, resolved.deviceByKey)
}

/**
 * The user's server designs, read from disk once per process and kept in memory, so a dashboard's
 * first frame already plays them. Reading them on the dashboard's own composition left every card
 * on the local library's design for its first frames, then swapped to the server's: a different
 * document, which can lay out differently (a grid's per-row count, its cells' sizes).
 *
 * [start] runs from the application's start, well before a dashboard composes; it reads again when
 * the choice changes or a sync stores new designs ([CardDesignSync.revision]).
 */
object CardDesignPreload {
  private val _designs = MutableStateFlow<CardDesigns?>(null)

  /** Trace section around each read, so a startup trace shows when the cards stop waiting. */
  private const val LOAD_SECTION = "harc:loadCardDesigns"
  private var loadCookie = 0

  /** The designs for the current choice; null until the first read from disk completes. */
  val designs: StateFlow<CardDesigns?> = _designs.asStateFlow()

  fun start(context: Context, prefs: PreferencesStore, scope: CoroutineScope) {
    scope.launch {
      CardDesignSync.choice(context, prefs)
        .distinctUntilChanged()
        .combine(CardDesignSync.revision) { choice, _ -> choice }
        .collectLatest { choice ->
          // Another choice's designs are the wrong ones (another variant, author or server):
          // drop them while this one reads. A sync for the same choice keeps them until then.
          if (_designs.value?.loadedFor != choice) _designs.value = null
          // Async: the read suspends, and may resume on another thread.
          val cookie = loadCookie++
          Trace.beginAsyncSection(LOAD_SECTION, cookie)
          try {
            // All of it on IO, so the store's own IO hops don't dispatch: run from the main
            // dispatcher, every design read was a round trip through the main looper, and a cold
            // start took about 2 s to finish.
            _designs.value = withContext(Dispatchers.IO) { loadCardDesigns(context, choice) }
          } finally {
            Trace.endAsyncSection(LOAD_SECTION, cookie)
          }
        }
    }
  }
}

/** How long a dashboard holds its cards for the first design read before using local designs. */
internal const val DESIGN_PRELOAD_WAIT_MS = 1_500L

/**
 * The preloaded designs ([CardDesignPreload]), or null while the first read is still running. It
 * waits at most [DESIGN_PRELOAD_WAIT_MS]: after that a slow or broken cache never holds the
 * dashboard back, and the cards render from the local library until the read lands.
 *
 * Previews (inspection mode) never start the preload and don't advance the clock through the wait,
 * so they render from the local library straight away instead of capturing the hold's spinner.
 */
@Composable
fun rememberPreloadedCardDesigns(): CardDesigns? {
  val preloaded by CardDesignPreload.designs.collectAsState()
  if (LocalInspectionMode.current) return preloaded ?: CardDesigns()
  var waitedOut by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    delay(DESIGN_PRELOAD_WAIT_MS)
    waitedOut = true
  }
  return preloaded ?: if (waitedOut) CardDesigns() else null
}

/** The user's per-card and per-card-type design overrides, kept on the device. */
val LocalCardDesignOverrides = staticCompositionLocalOf { CardDesignOverrides() }

/**
 * The design [card] renders with: the server's design for its key in the preferred variant
 * ([variant] when the card overrides it, else the choice's), then in the `default` variant,
 * otherwise the local library's. Null only when the card has no template shape (an unsupported type
 * or config, or an entity not loaded yet); those cards capture through their converter.
 *
 * A dashboard card counts towards template usage ([CardUsage]); [recordUsage] false is for samples
 * that aren't the user's cards (the card style picker).
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
  variant: String? = null,
  recordUsage: Boolean = true,
): CardTemplatePlayback? {
  val key =
    CardDesignTemplates.keyFor(card, snapshot, style, dark, registry, cardStyle = cardStyle)
      ?: return null
  val preferred = key.copy(variant = variant ?: designs.variant)
  // A design made for this card's device wins over the generic one, in either variant. Each
  // lookup keeps the key it matched under, so usage reports the variant whose bytes were used.
  val device = cardDevice(card, snapshot)
  val (server, usedKey) =
    listOf(preferred, key).firstNotNullOfOrNull { k ->
      device?.let { DeviceMatch.pick(designs.deviceByKey[k].orEmpty(), it) }?.let { it to k }
    }
      ?: listOf(preferred, key).firstNotNullOfOrNull { k -> designs.byKey[k]?.let { it to k } }
      ?: (null to key)
  designs.loadedFor?.let { CardDesignSources.record(it, key, fromServer = server != null) }
  if (recordUsage) {
    CardUsage.record(
      card,
      cardType = key.cardType,
      variant = usedKey.variant,
      style = key.cardStyle,
      source = if (server != null) UsageSource.Remote else UsageSource.Local,
    )
  }
  // A local capture that fails leaves the card to draw through its converter.
  val bytes = server ?: CardDesignLibrary.templateOrNull(context, key, registry) ?: return null
  // Designs read the player's display (Host density); fold this phone's in once.
  val metrics = context.resources.displayMetrics
  val folded =
    CardDesignTemplates.forDisplay(
      bytes,
      metrics.density,
      context.resources.configuration.fontScale,
    )
  return CardDesignTemplates.playback(card, folded, registry)
}

/**
 * Resolves [cards]' templates ahead of time, in order and off the main thread, exactly as each
 * card's slot will ([cardTemplateFor]): the local library's capture (or its disk store's read), the
 * fold for this display, and the decode for the player. A card then composes against warm caches
 * instead of paying for all three on the frame it first scrolls on screen, which is what dropped
 * frames on a cold start. A card the warm-up hasn't reached yet resolves as before; one it is busy
 * with waits for it rather than doing the work twice ([CardDesignLibrary],
 * [CardDesignTemplates.forDisplay]).
 *
 * Cancellation stops between cards. A card that can't be resolved is skipped: its slot reports it.
 */
suspend fun warmCardTemplates(
  context: Context,
  cards: List<CardConfig>,
  snapshot: HaSnapshot,
  designs: CardDesigns,
  overrides: CardDesignOverrides,
  style: ThemeStyle,
  dark: Boolean,
  registry: CardRegistry,
  cardStyle: HaUberTheme,
) =
  withContext(Dispatchers.Default) {
    for (card in cards) {
      ensureActive()
      runCatching {
        val override = overrides.forCard(card)
        cardTemplateFor(
            context,
            CardDesignTemplates.designCard(card, snapshot, registry),
            snapshot,
            designs,
            style,
            dark,
            registry,
            override?.cardStyle?.let(HaUberTheme::byId) ?: cardStyle,
            variant = override?.variant,
            recordUsage = false,
          )
          ?.let { preloadForComposePlayer(it.bytes) }
      }
        .onFailure {
          android.util.Log.w("CardTemplateWarmup", "warm-up failed for ${card.type}", it)
        }
    }
  }

/**
 * The device behind [card]'s main entity (its `entity`, else its first listed one), as the
 * snapshot's registries know it; null when unknown.
 */
internal fun cardDevice(card: CardConfig, snapshot: HaSnapshot): DeviceInfo? =
  // Only the main entity counts: a later entity's device must not restyle the whole card.
  cardEntityIds(card).firstOrNull()?.let { snapshot.devices[it] }

/**
 * Where this process's cards got their designs: the server, or the app's own library because the
 * server had none for the key. Kept on the device only; Settings shows it, so the gaps the server
 * should fill are visible.
 */
object CardDesignSources {
  private var choice: CardDesignChoice? = null
  private val fromServer = HashMap<DesignKey, Boolean>()

  /**
   * Records where [key]'s design came from under [choice], the server choice whose cache was
   * loaded. A different choice starts the record afresh: its server, variant or author may differ.
   */
  @Synchronized
  fun record(choice: CardDesignChoice, key: DesignKey, fromServer: Boolean) {
    if (choice != this.choice) {
      this.choice = choice
      this.fromServer.clear()
    }
    this.fromServer[key] = fromServer
  }

  /** How many distinct designs came from each source, and the keys the server lacked. */
  data class Summary(val server: Int, val local: List<DesignKey>)

  /** The record for [choice], empty when the last one recorded was for another. */
  @Synchronized
  fun summary(choice: CardDesignChoice? = this.choice): Summary {
    if (choice == null || choice != this.choice) return Summary(0, emptyList())
    val all = fromServer.entries.toList()
    return Summary(
      server = all.count { it.value },
      local = all.filter { !it.value }.map { it.key }.sortedBy { "${it.cardType} ${it.shape}" },
    )
  }
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
