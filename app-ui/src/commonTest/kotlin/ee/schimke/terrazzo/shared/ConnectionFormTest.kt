package ee.schimke.terrazzo.shared

import kotlin.test.*

class ConnectionFormTest {
  @Test
  fun requiresHttpsWithoutEmbeddedCredentials() {
    assertTrue(validServerUrl("https://home.example.com"))
    assertTrue(validServerUrl("https://192.168.1.5:8123"))
    listOf(
        "http://192.168.1.5:8123",
        "https://",
        "file:///tmp/home",
        "https://bad host",
        "https://user:token@home.example.com",
        "https://home.example.com?token=x",
        "https://home.example.com#token",
      )
      .forEach { assertFalse(validServerUrl(it), it) }
  }
}
