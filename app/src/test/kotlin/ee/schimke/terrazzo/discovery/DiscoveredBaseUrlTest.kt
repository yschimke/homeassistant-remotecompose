package ee.schimke.terrazzo.discovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DiscoveredBaseUrlTest {
  @Test
  fun prefersTheAdvertisedInternalUrlThenBaseUrl() {
    assertEquals(
      "http://ha.lan:8123",
      discoveredBaseUrl("http://ha.lan:8123", "https://ha.example", "192.168.1.5", 8123),
    )
    assertEquals(
      "https://ha.example",
      discoveredBaseUrl(null, "https://ha.example", "192.168.1.5", 8123),
    )
  }

  @Test
  fun unsetUrlsAdvertisedAsEmptyStringsFallBackToTheAddress() {
    assertEquals("http://192.168.1.5:8123", discoveredBaseUrl("", "", "192.168.1.5", 8123))
    assertEquals("http://192.168.1.5:8123", discoveredBaseUrl(" ", null, "192.168.1.5", 8123))
  }

  @Test
  fun ipv6AddressesAreBracketedWithTheirZoneEscaped() {
    assertEquals("http://[fd00::5]:8123", discoveredBaseUrl(null, null, "fd00::5", 8123))
    assertEquals(
      "http://[fe80::1%25wlan0]:8123",
      discoveredBaseUrl(null, null, "fe80::1%wlan0", 8123),
    )
  }

  @Test
  fun noUrlAndNoAddressIsNotAnInstance() {
    assertNull(discoveredBaseUrl(null, "", null, 8123))
  }
}
