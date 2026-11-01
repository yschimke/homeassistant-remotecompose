package ee.schimke.terrazzo.quest

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuestPlatformTest {
  @Test
  fun roomMappingUsesTheAndroid14PathOnly() {
    assertFalse(supportsQuestRoomMapping(33))
    assertTrue(supportsQuestRoomMapping(34))
    assertTrue(supportsQuestRoomMapping(35))
  }
}
