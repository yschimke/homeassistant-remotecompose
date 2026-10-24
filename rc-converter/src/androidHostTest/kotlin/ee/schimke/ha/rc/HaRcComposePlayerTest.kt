package ee.schimke.ha.rc

import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.runtime.RcHostActionValue
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Host-contract adapters between the card hosts and the CMP `RcComposePlayer`. */
class HaRcComposePlayerTest {

  @Test
  fun liveBindingsBecomeNamedValuesOnTheIntChannelForBooleans() {
    val values = mutableMapOf<String, RcNamedValue>()
    val pushed = HashMap<String, Any?>()
    val snapshot =
      HaSnapshot(
        states =
          mapOf(
            "light.kitchen" to EntityState("light.kitchen", "on"),
            "sensor.temp" to EntityState("sensor.temp", "21.5"),
          )
      )

    pushSnapshotBindings(
      NamedValueWriter(values),
      setOf("light.kitchen", "sensor.temp"),
      snapshot,
      pushed,
    )

    assertEquals(RcNamedValue.Integer(1), values["light.kitchen.is_on"])
    assertEquals(RcNamedValue.Integer(0), values["sensor.temp.is_on"])
    assertEquals(RcNamedValue.FloatValue(21.5f), values["sensor.temp.numeric_state"])
    assertTrue(values["light.kitchen.state"] is RcNamedValue.Text)
  }

  @Test
  fun unchangedBindingsAreNotRewritten() {
    var writes = 0
    val writer =
      object : BindingWriter {
        override fun string(name: String, value: String) {
          writes++
        }

        override fun int(name: String, value: Int) {
          writes++
        }

        override fun float(name: String, value: Float) {
          writes++
        }
      }
    val pushed = HashMap<String, Any?>()
    val snapshot = HaSnapshot(states = mapOf("light.a" to EntityState("light.a", "off")))
    pushSnapshotBindings(writer, setOf("light.a"), snapshot, pushed)
    val first = writes
    pushSnapshotBindings(writer, setOf("light.a"), snapshot, pushed)
    assertTrue(first > 0)
    assertEquals(first, writes)
  }

  @Test
  fun onlyDeclaredNamesWithAMatchingTypeReachThePlayer() {
    val declared =
      mapOf(
        "USER:light.kitchen.is_on" to RcNamedVariable.INT_TYPE,
        "USER:light.kitchen.state" to RcNamedVariable.STRING_TYPE,
      )
    assertTrue(declared.accepts("light.kitchen.is_on", RcNamedValue.Integer(1)))
    assertTrue(declared.accepts("light.kitchen.state", RcNamedValue.Text("On")))
    // Not declared by this document: rc-player would throw on the write.
    assertFalse(declared.accepts("sensor.living_room.is_on", RcNamedValue.Integer(0)))
    // Declared, but as a different type.
    assertFalse(declared.accepts("light.kitchen.state", RcNamedValue.Integer(1)))
  }

  @Test
  fun hostActionPayloadsUnwrapToPlainValues() {
    assertEquals("{}", RcHostActionValue.TextValue("{}").toHostPayload())
    assertEquals(3, RcHostActionValue.IntegerValue(3).toHostPayload())
    assertEquals(1.5f, RcHostActionValue.FloatValue(1.5f).toHostPayload())
    assertNull(RcHostActionValue.None.toHostPayload())
  }

  @Test
  fun externalImagesExposeTheirUrlAndInlineImagesDoNot() {
    val url = "https://ha.local/api/camera_proxy/camera.door"
    val external =
      RcBitmapData(1, 64, 64, RcBitmapData.TYPE_PNG, RcBitmapData.ENCODING_URL, url.toByteArray())
    val inline =
      RcBitmapData(2, 1, 1, RcBitmapData.TYPE_PNG, RcBitmapData.ENCODING_INLINE, byteArrayOf(1))
    assertEquals(url, external.externalImageName())
    assertNull(inline.externalImageName())
  }

  @Test
  fun frozenClockPinsThePlayerTimeSource() {
    val now = ZonedDateTime.of(2026, 5, 5, 10, 8, 30, 0, ZoneId.of("Europe/London"))
    val source = FixedHaClock(now).asRcTimeSource()
    assertEquals(now.toInstant().toEpochMilli(), source.currentTimeMillis())
    val snapshot = source.snapshot(source.currentTimeMillis())
    assertEquals(2026, snapshot.year)
    assertEquals(5, snapshot.month)
    assertEquals(5, snapshot.dayOfMonth)
    assertEquals(10, snapshot.hour)
    assertEquals(8, snapshot.minute)
    assertEquals(30, snapshot.second)
    // 2026-05-05 is a Tuesday (ISO 2); London is on BST (+1h) in May.
    assertEquals(2, snapshot.isoDayOfWeek)
    assertEquals(3600, snapshot.offsetSeconds)
  }
}
