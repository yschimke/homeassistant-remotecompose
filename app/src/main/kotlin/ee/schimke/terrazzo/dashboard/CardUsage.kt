package ee.schimke.terrazzo.dashboard

import android.content.Context
import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.client.design.TemplateUsage
import ee.schimke.ha.client.design.UsageKey
import ee.schimke.ha.client.design.UsageLedger
import ee.schimke.ha.client.design.UsageSource
import ee.schimke.ha.client.design.UsageStore
import ee.schimke.ha.client.design.UsageSurfaces
import ee.schimke.ha.client.design.UsageUserAgent
import ee.schimke.ha.client.design.uploadWithDefaultClient
import ee.schimke.ha.model.CardConfig
import ee.schimke.terrazzo.BuildConfig
import ee.schimke.terrazzo.core.prefs.PreferencesStore
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * The app's anonymous card usage (docs/followups/template-usage-tracking.md, phase 2): which
 * template drew each dashboard card, counted per day while the user has sharing on, and uploaded by
 * the nightly design sync. Off by default: until the user turns "Share anonymous card usage" on,
 * nothing is counted or sent, and turning it off deletes what was pending.
 */
object CardUsage {
  /** What every request to the design server says about this app: its release, nothing else. */
  val userAgent: UsageUserAgent =
    UsageUserAgent(version = BuildConfig.VERSION_NAME, platform = "android", surface = "phone")

  @Volatile private var usage: TemplateUsage? = null
  @Volatile private var scope: CoroutineScope? = null

  /**
   * Starts counting under [prefs]' setting, from the application's start. Whenever the effective
   * setting is off, pending counts are deleted.
   */
  fun start(context: Context, prefs: PreferencesStore, scope: CoroutineScope) {
    val usage = of(context, prefs)
    this.scope = scope
    scope.launch {
      prefs.usageSharing
        .map { it.effective() }
        .distinctUntilChanged()
        .collect { on -> if (!on) usage.clear() }
    }
  }

  private fun of(context: Context, prefs: PreferencesStore): TemplateUsage =
    usage
      ?: synchronized(this) {
        usage
          ?: TemplateUsage(
              store = FileUsageStore(File(context.filesDir, "terrazzo/usage/ledger.json")),
              sharing = { prefs.usageSharing.first().effective() },
              today = { LocalDate.now(ZoneOffset.UTC).toString() },
            )
            .also { usage = it }
      }

  /**
   * Notes that [card] rendered from [source] with the design for [cardType], [variant] and [style]
   * (an uber theme id), on [surface]: the dashboard ([UsageSurfaces.PHONE]) or a launcher widget
   * ([UsageSurfaces.WIDGET]). The card itself is reduced to a local fingerprint that never leaves
   * the device. A no-op before [start] and while sharing is off.
   */
  fun record(
    card: CardConfig,
    cardType: String,
    variant: String,
    style: String,
    source: UsageSource,
    surface: String = UsageSurfaces.PHONE,
  ) {
    val usage = usage ?: return
    val scope = scope ?: return
    val fingerprint = Sha256.hex(card.raw.toString().encodeToByteArray())
    scope.launch { usage.record(UsageKey(cardType, variant, style, source, surface), fingerprint) }
  }

  /** Uploads finished days to [serverUrl]; called by the nightly sync. */
  suspend fun upload(context: Context, prefs: PreferencesStore, serverUrl: String): Int {
    if (serverUrl.isBlank()) return 0
    return of(context, prefs).uploadWithDefaultClient(serverUrl, userAgent)
  }
}

/** The [UsageLedger] in one JSON file, written atomically. */
class FileUsageStore(private val file: File) : UsageStore {
  private val json = Json { ignoreUnknownKeys = true }

  override suspend fun read(): UsageLedger? =
    withContext(Dispatchers.IO) {
      if (!file.isFile) return@withContext null
      runCatching { json.decodeFromString(UsageLedger.serializer(), file.readText()) }.getOrNull()
    }

  override suspend fun write(ledger: UsageLedger) =
    withContext(Dispatchers.IO) {
      val dir = requireNotNull(file.parentFile).apply { mkdirs() }
      val tmp = File.createTempFile("${file.name}.", ".tmp", dir)
      try {
        tmp.writeText(json.encodeToString(UsageLedger.serializer(), ledger))
        java.nio.file.Files.move(
          tmp.toPath(),
          file.toPath(),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
      } finally {
        tmp.delete()
      }
      Unit
    }

  override suspend fun delete() =
    withContext(Dispatchers.IO) {
      file.delete()
      Unit
    }
}
