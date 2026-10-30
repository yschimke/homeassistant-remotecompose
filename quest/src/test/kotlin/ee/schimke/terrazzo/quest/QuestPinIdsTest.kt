package ee.schimke.terrazzo.quest

import ee.schimke.terrazzo.room.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuestPinIdsTest {

  @Test
  fun aWallPinRoundTrips() {
    val place = QuestPinPlace.OnWall("6f1c0c1e-0000-4000-8000-000000000001", 0.25f, -0.4f)
    assertEquals(place, QuestPinPlace.decode(place.encode()))
  }

  @Test
  fun anAirPinRoundTrips() {
    val place = QuestPinPlace.InRoom("room-uuid", Vec3(0.5f, 1.6f, -0.8f), Vec3(0f, 0f, 1f))
    assertEquals(place, QuestPinPlace.decode(place.encode()))
  }

  @Test
  fun anAndroidXrAnchorIdIsNotAQuestPin() {
    assertNull(QuestPinPlace.decode("6f1c0c1e-0000-4000-8000-000000000001"))
    assertNull(QuestPinPlace.decode("wall|only-two"))
    assertNull(QuestPinPlace.decode("room|r|x|1|2|3|4|5"))
  }
}
