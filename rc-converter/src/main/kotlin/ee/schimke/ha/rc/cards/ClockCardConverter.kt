package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.LocalPreviewClock
import ee.schimke.ha.rc.components.HaClockData
import ee.schimke.ha.rc.components.RemoteHaClock
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.jsonPrimitive

/**
 * `clock` card — local time text. The default render binds
 * `RemoteTimeDefaults.defaultTimeString` so the player ticks the time
 * without a re-encode round trip. Configs that pin a specific time
 * zone or seconds-display drop back to a static encode-time label
 * because the bound RemoteString uses the host's locale-default
 * formatting (no per-card override path yet).
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

        // Live-ticking path: no time_zone override, no show_seconds, no
        // preview-clock override. When a preview clock is in scope the
        // rendered document must be deterministic, so fall back to the
        // static-label encoding.
        val previewNow = LocalPreviewClock.current
        val canTick = tz == null && !showSeconds && previewNow == null
        val use24Hour = timeFmt != "12"

        // Static fallback for time-zone / seconds / preview variants.
        val staticLabel = if (canTick) null else run {
            val pattern = when {
                timeFmt == "12" -> if (showSeconds) "h:mm:ss a" else "h:mm a"
                else -> if (showSeconds) "HH:mm:ss" else "HH:mm"
            }
            val now = clockNow(tz, previewNow)
            now.format(DateTimeFormatter.ofPattern(pattern))
        }

        val display = card.raw["display"]?.jsonPrimitive?.content ?: "primary"
        val secondaryLabel = if (display == "primary" || display == null) {
            clockNow(tz, previewNow).format(DateTimeFormatter.ofPattern("EEE d MMM"))
        } else null
        val size = card.raw["clock_size"]?.jsonPrimitive?.content
        val isLarge = size == "large" || size == "medium"

        RemoteHaClock(
            HaClockData(
                title = title?.rs,
                staticTimeLabel = staticLabel?.rs,
                secondaryLabel = secondaryLabel?.rs,
                isLarge = isLarge,
                use24Hour = use24Hour,
            ),
            modifier = modifier,
        )
    }
}

private fun clockNow(tz: ZoneId?, previewNow: ZonedDateTime?): ZonedDateTime {
    val zone = tz ?: previewNow?.zone ?: ZoneId.systemDefault()
    return previewNow?.withZoneSameInstant(zone) ?: ZonedDateTime.now(zone)
}
