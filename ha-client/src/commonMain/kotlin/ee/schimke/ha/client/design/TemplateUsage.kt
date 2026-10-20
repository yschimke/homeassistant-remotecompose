package ee.schimke.ha.client.design

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Whether the user shares anonymous card usage with the design server
 * (docs/followups/template-usage-tracking.md, phase 2).
 *
 * Stored as this tri-state, never as a boolean: [Enabled] and [Disabled] are the user's explicit
 * choice and are never overridden; [Default] (what everyone starts on) follows [DEFAULT_SHARES], a
 * single constant a later release can flip. Settings shows [effective] as a switch, and touching it
 * writes [Enabled] or [Disabled], never [Default] again.
 */
enum class UsageSharing {
  Enabled,
  Disabled,
  Default;

  /** Whether usage is counted and shared: [Default] reads [defaultShares]. */
  fun effective(defaultShares: Boolean = DEFAULT_SHARES): Boolean =
    when (this) {
      Enabled -> true
      Disabled -> false
      Default -> defaultShares
    }

  companion object {
    /**
     * What [Default] means. Off: sharing is opt-in, so nothing is counted or sent until the user
     * turns it on. Flipping it changes only users who never touched the setting.
     */
    const val DEFAULT_SHARES: Boolean = false

    /** The stored [name], else [Default] (a missing or unknown value). */
    fun parse(stored: String?): UsageSharing = entries.firstOrNull { it.name == stored } ?: Default

    /** What the settings switch writes: always an explicit choice. */
    fun chosen(on: Boolean): UsageSharing = if (on) Enabled else Disabled
  }
}

/** Where a rendered card's document came from. */
enum class UsageSource {
  /** A design downloaded from the design server. */
  Remote,
  /** The app's own design library: the server had none for the card's key. */
  Local,
  /** The card's converter: a card with no template shape. */
  Converter,
  /** No converter at all (a custom card the app can't draw). */
  Unsupported,
}

/**
 * One row of usage: which template drew cards, never which cards. Every field is a design-library
 * tag (card type, variant, card style) or [UsageSource]; nothing from Home Assistant (entity ids,
 * names, states, URLs) has a place here.
 */
@Serializable
data class UsageKey(
  val cardType: String,
  val variant: String,
  /** The uber theme id the card was drawn in (`DesignKey.cardStyle`). */
  val style: String,
  val source: UsageSource,
  /**
   * Where the card was drawn: [UsageSurfaces.PHONE] (the default, left out of the JSON so a ledger
   * or report from before surfaces reads the same), a launcher widget, the desktop or the web app.
   */
  val surface: String = UsageSurfaces.PHONE,
)

/** The [UsageKey.surface] tags apps report. */
object UsageSurfaces {
  const val PHONE: String = "phone"
  const val WIDGET: String = "widget"
  const val DESKTOP: String = "desktop"
  const val WEB: String = "web"
}

/** A [UsageKey] with how many distinct cards used it that day, as a bucket ([bucketOf]). */
@Serializable
data class UsageRow(
  val cardType: String,
  val variant: String,
  val style: String,
  val source: UsageSource,
  val bucket: String,
  /** [UsageKey.surface]; a report without it is from the phone. */
  val surface: String = UsageSurfaces.PHONE,
)

/** One day's usage, as uploaded: the app that sent it and its bucketed rows. */
@Serializable
data class UsageReport(
  /** UTC day, `yyyy-mm-dd`. */
  val day: String,
  val app: String,
  val appVersion: String,
  val platform: String,
  val rows: List<UsageRow>,
)

/**
 * Pending usage on the device, by UTC day: for each [UsageKey], local fingerprints of the cards
 * that used it, so a card counts once a day however often it renders. Fingerprints never leave the
 * device; only their count, bucketed, does.
 */
@Serializable data class UsageLedger(val days: Map<String, Map<String, Set<String>>> = emptyMap())

/** Where the [UsageLedger] lives. Implementations write atomically. */
interface UsageStore {
  suspend fun read(): UsageLedger?

  suspend fun write(ledger: UsageLedger)

  suspend fun delete()
}

class InMemoryUsageStore : UsageStore {
  var ledger: UsageLedger? = null
    private set

  override suspend fun read(): UsageLedger? = ledger

  override suspend fun write(ledger: UsageLedger) {
    this.ledger = ledger
  }

  override suspend fun delete() {
    ledger = null
  }
}

/**
 * Counts which templates draw this device's cards, per day, while [sharing] says so, and uploads
 * finished days to the design server. When sharing is off nothing is counted, and [clear] (called
 * when it turns off) deletes whatever was pending.
 *
 * @property sharing read on every call, so turning the setting off takes effect at once.
 * @property today the current UTC day, `yyyy-mm-dd`.
 */
class TemplateUsage(
  private val store: UsageStore,
  private val sharing: suspend () -> Boolean,
  private val today: () -> String,
) {
  private val lock = Mutex()
  private val json = Json

  /**
   * Notes that a card ([fingerprint], a local hash of its config) rendered with [key] today. A
   * no-op while sharing is off.
   */
  suspend fun record(key: UsageKey, fingerprint: String) {
    if (!sharing()) return
    lock.withLock {
      val ledger = store.read() ?: UsageLedger()
      val day = today()
      val keys = ledger.days[day].orEmpty()
      val id = json.encodeToString(UsageKey.serializer(), key)
      val cards = keys[id].orEmpty()
      if (fingerprint in cards) return
      store.write(UsageLedger(ledger.days + (day to (keys + (id to (cards + fingerprint))))))
    }
  }

  /** The reports for every finished day (before [today]); today keeps counting. */
  suspend fun pending(app: String, appVersion: String, platform: String): List<UsageReport> =
    lock.withLock {
      val ledger = store.read() ?: return emptyList()
      val day = today()
      ledger.days
        .filterKeys { it < day }
        .entries
        .sortedBy { it.key }
        .map { (d, keys) ->
          UsageReport(
            day = d,
            app = app,
            appVersion = appVersion,
            platform = platform,
            rows =
              keys
                .map { (id, cards) ->
                  val key = json.decodeFromString(UsageKey.serializer(), id)
                  UsageRow(
                    key.cardType,
                    key.variant,
                    key.style,
                    key.source,
                    bucketOf(cards.size),
                    key.surface,
                  )
                }
                .sortedWith(
                  compareBy(
                    { it.surface },
                    { it.cardType },
                    { it.variant },
                    { it.style },
                    { it.source },
                  )
                ),
          )
        }
    }

  /** Forgets [day] once it was uploaded. */
  suspend fun sent(day: String) {
    lock.withLock {
      val ledger = store.read() ?: return
      store.write(UsageLedger(ledger.days - day))
    }
  }

  /** Deletes everything pending: sharing was turned off. */
  suspend fun clear() {
    lock.withLock { store.delete() }
  }

  /**
   * Uploads every finished day to `<baseUrl>/usage`, forgetting each one the server took. Does
   * nothing while sharing is off (and clears what is pending), and never throws for network
   * trouble: an unsent day waits for the next try.
   */
  suspend fun upload(
    httpClient: HttpClient,
    baseUrl: String,
    userAgent: UsageUserAgent,
    sendUserAgentHeader: Boolean = true,
  ): Int {
    if (!sharing()) {
      clear()
      return 0
    }
    var sent = 0
    for (report in pending(userAgent.app, userAgent.version, userAgent.platform)) {
      if (report.rows.isEmpty()) {
        sent(report.day)
        continue
      }
      val status = runCatching {
        httpClient
          .post("${baseUrl.trimEnd('/')}/$USAGE_PATH") {
            // A browser won't send a page's own User-Agent; the report names the app anyway.
            if (sendUserAgentHeader) {
              headers.append(io.ktor.http.HttpHeaders.UserAgent, userAgent.header)
            }
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(UsageReport.serializer(), report))
          }
          .status
      }
        .getOrNull()
      when {
        status == null -> break // Network trouble: retry this day next time.
        status.isSuccess() -> {
          sent(report.day)
          sent++
        }
        // The server will never take this report (too old, malformed): drop it, or it would
        // block every newer day behind it.
        isPermanentRejection(status) -> sent(report.day)
        else -> break // Rate limited or a server error: retry next time.
      }
    }
    return sent
  }

  companion object {
    /** A 4xx other than 408 / 429: retrying the same report can never succeed. */
    internal fun isPermanentRejection(status: HttpStatusCode): Boolean =
      status.value in 400..499 &&
        status != HttpStatusCode.RequestTimeout &&
        status != HttpStatusCode.TooManyRequests

    /**
     * The UTC day of [epochMs] as `yyyy-mm-dd`, for hosts without java.time (the desktop and web
     * app); the same day Android's `LocalDate.now(ZoneOffset.UTC)` gives.
     */
    fun utcDay(epochMs: Long): String {
      // Days since 1970-01-01 to a civil date (Howard Hinnant's days_from_civil, inverted).
      val z = epochMs.floorDiv(86_400_000L) + 719_468
      val era = z.floorDiv(146_097L)
      val doe = z - era * 146_097
      val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
      val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
      val mp = (5 * doy + 2) / 153
      val day = doy - (153 * mp + 2) / 5 + 1
      val month = if (mp < 10) mp + 3 else mp - 9
      val year = yoe + era * 400 + if (month <= 2) 1 else 0
      return "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-" +
        day.toString().padStart(2, '0')
    }

    /** The upload path under the design server's base URL. */
    const val USAGE_PATH: String = "usage"

    /** The buckets a day's card count is sent as. */
    val BUCKETS: List<String> = listOf("1", "2-5", "6-20", "21+")

    /** [count] as one of [BUCKETS]; a count is at least 1 (a key is only kept once used). */
    fun bucketOf(count: Int): String =
      when {
        count <= 1 -> "1"
        count <= 5 -> "2-5"
        count <= 20 -> "6-20"
        else -> "21+"
      }
  }
}

/**
 * [TemplateUsage.upload] with the platform's default HTTP engine, opened only for this upload; for
 * hosts that don't depend on Ktor themselves.
 */
suspend fun TemplateUsage.uploadWithDefaultClient(
  baseUrl: String,
  userAgent: UsageUserAgent,
  sendUserAgentHeader: Boolean = true,
): Int {
  val client = HttpClient()
  try {
    return upload(client, baseUrl, userAgent, sendUserAgentHeader)
  } finally {
    client.close()
  }
}

/**
 * The fixed, non-identifying User-Agent clients send to the design server: `Terrazzo/<version>
 * (<platform>; surface=<surface>)`. Every install of a release sends the same string.
 */
data class UsageUserAgent(
  val version: String,
  val platform: String,
  val surface: String,
  val app: String = "Terrazzo",
) {
  val header: String
    get() = "$app/$version ($platform; surface=$surface)"
}
