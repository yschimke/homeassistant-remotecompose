package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.HaClock
import ee.schimke.ha.rc.LocalHaClock
import ee.schimke.ha.rc.components.HaClockData
import ee.schimke.ha.rc.components.RemoteHaClock
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.jsonPrimitive

/**
 * `clock` card — local time text. The default render binds
 * `RemoteTimeDefaults.defaultTimeString` so the player ticks the time
 * without a re-encode round trip. Configs that pin `time_zone` or
 * `show_seconds` switch to a per-field live path that composes the
 * displayed string from the player's hour/minute/second floats — so
 * those variants tick live too. The zone shift is captured at encode
 * time; DST transitions during playback fall to the next re-encode.
 *
 * Wall-clock reads go through [LocalHaClock] — production renders see
 * [ee.schimke.ha.rc.SystemHaClock] and tick live, while previews /
 * tests inject a [ee.schimke.ha.rc.FixedHaClock] whose `isFrozen` flag
 * flips the converter onto a static-label path so the captured `.rc`
 * bytes (and therefore the rendered PNG) stay byte-stable across runs.
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

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        val title = card.raw["title"]?.jsonPrimitive?.content
        val tz = card.raw["time_zone"]?.jsonPrimitive?.content
            ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        val showSeconds = card.raw["show_seconds"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val timeFmt = card.raw["time_format"]?.jsonPrimitive?.content

        val clock = LocalHaClock.current
        val use24Hour = timeFmt != "12"

        // Frozen clock in scope → encode a static label so preview
        // PNGs are stable. Otherwise the time_zone / show_seconds path
        // ticks live via per-field RemoteFloat formatting in the
        // composable; no override at all → defaultTimeString.
        val staticLabel = if (clock.isFrozen) {
            val pattern = when {
                timeFmt == "12" -> if (showSeconds) "h:mm:ss a" else "h:mm a"
                else -> if (showSeconds) "HH:mm:ss" else "HH:mm"
            }
            clockNow(tz, clock).format(DateTimeFormatter.ofPattern(pattern))
        } else null

        val zoneOffsetMinutes = if (tz != null) {
            val now = clock.now()
            val target = tz.rules.getOffset(now).totalSeconds / 60
            val host = clock.zone().rules.getOffset(now).totalSeconds / 60
            target - host
        } else 0

        val display = card.raw["display"]?.jsonPrimitive?.content ?: "primary"
        val secondaryLabel = if (display == "primary") {
            clockNow(tz, clock).format(DateTimeFormatter.ofPattern("EEE d MMM"))
        } else null
        val size = card.raw["clock_size"]?.jsonPrimitive?.content
        val isLarge = size == "large" || size == "medium"

        RemoteHaClock(
            HaClockData(
                title = title,
                staticTimeLabel = staticLabel,
                secondaryLabel = secondaryLabel,
                isLarge = isLarge,
                use24Hour = use24Hour,
                zoneOffsetMinutes = zoneOffsetMinutes,
                showSeconds = showSeconds,
            ),
            modifier = modifier,
        )
    }
}

private fun clockNow(tz: ZoneId?, clock: HaClock): ZonedDateTime {
    val zone = tz ?: clock.zone()
    return ZonedDateTime.ofInstant(clock.now(), zone)
}
