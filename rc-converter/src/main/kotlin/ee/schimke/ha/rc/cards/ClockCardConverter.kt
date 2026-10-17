package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.HaClock
import ee.schimke.ha.rc.LocalHaClock
import ee.schimke.ha.rc.SystemHaClock
import ee.schimke.ha.rc.components.HaClockData
import ee.schimke.ha.rc.components.RemoteHaClock
import ee.schimke.ha.rc.components.placeholderTitle
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `clock` card — local time text. The default render binds `RemoteTimeDefaults.defaultTimeString`
 * so the player ticks the time without a re-encode round trip. Configs that pin `time_zone` or
 * `show_seconds` switch to a per-field live path that composes the displayed string from the
 * player's hour/minute/second floats — so those variants tick live too. The zone shift is captured
 * at encode time; DST transitions during playback fall to the next re-encode.
 *
 * Wall-clock reads go through [LocalHaClock] — production renders see
 * [ee.schimke.ha.rc.SystemHaClock] and tick live, while previews / tests inject a
 * [ee.schimke.ha.rc.FixedHaClock] whose `isFrozen` flag flips the converter onto a static-label
 * path so the captured `.rc` bytes (and therefore the rendered PNG) stay byte-stable across runs.
 */
class ClockCardConverter : CardConverter {
  override val cardType: String = CardTypes.CLOCK

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int {
    val size = card.raw["clock_size"]?.jsonPrimitive?.content
    return when (size) {
      "large" -> 140
      "medium" -> 100
      else -> 70
    }
  }

  /**
   * A clock renders from a card design: the time ticks in-document, the date line is the
   * `card.date` binding and another zone's offset is `card.zone_offset_minutes`, so only which
   * lines show and how the time is formatted are structure.
   */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? {
    if (!TEMPLATE_CONFIG_KEYS.containsAll(card.raw.keys)) return null
    if (card.raw["time_zone"] != null && zoneOf(card) == null) return null
    return DesignProperties.build {
      enum(HAS_TITLE, if (card.raw["title"] != null) 1 else 0)
      enum(SECONDS, if (showSeconds(card)) 1 else 0)
      enum(H24, if (card.raw["time_format"]?.jsonPrimitive?.content != "12") 1 else 0)
      enum(LARGE, if (isLarge(card)) 1 else 0)
      enum(ZONED, if (zoneOf(card) != null) 1 else 0)
      enum(DATE, if (showsDate(card)) 1 else 0)
    }
  }

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? {
    fun on(name: String) = properties[name] == "1"
    if (properties[H24] == null) return null
    val card =
      CardConfig(
        type = cardType,
        raw =
          buildJsonObject {
            put("type", cardType)
            if (on(HAS_TITLE)) put("title", placeholderTitle(0))
            if (on(SECONDS)) put("show_seconds", true)
            if (!on(H24)) put("time_format", "12")
            if (on(LARGE)) put("clock_size", "large")
            if (on(ZONED)) put("time_zone", "UTC")
            put("display", if (on(DATE)) "primary" else "time")
          },
      )
    return DesignTemplateInput(card, HaSnapshot())
  }

  override fun designBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings =
    designBindings(card, snapshot, SystemHaClock)

  /**
   * The date line and another zone's offset, read from [clock]: the same clock [Render] reads
   * through [LocalHaClock], so a template played under an injected clock shows what the direct
   * render under that clock shows.
   */
  override fun designBindings(
    card: CardConfig,
    snapshot: HaSnapshot,
    clock: HaClock,
  ): CardSnapshotBindings {
    val tz = zoneOf(card)
    val strings = buildMap { if (showsDate(card)) put("card.date", dateLabel(tz, clock)) }
    val floats = buildMap {
      if (tz != null) put("card.zone_offset_minutes", zoneOffsetMinutes(tz, clock).toFloat())
    }
    return CardSnapshotBindings(strings, emptyMap(), emptyMap(), floats)
  }

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val title = card.raw["title"]?.jsonPrimitive?.content
    val tz = zoneOf(card)
    val showSeconds = showSeconds(card)
    val timeFmt = card.raw["time_format"]?.jsonPrimitive?.content

    val clock = LocalHaClock.current
    val use24Hour = timeFmt != "12"

    // Frozen clock in scope → encode a static label so preview
    // PNGs are stable. Otherwise the time_zone / show_seconds path
    // ticks live via per-field RemoteFloat formatting in the
    // composable; no override at all → defaultTimeString.
    val staticLabel =
      if (clock.isFrozen) {
        val pattern =
          when {
            timeFmt == "12" -> if (showSeconds) "h:mm:ss a" else "h:mm a"
            else -> if (showSeconds) "HH:mm:ss" else "HH:mm"
          }
        clockNow(tz, clock).format(DateTimeFormatter.ofPattern(pattern))
      } else null

    val zoneOffsetMinutes = if (tz != null) zoneOffsetMinutes(tz, clock) else 0
    val secondaryLabel = if (showsDate(card)) dateLabel(tz, clock) else null

    RemoteHaClock(
      HaClockData(
        title = title,
        staticTimeLabel = staticLabel,
        secondaryLabel = secondaryLabel,
        isLarge = isLarge(card),
        use24Hour = use24Hour,
        zoneOffsetMinutes = zoneOffsetMinutes,
        showSeconds = showSeconds,
        // Any configured zone shifts in-document, even one that matches the host today: its
        // offset is a value (DST moves it), so it must never change the document's structure.
        zoned = tz != null,
      ),
      modifier = modifier,
    )
  }
}

private val TEMPLATE_CONFIG_KEYS =
  setOf("type", "title", "time_zone", "show_seconds", "time_format", "display", "clock_size")
private const val HAS_TITLE = "has_title"
private const val SECONDS = "seconds"
private const val H24 = "h24"
private const val LARGE = "large"
private const val ZONED = "zoned"
private const val DATE = "date"

private fun zoneOf(card: CardConfig): ZoneId? =
  card.raw["time_zone"]?.jsonPrimitive?.content?.let { runCatching { ZoneId.of(it) }.getOrNull() }

private fun showSeconds(card: CardConfig): Boolean =
  card.raw["show_seconds"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

private fun isLarge(card: CardConfig): Boolean =
  card.raw["clock_size"]?.jsonPrimitive?.content.let { it == "large" || it == "medium" }

private fun showsDate(card: CardConfig): Boolean =
  (card.raw["display"]?.jsonPrimitive?.content ?: "primary") == "primary"

private fun dateLabel(tz: ZoneId?, clock: HaClock): String =
  clockNow(tz, clock).format(DateTimeFormatter.ofPattern("EEE d MMM"))

/** Target zone − host zone, now. */
private fun zoneOffsetMinutes(tz: ZoneId, clock: HaClock): Int {
  val now = clock.now()
  return (tz.rules.getOffset(now).totalSeconds - clock.zone().rules.getOffset(now).totalSeconds) /
    60
}

private fun clockNow(tz: ZoneId?, clock: HaClock): ZonedDateTime {
  val zone = tz ?: clock.zone()
  return ZonedDateTime.ofInstant(clock.now(), zone)
}
