package ee.schimke.ha.rc.cards

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.FixedHaClock
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * A clock card's design template reads its time-derived bindings — the date line and another zone's
 * offset — from the injected clock, the same one [ClockCardConverter.Render] reads through
 * `LocalHaClock`, never from the wall clock.
 */
class ClockCardConverterTest {

  private val converter = ClockCardConverter()

  private fun clock() =
    CardConfig(
      type = "clock",
      raw =
        buildJsonObject {
          put("type", "clock")
          put("title", "Kitchen clock")
        },
    )

  private val lastSecond = ZonedDateTime.of(2026, 5, 5, 23, 59, 59, 0, ZoneOffset.UTC)

  /** The direct render's date line for [now]: `ClockCardConverter`'s own pattern. */
  private fun dateLine(now: ZonedDateTime) = now.format(DateTimeFormatter.ofPattern("EEE d MMM"))

  @Test
  fun dateBindingReadsTheInjectedClock() {
    val bindings = converter.designBindings(clock(), HaSnapshot(), FixedHaClock(lastSecond))

    assertEquals(dateLine(lastSecond), bindings.strings["card.date"])
    // One second on is the next day: the binding follows the clock, not the wall clock.
    val midnight = lastSecond.plusSeconds(1)
    assertEquals(
      dateLine(midnight),
      converter.designBindings(clock(), HaSnapshot(), FixedHaClock(midnight)).strings["card.date"],
    )
  }

  @Test
  fun templatePlaybackPushesTheDateOfTheClockItIsPlayedUnder() {
    val card = clock()
    val playback = CardDesignTemplates.playback(card, ByteArray(0), defaultRegistry())

    val bindings = playback.bindings(HaSnapshot(), FixedHaClock(lastSecond))

    assertEquals(dateLine(lastSecond), bindings.strings["card.date"])
  }

  @Test
  fun zoneOffsetIsTheCardZoneLessTheClockZone() {
    val tokyo =
      CardConfig(
        type = "clock",
        raw =
          buildJsonObject {
            put("type", "clock")
            put("time_zone", "Asia/Tokyo")
          },
      )

    val fromUtc = converter.designBindings(tokyo, HaSnapshot(), FixedHaClock(lastSecond))
    val fromTokyo =
      converter.designBindings(
        tokyo,
        HaSnapshot(),
        FixedHaClock(lastSecond.withZoneSameInstant(ZoneId.of("Asia/Tokyo"))),
      )

    assertEquals(540f, fromUtc.floats["card.zone_offset_minutes"])
    assertEquals(0f, fromTokyo.floats["card.zone_offset_minutes"])
    // Tokyo's date at 23:59:59 UTC is already the next day.
    assertEquals(
      dateLine(lastSecond.withZoneSameInstant(ZoneId.of("Asia/Tokyo"))),
      fromUtc.strings["card.date"],
    )
  }

  @Test
  fun stackPassesTheClockToItsClockChild() {
    val stack =
      CardConfig(
        type = "vertical-stack",
        raw =
          buildJsonObject {
            put("type", "vertical-stack")
            putJsonArray("cards") {
              addJsonObject {
                put("type", "clock")
                put("title", "Kitchen clock")
              }
            }
          },
      )
    val playback = CardDesignTemplates.playback(stack, ByteArray(0), defaultRegistry())

    val bindings = playback.bindings(HaSnapshot(), FixedHaClock(lastSecond))

    assertEquals(dateLine(lastSecond), bindings.strings["card.c0.date"])
  }
}
