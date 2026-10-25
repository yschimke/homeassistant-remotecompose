package ee.schimke.terrazzo.notify

import kotlin.test.Test
import kotlin.test.assertEquals

class TestNotificationTest {
  @Test
  fun everyTestNotificationSharesOneIdSoASecondReplacesTheFirst() {
    assertEquals(
      HaNotifications.idOf(TestNotification.message("light.kitchen")),
      HaNotifications.idOf(TestNotification.message(null)),
    )
    assertEquals("tag:${TestNotification.TAG}", TestNotification.message(null).dedupeKey)
  }
}
