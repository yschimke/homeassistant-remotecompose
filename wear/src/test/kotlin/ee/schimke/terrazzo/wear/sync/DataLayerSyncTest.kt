package ee.schimke.terrazzo.wear.sync

import ee.schimke.terrazzo.wearsync.proto.EntityDelta
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import ee.schimke.terrazzo.wearsync.proto.LiveValues
import ee.schimke.terrazzo.wearsync.proto.StreamUpdate
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

  @Test
  fun delayedStreamDoesNotRollBackAPersistedSnapshot() {
    val stored = values("on", at = 200)
    val frame = StreamUpdate(listOf(EntityDelta("light.kitchen", EntityValue(state = "off"))), 100)
    assertNull(mergeStreamUpdate(stored, frame))
    assertNull(mergeStreamUpdate(stored, frame.copy(capturedAtMs = 200)))
  }

  @Test
  fun freshStreamPreservesOtherEntitiesAndAdvancesThePersistedTime() {
    val frame = StreamUpdate(listOf(EntityDelta("sensor.temp", EntityValue(state = "21.5"))), 300)
    val merged = mergeStreamUpdate(values("on", at = 200), frame)!!
    assertEquals("on", merged.values["light.kitchen"]?.state)
    assertEquals("21.5", merged.values["sensor.temp"]?.state)
    assertEquals(300L, merged.capturedAtMs)
    // A cold read after restarting must not restore the earlier batched DataItem.
    assertNull(newerThan(values("off", at = 250), merged, LiveValues::capturedAtMs))
  }

  @Test
  fun firstStreamCanStartWithoutADataItem() {
    val frame = StreamUpdate(listOf(EntityDelta("light.kitchen", EntityValue(state = "on"))), 100)
    assertEquals(values("on", at = 100), mergeStreamUpdate(null, frame))
  }
}
