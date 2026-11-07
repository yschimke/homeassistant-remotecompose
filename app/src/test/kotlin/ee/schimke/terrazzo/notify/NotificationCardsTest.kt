package ee.schimke.terrazzo.notify

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationCardsTest {
  @Test
  fun belowApi36ThePlatformPlayerCantPlayACard() {
    assertFalse(NotificationCards.isSupported(35))
  }

  /** Android 17 included: the card goes wrapped ([NotificationCards.wrapForNotification]). */
  @Test
  fun fromApi36TheNotificationCarriesTheLiveCard() {
    assertTrue(NotificationCards.isSupported(36))
    assertTrue(NotificationCards.isSupported(37))
  }
}
