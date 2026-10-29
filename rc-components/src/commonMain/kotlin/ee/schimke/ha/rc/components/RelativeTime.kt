@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.state.RemoteInt
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.RemoteTimeVariables
import androidx.compose.remote.creation.compose.state.max
import androidx.compose.remote.creation.compose.state.ri
import androidx.compose.remote.creation.compose.state.rs

/**
 * How long ago [since] was, in Home Assistant's words, computed by the document against the
 * player's own clock: `just now`, `5 min ago`, `3 h ago`, `2 days ago`, and from a week on the date
 * (`20 Apr 2026`, in the player's time zone). [since] is whole Unix seconds, as `<id>.last_changed`
 * carries Home Assistant's `last_changed`; 0 (no time) reads as nothing.
 *
 * The label moves on by itself as the clock does: no host formats it or pushes it again. It matches
 * ha-model's `formatRelativeTime` for a past time (HA's `selectUnit` thresholds of 45 s, 45 min and
 * 22 h, rounding half up); a time ahead of the player's clock, which only a skewed clock gives for
 * `last_changed`, reads as `just now`. The subtraction is whole-number arithmetic, so it is exact
 * to the second: a 32-bit float can't hold today's epoch to the second.
 *
 * rc-player (the app's dashboard, the website, the Home Assistant embed) redraws a document that
 * reads the epoch every second, so the label keeps up. The AndroidX player draws it right when it
 * draws the document, but doesn't work this text out again as the clock moves: a launcher widget
 * shows the label of its last update.
 */
fun relativeTimeText(since: RemoteInt): RemoteString {
  val age = max(RemoteTimeVariables.epochSecond - since, 0.ri)
  val minutes = (age + 30) / 60
  val hours = (age + 1800) / 3600
  val days = (age + 43200) / 86400
  val text =
    age
      .isLessThan(45.ri)
      .select(
        "just now".rs,
        minutes
          .isLessThan(45.ri)
          .select(
            minutes.toPaddedRemoteString(1) + " min ago",
            hours
              .isLessThan(22.ri)
              .select(
                hours.toPaddedRemoteString(1) + " h ago",
                days
                  .isLessThan(7.ri)
                  .select(
                    days
                      .isEqualTo(1.ri)
                      .select("1 day ago".rs, days.toPaddedRemoteString(1) + " days ago"),
                    dateText(since),
                  ),
              ),
          ),
      )
  return since.isGreaterThan(0.ri).select(text, "".rs)
}

/**
 * [seconds] (Unix) as `20 Apr 2026` in the player's time zone: the days since the epoch turned into
 * a civil date with whole numbers only (Howard Hinnant's `civil_from_days`).
 *
 * The player only knows its UTC offset now, not the one in force at [seconds], so across a
 * daylight-saving change a time within the change (an hour) of midnight shows the neighbouring day.
 * That only reaches the date line, which is for changes a week old or more; the offset at an
 * arbitrary past time would need a value no host gets from Home Assistant.
 */
private fun dateText(seconds: RemoteInt): RemoteString {
  val day = (seconds + RemoteTimeVariables.utcOffsetSeconds) / SECONDS_PER_DAY
  val z = day + 719468
  val era = z / 146097
  val dayOfEra = z - era * 146097
  val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365
  val dayOfYear = dayOfEra - (yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100)
  val shifted = (dayOfYear * 5 + 2) / 153
  val dayOfMonth = dayOfYear - (shifted * 153 + 2) / 5 + 1
  // March-based month (0 = March) to January-based (0 = January).
  val month = shifted.isLessThan(10.ri).select(shifted + 2, shifted - 10)
  val year = yearOfEra + era * 400 + month.isLessThan(2.ri).select(1.ri, 0.ri)
  val name = MONTHS.rs.substring(month * 3, month * 3 + 3)
  return dayOfMonth.toPaddedRemoteString(1) + " " + name + " " + year.toPaddedRemoteString(1)
}

private const val SECONDS_PER_DAY = 86400
private const val MONTHS = "JanFebMarAprMayJunJulAugSepOctNovDec"
