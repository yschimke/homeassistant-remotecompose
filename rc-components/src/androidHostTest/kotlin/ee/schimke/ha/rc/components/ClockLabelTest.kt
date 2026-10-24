package ee.schimke.ha.rc.components

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals

/** [parseClockLabel] reads a static clock label as `LocalTime.parse` did. */
class ClockLabelTest {
  @Test
  fun readsLabelsAsLocalTimeDoes() {
    val labels =
      listOf("00:00", "09:05", "23:59:59", "24:00", "9:05", "12:00 AM", "12:30 PM", "1:02:03 PM") +
        listOf("13:00 PM", "0:15 AM", "10:61", "noon", "", "11:59 pm", "07:08:09", "7:08 AM")
    for (use24Hour in listOf(true, false)) {
      val formatter = DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm[:ss]" else "h:mm[:ss] a")
      for (label in labels) {
        val expected = runCatching {
          LocalTime.parse(label, formatter)
        }
          .getOrNull()
          ?.let { ClockLabelTime(it.hour, it.minute, it.second) }
        assertEquals(expected, parseClockLabel(label, use24Hour), "$label (24h: $use24Hour)")
      }
    }
  }
}
