package ee.schimke.terrazzo.glasses

import ee.schimke.terrazzo.room.DemoHouse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GlassesRoomTest {
  @AfterTest
  fun reset() {
    GlassesRoom.pick(null)
    if (GlassesRoom.snapshot != DemoHouse.snapshot) {
      DemoHouse.snapshot.states.forEach { (id, state) ->
        if (GlassesRoom.snapshot.states[id]?.state != state.state) GlassesRoom.toggle(id)
      }
    }
  }

  @Test
  fun pickingARoomShowsItsControls() {
    GlassesRoom.pick(DemoHouse.kitchen.id)
    assertEquals(DemoHouse.kitchen.id, GlassesRoom.state.controls?.area?.id)
    val controls = GlassesRoom.state.controls
    assertEquals(
      listOf(DemoHouse.KITCHEN_LIGHTS),
      controls?.let { it.primary + it.more }?.map { it.entityId },
    )
  }

  @Test
  fun aToggleChangesWhatBothScreensRead() {
    GlassesRoom.toggle(DemoHouse.FAN)
    assertEquals("on", GlassesRoom.snapshot.states[DemoHouse.FAN]?.state)
    GlassesRoom.toggle(DemoHouse.FAN)
    assertEquals("off", GlassesRoom.snapshot.states[DemoHouse.FAN]?.state)
  }
}
