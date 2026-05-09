package ee.schimke.ha.rc

import ee.schimke.ha.model.EntityState
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
}
