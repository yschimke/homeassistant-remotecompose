package ee.schimke.terrazzo.room

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoomPinsTest {

  @Test
  fun pinsRoundTrip() {
    val pins =
      listOf(
        RoomPin("6f1c0c1e-0000-4000-8000-000000000001", "https://ha.local", "climate.heater"),
        RoomPin("6f1c0c1e-0000-4000-8000-000000000002", "https://ha.local", "media_player.tv"),
      )
    assertEquals(pins, decodeRoomPins(encodeRoomPins(pins)))
  }

  @Test
  fun nothingOrGarbageDecodesToNoPins() {
    assertEquals(emptyList(), decodeRoomPins(null))
    assertEquals(emptyList(), decodeRoomPins("not json"))
  }

  @Test
  fun theNearestPinWithinReachNamesTheRoom() {
    val placed = listOf("climate.heater" to 2.5f, "media_player.tv" to 1.2f, "light.hall" to 9f)
    assertEquals("media_player.tv", nearestPinnedEntity(placed))
  }

  @Test
  fun pinsOutOfReachNameNoRoom() {
    assertNull(nearestPinnedEntity(listOf("light.hall" to 9f)))
    assertNull(nearestPinnedEntity(emptyList()))
  }
}
