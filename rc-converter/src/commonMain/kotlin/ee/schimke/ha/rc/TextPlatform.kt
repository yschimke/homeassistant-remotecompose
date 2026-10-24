package ee.schimke.ha.rc

import kotlinx.datetime.LocalDateTime

/*
 * What converters need from the platform beyond common Kotlin. Android and the JVM use the JDK
 * (`src/jdkMain`), as they always have; the other targets use the JDK-equivalent implementations in
 * DecimalStrings and DateTimePatterns, which the host tests check against the JDK.
 */

/** A reentrant lock: real on threaded platforms, none where there is one thread (wasmJs). */
internal expect class PlatformLock() {
  fun <T> withLock(block: () -> T): T
}

/**
 * [block] run to completion on this thread, for the blocking capture APIs. wasmJs cannot block: its
 * callers use the suspending ones.
 */
internal expect fun <T> runCaptureBlocking(block: suspend () -> T): T

/** `"%.{decimals}f".format(Locale.ROOT, value)`. */
internal expect fun formatFixed(value: Double, decimals: Int): String

/** `BigDecimal.valueOf(value).stripTrailingZeros().scale()`, at least 0. */
internal expect fun decimalPlaces(value: Double): Int

/** `BigDecimal.valueOf(value).setScale(digits, HALF_UP)`, as `toPlainString()`. */
internal expect fun plainHalfUp(value: Double, digits: Int): String

/** `BigDecimal.valueOf(a).add(BigDecimal.valueOf(b)).toDouble()`: decimal, not binary, addition. */
internal expect fun decimalSum(a: Double, b: Double): Double

/**
 * [dateTime] in the `DateTimeFormatter` [pattern], in the default locale, or in US English when
 * [english]. Outside the JDK, always US English.
 */
internal expect fun formatDateTime(
  dateTime: LocalDateTime,
  pattern: String,
  english: Boolean,
): String
