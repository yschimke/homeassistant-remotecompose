package ee.schimke.ha.rc

import ee.schimke.ha.model.EntityState
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.units.indriya.unit.Units
import kotlin.test.Test
import kotlin.test.assertEquals

class HaStateFormatTest {

    @Test
    fun trimsTrailingZerosForNumericState() {
        val state = EntityState("sensor.fast_com_download", "73.0000")

        assertEquals("73", formatState(state))
    }

    @Test
    fun trimsTrailingZerosAndKeepsUnit() {
        val state = EntityState(
            "sensor.fast_com_download",
            "73.1200",
            attributes = buildJsonObject { put("unit_of_measurement", "Mbit/s") },
        )

        assertEquals("73.12 Mbit/s", formatState(state))
    }

    @Test
    fun roundsLongDecimalsToTwoPlaces() {
        val state = EntityState(
            "sensor.fast_com_download",
            "73.1299",
            attributes = buildJsonObject { put("unit_of_measurement", "Mbit/s") },
        )

        assertEquals("73.13 Mbit/s", formatState(state))
    }

    @Test
    fun dropsMaskedFractionSuffix() {
        val state = EntityState(
            "sensor.home_download",
            "551.XXXXXXXXXXXX",
            attributes = buildJsonObject { put("unit_of_measurement", "Mbit/s") },
        )

        assertEquals("551 Mbit/s", formatState(state))
    }

    @Test
    fun keepsWholeNumberHundredsIntact() {
        val state = EntityState("sensor.battery", "100")

        assertEquals("100", formatState(state))
    }

    @Test
    fun `numeric state includes HA-provided celsius unit`() {
        val entity =
            EntityState(
                entityId = "sensor.living_room_temperature",
                state = "21.7",
                attributes = buildJsonObject { put("unit_of_measurement", "°C") },
            )

        assertEquals("21.7 °C", formatState(entity))
    }

    @Test
    fun `numeric state includes HA-provided fahrenheit unit`() {
        val entity =
            EntityState(
                entityId = "sensor.attic_temperature",
                state = "71.1",
                attributes = buildJsonObject { put("unit_of_measurement", "°F") },
            )

        assertEquals("71.1 °F", formatState(entity))
    }

    @Test
    fun `indriya celsius unit uses single-codepoint symbol`() {
        assertEquals("℃", Units.CELSIUS.symbol)
    }

    @Test
    fun `value and unit formatter normalizes spacing`() {
        assertEquals("21 °C", formatValueWithUnit("21", "°C"))
        assertEquals("21 °F", formatValueWithUnit("21", "  °F  "))
    }

    @Test
    fun `value and unit formatter normalizes numeric values`() {
        assertEquals("73.13 Mbit/s", formatValueWithUnit("73.1299", "Mbit/s"))
        assertEquals("551 Mbit/s", formatValueWithUnit("551.XXXXXXXXXXXX", "Mbit/s"))
    }

    @Test
    fun `formatter works with units provided by indriya`() {
        assertEquals("21 ℃", formatValueWithUnit("21", Units.CELSIUS.symbol))
    }
}
