@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.core.RemoteContext
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.border
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.RemoteBoolean
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.floor
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.remote.creation.compose.text.RemoteTimeDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import java.text.DecimalFormat

/**
 * `clock` card — local time text. The default path binds
 * `RemoteTimeDefaults.defaultTimeString` so the document ticks the
 * time once a minute on the player without a re-encode round trip.
 * [HaClockData.staticTimeLabel] overrides for a frozen capture (used
 * by previews and any host that prefers snapshot-time text).
 *
 * `time_zone` / `show_seconds` overrides take a separate live path
 * that builds the displayed `RemoteString` from the player's
 * hour/minute/second floats (`RemoteContext.FLOAT_TIME_IN_*`) — same
 * tick guarantee as the default, just with the offset and seconds
 * baked into the formatting expression.
 */
@Composable
@RemoteComposable
fun RemoteHaClock(data: HaClockData, modifier: RemoteModifier = RemoteModifier) {
    val theme = haTheme()
    val timeSize = if (data.isLarge) 64 else 36
    RemoteBox(
        modifier = modifier
            .fillMaxWidth()
            .clip(RemoteRoundedCornerShape(12.rdp))
            .background(theme.cardBackground.rc)
            .border(1.rdp, theme.divider.rc, RemoteRoundedCornerShape(12.rdp))
            .padding(horizontal = 14.rdp, vertical = 12.rdp),
        contentAlignment = RemoteAlignment.Center,
    ) {
        RemoteColumn(
            horizontalAlignment = RemoteAlignment.CenterHorizontally,
            verticalArrangement = RemoteArrangement.spacedBy(2.rdp),
        ) {
            if (data.title != null) {
                RemoteText(
                    text = data.title,
                    color = theme.secondaryText.rc,
                    fontSize = 12.rsp,
                    style = RemoteTextStyle.Default,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val timeText = data.staticTimeLabel
                ?: if (data.zoneOffsetMinutes != 0 || data.showSeconds) {
                    liveClockTimeString(
                        zoneOffsetMinutes = data.zoneOffsetMinutes,
                        showSeconds = data.showSeconds,
                        use24Hour = data.use24Hour,
                    )
                } else {
                    RemoteTimeDefaults.defaultTimeString(
                        is24HourFormat = if (data.use24Hour) RemoteBoolean(true) else RemoteTimeDefaults.is24HourFormat(),
                    )
                }
            RemoteText(
                text = timeText,
                color = theme.primaryText.rc,
                fontSize = timeSize.rsp,
                fontWeight = FontWeight.Light,
                style = RemoteTextStyle.Default,
                maxLines = 1,
            )
            if (data.secondaryLabel != null) {
                RemoteText(
                    text = data.secondaryLabel,
                    color = theme.secondaryText.rc,
                    fontSize = 12.rsp,
                    style = RemoteTextStyle.Default,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val twoDigits = DecimalFormat("00")
private val oneDigit = DecimalFormat("0")

/**
 * Build a live time `RemoteString` from the player's hour/minute/second
 * floats. Mirrors the shape of `RemoteTimeDefaults.defaultTimeString`
 * but adds zone-offset shifting and an optional seconds component.
 *
 * `RemoteContext.FLOAT_TIME_IN_HR` is hour-of-day; `FLOAT_TIME_IN_MIN`
 * and `FLOAT_TIME_IN_SEC` are minute / second since midnight, so the
 * `% 60` reduces them to minute/second-of-the-current-unit. To shift
 * into [zoneOffsetMinutes] (target zone − host zone) we recompose
 * minute-of-day, add the offset, mod 1440, and re-split into hours
 * and minutes.
 */
private fun liveClockTimeString(
    zoneOffsetMinutes: Int,
    showSeconds: Boolean,
    use24Hour: Boolean,
): RemoteString {
    val hostHour = RemoteFloat(RemoteContext.FLOAT_TIME_IN_HR)
    val hostMin = RemoteFloat(RemoteContext.FLOAT_TIME_IN_MIN).rem(60f)
    val sec = RemoteFloat(RemoteContext.FLOAT_TIME_IN_SEC).rem(60f)

    val (hour24, minute) = if (zoneOffsetMinutes == 0) {
        hostHour to hostMin
    } else {
        // +1_440_000 keeps the modulo argument positive across any plausible
        // zone offset before we wrap to minutes-of-day.
        val totalMin = (hostHour * 60f + hostMin + (zoneOffsetMinutes + 1_440_000).toFloat()).rem(1440f)
        floor(totalMin / 60f) to totalMin.rem(60f)
    }

    val hourText = if (use24Hour) {
        hour24.toRemoteString(twoDigits)
    } else {
        // 12-hour clock: 0→12, 1..11→same, 12→12, 13..23→1..11.
        val mod12 = hour24.rem(12f)
        mod12.eq(0f.rf).select(12f.rf, mod12).toRemoteString(oneDigit)
    }

    var text: RemoteString = hourText + ":" + minute.toRemoteString(twoDigits)
    if (showSeconds) {
        text = text + ":" + sec.toRemoteString(twoDigits)
    }
    if (!use24Hour) {
        text = text + " " + hour24.lt(12f.rf).select("AM".rs, "PM".rs)
    }
    return text
}
