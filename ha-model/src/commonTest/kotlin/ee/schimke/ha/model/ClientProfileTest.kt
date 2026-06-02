package ee.schimke.ha.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClientProfileTest {

  @Test
  fun wireFormIsLowercaseName() {
    assertEquals("phone", ClientProfile.Phone.wire)
    assertEquals("wear", ClientProfile.Wear.wire)
    assertEquals("mono", ClientProfile.Mono.wire)
  }

  @Test
  fun everyProfileRoundTripsThroughItsWireForm() {
    ClientProfile.entries.forEach { profile ->
      assertEquals(profile, ClientProfile.parse(profile.wire), "round-trip for $profile")
    }
  }

  @Test
  fun parseIsCaseInsensitive() {
    assertEquals(ClientProfile.Tv, ClientProfile.parse("TV"))
    assertEquals(ClientProfile.Glance, ClientProfile.parse("Glance"))
  }

  @Test
  fun parseReturnsNullForNullOrUnknown() {
    assertNull(ClientProfile.parse(null))
    assertNull(ClientProfile.parse(""))
    assertNull(ClientProfile.parse("desktop"))
  }
}
