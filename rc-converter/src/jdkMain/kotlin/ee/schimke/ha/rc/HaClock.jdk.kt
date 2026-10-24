package ee.schimke.ha.rc

import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.time.toKotlinInstant
import kotlinx.datetime.toKotlinTimeZone

/** A [FixedHaClock] at [zoned]'s instant, in its zone. */
fun FixedHaClock(zoned: ZonedDateTime): FixedHaClock = FixedHaClock(zoned.toInstant(), zoned.zone)

/** A [FixedHaClock] at [instant], in [zone]. */
fun FixedHaClock(instant: java.time.Instant, zone: ZoneId = ZoneOffset.UTC): FixedHaClock =
  FixedHaClock(instant.toKotlinInstant(), zone.toKotlinTimeZone())
