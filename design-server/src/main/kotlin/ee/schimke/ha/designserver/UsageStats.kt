package ee.schimke.ha.designserver

import ee.schimke.ha.client.design.TemplateUsage
import ee.schimke.ha.client.design.UsageReport
import ee.schimke.ha.design.DesignEntry
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Anonymous template usage (docs/followups/template-usage-tracking.md).
 *
 * Phase 1: every manifest and blob request is counted per UTC day, by what was fetched (a blob's
 * card type, variant, card style, surface and RC API level) and by the app's fixed User-Agent
 * ([ClientAgent]). Phase 2: apps whose users turned sharing on post a day's bucketed render counts
 * ([UsageReport]); each report adds one to its rows.
 *
 * Nothing that identifies a person or a device is kept: no IP address, no device id, no token, no
 * Home Assistant data. Counts are only ever shown with [K_ANONYMITY] or more behind them ([rows]).
 * Days live in memory and are written to [dir] (one JSON file per day) by [flush]; with no [dir]
 * they last until the server restarts. Days older than [RETENTION_DAYS] are deleted.
 */
class UsageStats(
  private val dir: File?,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
  }
  private val days = HashMap<String, MutableMap<UsageCell, Long>>()
  private val dirty = HashSet<String>()

  init {
    dir
      ?.listFiles { f -> DAY_FILE.matches(f.name) }
      ?.forEach { file ->
        runCatching { json.decodeFromString(StoredDay.serializer(), file.readText()) }
          .onSuccess { stored ->
            days[stored.day] = stored.cells.associate { it.cell to it.count }.toMutableMap()
          }
      }
  }

  /** The UTC day now, `yyyy-mm-dd`. */
  fun today(): String =
    LocalDate.ofInstant(Instant.ofEpochMilli(clock()), ZoneOffset.UTC).toString()

  /** Counts a manifest request from [userAgent]. */
  fun manifestFetched(userAgent: String?) {
    val agent = ClientAgent.parse(userAgent)
    add(
      UsageCell(
        kind = MANIFEST,
        app = agent.app,
        appVersion = agent.version,
        platform = agent.platform,
        surface = agent.surface,
      )
    )
  }

  /**
   * Counts a blob request for [entry] from [userAgent]. A person's own design counts as variant
   * `user`: its variant name is theirs to choose.
   */
  fun blobFetched(entry: DesignEntry, userAgent: String?) {
    val agent = ClientAgent.parse(userAgent)
    val key = entry.key
    add(
      UsageCell(
        kind = FETCH,
        app = agent.app,
        appVersion = agent.version,
        platform = agent.platform,
        surface = agent.surface,
        cardType = key.cardType,
        variant = if (entry.author == DesignEntry.BUILTIN_AUTHOR) key.variant else USER_VARIANT,
        style = key.cardStyle,
        designSurface = key.surface.name,
        rcApiLevel = key.rcApiLevel,
      )
    )
  }

  /** Adds one app's bucketed day of renders (already validated by [UsageReportCheck]). */
  fun reportReceived(report: UsageReport) {
    for (row in report.rows) {
      add(
        UsageCell(
          kind = RENDER,
          app = report.app,
          appVersion = report.appVersion,
          platform = report.platform,
          surface = row.surface,
          cardType = row.cardType,
          variant = row.variant,
          style = row.style,
          source = row.source.name.lowercase(),
          bucket = row.bucket,
        ),
        day = report.day,
      )
    }
  }

  @Synchronized
  private fun add(cell: UsageCell, day: String = today()) {
    val counts = days.getOrPut(day) { HashMap() }
    counts[cell] = (counts[cell] ?: 0) + 1
    dirty += day
  }

  /**
   * Every count from [from] to [to] (inclusive UTC days) summed over days, with any cell under
   * [K_ANONYMITY] dropped. The only way counts leave the server.
   */
  @Synchronized
  fun rows(from: String, to: String): List<UsageCount> =
    days
      .filterKeys { it in from..to }
      .values
      .flatMap { it.entries }
      .groupBy({ it.key }, { it.value })
      .map { (cell, counts) -> UsageCount(cell, counts.sum()) }
      .filter { it.count >= K_ANONYMITY }
      .sortedByDescending { it.count }

  /** Writes changed days to [dir] and deletes days past [RETENTION_DAYS]. */
  @Synchronized
  fun flush() {
    val oldest = LocalDate.parse(today()).minusDays(RETENTION_DAYS).toString()
    days.keys
      .filter { it < oldest }
      .forEach {
        days.remove(it)
        dirty.remove(it)
        dir?.let { d -> File(d, "$it.json").delete() }
      }
    val target = dir ?: return dirty.clear()
    target.mkdirs()
    for (day in dirty) {
      val cells = days[day].orEmpty().map { StoredCell(it.key, it.value) }
      writeAtomically(
        File(target, "$day.json"),
        json.encodeToString(StoredDay.serializer(), StoredDay(day, cells)).encodeToByteArray(),
      )
    }
    dirty.clear()
  }

  private fun writeAtomically(target: File, bytes: ByteArray) {
    val tmp = File.createTempFile("${target.name}.", ".tmp", target.parentFile)
    try {
      tmp.writeBytes(bytes)
      Files.move(
        tmp.toPath(),
        target.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
    } finally {
      tmp.delete()
    }
  }

  companion object {
    /** A count is shown only with at least this many requests or reports behind it. */
    const val K_ANONYMITY: Long = 5

    /** Days are kept this long (about 13 months), then deleted. */
    const val RETENTION_DAYS: Long = 400

    const val MANIFEST = "manifest"
    const val FETCH = "fetch"
    const val RENDER = "render"
    const val USER_VARIANT = "user"

    private val DAY_FILE = Regex("\\d{4}-\\d{2}-\\d{2}\\.json")
  }
}

/** One counted thing; fields that don't apply to its [kind] stay empty. */
@Serializable
data class UsageCell(
  /** [UsageStats.MANIFEST], [UsageStats.FETCH] or [UsageStats.RENDER]. */
  val kind: String,
  val app: String,
  val appVersion: String,
  val platform: String,
  val surface: String = "",
  val cardType: String = "",
  val variant: String = "",
  /** The card style (uber theme id). */
  val style: String = "",
  val designSurface: String = "",
  val rcApiLevel: Int = 0,
  /** For renders: remote, local, converter or unsupported. */
  val source: String = "",
  /** For renders: how many cards one device drew with it that day ([TemplateUsage.BUCKETS]). */
  val bucket: String = "",
)

@Serializable data class UsageCount(val cell: UsageCell, val count: Long)

@Serializable private data class StoredCell(val cell: UsageCell, val count: Long)

@Serializable private data class StoredDay(val day: String, val cells: List<StoredCell>)

/**
 * The app behind a request, from the fixed User-Agent apps send (`Terrazzo/<version> (<platform>;
 * surface=<surface>)`). Anything else (a browser, the embed, an older app) is [OTHER], so a
 * free-form User-Agent is never stored.
 */
data class ClientAgent(
  val app: String,
  val version: String,
  val platform: String,
  val surface: String,
) {
  companion object {
    const val OTHER = "other"

    private val PATTERN =
      Regex(
        """(Terrazzo)/([0-9A-Za-z.+\-]{1,32}) \(([a-z0-9]{1,16}); surface=([a-z0-9\-]{1,16})\)"""
      )

    /**
     * The app behind a request: its User-Agent, else the `client` query parameter carrying the same
     * fixed string, which a browser app sends because a page can't set its User-Agent.
     */
    fun of(userAgent: String?, clientParameter: String?): String? =
      userAgent?.takeIf { parse(it).app != OTHER } ?: clientParameter ?: userAgent

    fun parse(userAgent: String?): ClientAgent {
      val match =
        userAgent?.let { PATTERN.matchEntire(it.trim()) } ?: return ClientAgent(OTHER, "", "", "")
      val (app, version, platform, surface) = match.destructured
      return ClientAgent(app, version, platform, surface)
    }
  }
}

/**
 * What `POST /designs/v1/usage` accepts: exactly a [UsageReport] (an unknown field is refused), for
 * a recent day, from a known app, with short tag-like values and a bucket from
 * [TemplateUsage.BUCKETS]. Anything else is refused whole.
 */
object UsageReportCheck {
  private val strict = Json { ignoreUnknownKeys = false }
  private val TAG = Regex("[a-z0-9][a-z0-9:_.\\-]{0,63}")
  private val VERSION = Regex("[0-9A-Za-z.+\\-]{1,32}")
  private val PLATFORM = Regex("[a-z0-9]{1,16}")
  private val SURFACE = Regex("[a-z0-9\\-]{1,16}")
  private const val MAX_ROWS = 500

  /** The report in [body], or why it is refused. */
  fun parse(body: String, today: String): Result<UsageReport> = runCatching {
    val report = strict.decodeFromString(UsageReport.serializer(), body)
    val day = LocalDate.parse(report.day)
    val now = LocalDate.parse(today)
    require(!day.isAfter(now) && !day.isBefore(now.minusDays(30))) { "day out of range" }
    require(report.app == "Terrazzo") { "unknown app" }
    require(VERSION.matches(report.appVersion)) { "bad appVersion" }
    require(PLATFORM.matches(report.platform)) { "bad platform" }
    require(report.rows.size in 1..MAX_ROWS) { "bad row count" }
    for (row in report.rows) {
      require(TAG.matches(row.cardType)) { "bad cardType" }
      require(TAG.matches(row.variant)) { "bad variant" }
      require(TAG.matches(row.style)) { "bad style" }
      require(row.bucket in TemplateUsage.BUCKETS) { "bad bucket" }
      require(SURFACE.matches(row.surface)) { "bad surface" }
    }
    report
  }
}

/**
 * A fixed-window limit on usage reports across all callers: at most [perMinute] a minute. Kept in
 * memory and keyed on nothing, so it never stores who sent what.
 */
class UsageRateLimit(
  private val perMinute: Int = 600,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private var window = -1L
  private var count = 0

  @Synchronized
  fun allow(): Boolean {
    val minute = clock() / 60_000
    if (minute != window) {
      window = minute
      count = 0
    }
    return ++count <= perMinute
  }
}
