package ee.schimke.ha.rc

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime

/**
 * The formatting the targets without a JDK use (DecimalStrings, DateTimePatterns) matches the
 * JDK's, which Android and the JVM use: one card's text, whichever platform captured it.
 */
class JdkFreeFormattingTest {
  private val values: List<Double> = buildList {
    addAll(
      listOf(0.0, -0.0, 0.5, 1.5, 2.5, -0.5, 0.05, 0.15, 0.25, 0.35, 1.005, 2.675, 1.0E-5, 1.0E-7)
    )
    addAll(listOf(100.0, 123.456, -123.456, 9.995, 99.95, 0.1 + 0.2, 1e15, 12345678.9, -0.01))
    addAll(listOf(20.5, 21.0, 0.1, 0.01, 7.0, 35.0, 33.333332061767578, 12.5, 99.99999))
    val random = Random(7)
    repeat(2000) { add((random.nextDouble() - 0.5) * 10.0.pow(random.nextInt(-4, 7))) }
    repeat(500) { add(random.nextInt(-100_000, 100_000) / 1000.0) }
  }

  @Test
  fun formatFixedMatchesTheFormatter() {
    for (v in values) for (places in 0..4) {
      assertEquals(
        "%.${places}f".format(Locale.ROOT, v),
        DecimalStrings.formatFixed(v, places),
        "$v",
      )
    }
  }

  @Test
  fun decimalPlacesMatchBigDecimal() {
    for (v in values) {
      val expected = BigDecimal.valueOf(v).stripTrailingZeros().scale().coerceAtLeast(0)
      assertEquals(expected, DecimalStrings.decimalPlaces(v), "$v")
    }
  }

  @Test
  fun halfUpMatchesBigDecimal() {
    for (v in values) for (places in 0..3) {
      val expected = BigDecimal.valueOf(v).setScale(places, RoundingMode.HALF_UP).toPlainString()
      assertEquals(expected, DecimalStrings.plainHalfUp(v, places), "$v")
    }
  }

  @Test
  fun sumMatchesBigDecimal() {
    for ((i, a) in values.withIndex()) {
      val b = values[(i * 31 + 7) % values.size]
      val expected = BigDecimal.valueOf(a).add(BigDecimal.valueOf(b)).toDouble()
      assertEquals(expected, DecimalStrings.sum(a, b), "$a + $b")
      assertEquals(
        BigDecimal.valueOf(a).subtract(BigDecimal.valueOf(b)).toDouble(),
        DecimalStrings.sum(a, -b),
      )
    }
  }

  @Test
  fun patternsMatchDateTimeFormatter() {
    val patterns =
      listOf(
        "HH:mm",
        "HH:mm:ss",
        "h:mm a",
        "h:mm:ss a",
        "EEE d MMM",
        "d MMM HH:mm",
        "EEEE, d MMMM yyyy",
        "yy-MM-dd",
      )
    val random = Random(11)
    repeat(400) {
      val t =
        LocalDateTime(
          random.nextInt(1990, 2040),
          random.nextInt(1, 13),
          random.nextInt(1, 29),
          random.nextInt(0, 24),
          random.nextInt(0, 60),
          random.nextInt(0, 60),
        )
      for (pattern in patterns) {
        val expected =
          DateTimeFormatter.ofPattern(pattern, Locale.US).format(t.toJavaLocalDateTime())
        assertEquals(expected, DateTimePatterns.format(t, pattern), "$t $pattern")
      }
    }
  }

  private fun Double.pow(n: Int): Double = Math.pow(this, n.toDouble())
}
