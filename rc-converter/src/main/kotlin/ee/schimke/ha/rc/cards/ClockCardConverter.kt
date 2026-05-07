package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.LocalPreviewClock
import ee.schimke.ha.rc.components.HaClockData
import ee.schimke.ha.rc.components.RemoteHaClock
import java.time.Instant
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
 * When [LocalPreviewClock] is in scope the converter encodes a static
 * label instead, so preview PNGs stay deterministic across renders.
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

        val previewNow = LocalPreviewClock.current
        val use24Hour = timeFmt != "12"

        // Preview clock in scope → freeze to a static label so preview
        // PNGs are stable. Otherwise the time_zone / show_seconds path
        // ticks live via per-field RemoteFloat formatting in the
        // composable; no override at all → defaultTimeString.
        val staticLabel = if (previewNow != null) {
            val pattern = when {
                timeFmt == "12" -> if (showSeconds) "h:mm:ss a" else "h:mm a"
                else -> if (showSeconds) "HH:mm:ss" else "HH:mm"
            }
            clockNow(tz, previewNow).format(DateTimeFormatter.ofPattern(pattern))
        } else null

        val zoneOffsetMinutes = if (tz != null) {
            val now = Instant.now()
            val target = tz.rules.getOffset(now).totalSeconds / 60
            val host = ZoneId.systemDefault().rules.getOffset(now).totalSeconds / 60
            target - host
        } else 0

        val display = card.raw["display"]?.jsonPrimitive?.content ?: "primary"
        val secondaryLabel = if (display == "primary" || display == null) {
            clockNow(tz, previewNow).format(DateTimeFormatter.ofPattern("EEE d MMM"))
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

private fun clockNow(tz: ZoneId?, previewNow: ZonedDateTime?): ZonedDateTime {
    val zone = tz ?: previewNow?.zone ?: ZoneId.systemDefault()
    return previewNow?.withZoneSameInstant(zone) ?: ZonedDateTime.now(zone)
}
