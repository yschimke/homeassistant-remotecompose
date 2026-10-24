package ee.schimke.ha.rc

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.number

/**
 * `DateTimeFormatter.ofPattern(pattern, Locale.US).format(dateTime)` for the targets without the
 * JDK, for the pattern letters converters use: `y`, `M`, `d`, `E`, `a`, `H`, `h`, `m`, `s`, and
 * `'quoted'` literals. The host tests check it against the JDK.
 */
internal object DateTimePatterns {
  fun format(dateTime: LocalDateTime, pattern: String): String = buildString {
    var i = 0
    while (i < pattern.length) {
      val c = pattern[i]
      if (c == '\'') {
        val end = pattern.indexOf('\'', i + 1).let { if (it < 0) pattern.length else it }
        if (end == i + 1) append('\'') else append(pattern, i + 1, end)
        i = end + 1
        continue
      }
      if (!c.isLetter()) {
        append(c)
        i++
        continue
      }
      var count = 1
      while (i + count < pattern.length && pattern[i + count] == c) count++
      i += count
      append(field(dateTime, c, count))
    }
  }

  private fun field(t: LocalDateTime, letter: Char, count: Int): String =
    when (letter) {
      'y' -> if (count == 2) (t.year % 100).pad(2) else t.year.pad(count)
      'M' ->
        when {
          count >= 4 -> Months[t.month.number - 1]
          count == 3 -> Months[t.month.number - 1].take(3)
          else -> t.month.number.pad(count)
        }
      'd' -> t.day.pad(count)
      'E' -> Days[t.dayOfWeek.ordinal].let { if (count >= 4) it else it.take(3) }
      'a' -> if (t.hour < 12) "AM" else "PM"
      'H' -> t.hour.pad(count)
      'h' -> (t.hour % 12).let { if (it == 0) 12 else it }.pad(count)
      'm' -> t.minute.pad(count)
      's' -> t.second.pad(count)
      else -> throw IllegalArgumentException("Unsupported pattern letter: $letter")
    }

  private fun Int.pad(width: Int): String = toString().padStart(width, '0')

  private val Months =
    listOf(
      "January",
      "February",
      "March",
      "April",
      "May",
      "June",
      "July",
      "August",
      "September",
      "October",
      "November",
      "December",
    )

  private val Days =
    listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
}
