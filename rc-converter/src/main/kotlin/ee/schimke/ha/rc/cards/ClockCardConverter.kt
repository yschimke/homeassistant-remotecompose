package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.components.HaClockData
import ee.schimke.ha.rc.components.RemoteHaClock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.jsonPrimitive

/**
 * `clock` card — local time text. Captured at capture time using the
 * snapshot's [HaSnapshot.locale] when available; the host re-encodes
 * to keep the label current. A future revision can wire
 * `RemoteAccess.getTime()` into a canvas drawText for live ticking
 * without re-encode.
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
            ?: ZoneId.systemDefault()
        val showSeconds = card.raw["show_seconds"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val timeFmt = card.raw["time_format"]?.jsonPrimitive?.content
        val pattern = when {
            timeFmt == "12" -> if (showSeconds) "h:mm:ss a" else "h:mm a"
            else -> if (showSeconds) "HH:mm:ss" else "HH:mm"
        }
        val now = java.time.ZonedDateTime.now(tz)
        val timeLabel = now.format(DateTimeFormatter.ofPattern(pattern))
        val display = card.raw["display"]?.jsonPrimitive?.content ?: "primary"
        val secondaryLabel = if (display == "primary" || display == null) {
            now.format(DateTimeFormatter.ofPattern("EEE d MMM"))
        } else null
        val size = card.raw["clock_size"]?.jsonPrimitive?.content
        val isLarge = size == "large" || size == "medium"

        RemoteHaClock(
            HaClockData(
                title = title?.rs,
                timeLabel = timeLabel.rs,
                secondaryLabel = secondaryLabel?.rs,
                isLarge = isLarge,
            ),
            modifier = modifier,
        )
    }
}
