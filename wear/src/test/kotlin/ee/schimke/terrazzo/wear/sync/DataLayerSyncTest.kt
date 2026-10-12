package ee.schimke.terrazzo.wear.sync

import ee.schimke.terrazzo.wearsync.proto.EntityValue
import ee.schimke.terrazzo.wearsync.proto.LiveValues
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The widgets' Data Layer read keeps whichever copy of a value is newer. */
class DataLayerSyncTest {
  private fun values(state: String, at: Long) =
    LiveValues(values = mapOf("light.kitchen" to EntityValue(state = state)), capturedAtMs = at)

  @Test
  fun aNewerDataItemReplacesTheStoredCopy() {
    val synced = values("on", at = 200)
    assertEquals(synced, newerThan(synced, values("off", at = 100), LiveValues::capturedAtMs))
  }

  @Test
  fun aStreamDeltaNewerThanTheDataItemIsKept() {
    // The running app merged a delta after the phone last wrote the DataItem.
    assertNull(newerThan(values("off", at = 100), values("on", at = 200), LiveValues::capturedAtMs))
  }

  @Test
  fun anythingSyncedBeatsNothingStored() {
    val synced = values("on", at = 1)
    assertEquals(synced, newerThan(synced, null, LiveValues::capturedAtMs))
    assertNull(newerThan(null, values("on", at = 1), LiveValues::capturedAtMs))
  }
}
