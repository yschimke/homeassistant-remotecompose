package ee.schimke.terrazzo.quest

import ee.schimke.terrazzo.room.RoomPin
import kotlin.test.Test
import kotlin.test.assertEquals

class QuestPinManagementTest {
  @Test
  fun movingAnEntityReplacesItsPreviousPinButKeepsOtherControls() {
    val lamp = RoomPin("wall|lamp-old|0|0", "https://ha.example", "light.lamp")
    val fan = RoomPin("wall|fan|0|0", "https://ha.example", "fan.ceiling")
    val movedLamp = lamp.copy(anchorId = "wall|lamp-new|0|0")

    assertEquals(listOf(fan, movedLamp), replaceQuestPin(listOf(lamp, fan), movedLamp))
  }
}
