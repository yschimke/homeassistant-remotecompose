package ee.schimke.terrazzo.discovery

import kotlin.test.Test
import kotlin.test.assertEquals

/** How the "sign in again" line on the connect screen names the restored Home Assistant. */
class DisplayHostTest {
  @Test
  fun dropsTheSchemeAndKeepsAnExplicitPort() {
    assertEquals("192.168.1.50:8123", displayHost("http://192.168.1.50:8123"))
    assertEquals("homeassistant.local:8123", displayHost("http://homeassistant.local:8123"))
  }

  @Test
  fun aUrlWithNoPortIsJustTheHost() {
    assertEquals("ha.example.com", displayHost("https://ha.example.com"))
    assertEquals("ha.example.com", displayHost("https://ha.example.com/"))
  }

  @Test
  fun somethingThatIsNotAUrlIsShownAsItIs() {
    assertEquals("homeassistant", displayHost("homeassistant"))
  }
}
