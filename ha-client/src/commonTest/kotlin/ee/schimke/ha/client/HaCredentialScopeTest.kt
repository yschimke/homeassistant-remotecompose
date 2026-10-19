package ee.schimke.ha.client

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HaCredentialScopeTest {
  @Test
  fun sameOrigin() {
    assertTrue(haBearerAllowed("https://ha.example", "https://ha.example/api/image_proxy/x"))
    assertTrue(haBearerAllowed("http://ha.local:8123", "http://ha.local:8123/api/image/serve/a"))
    assertTrue(haBearerAllowed("https://HA.Example/", "https://ha.example/api/x"))
  }

  @Test
  fun differentPortOnTheSameHost() {
    assertFalse(haBearerAllowed("http://ha.local:8123", "http://ha.local:8080/x"))
    assertFalse(haBearerAllowed("https://ha.example", "https://ha.example:8443/x"))
    assertFalse(haBearerAllowed("http://ha.local:8123", "http://ha.local/x"))
  }

  @Test
  fun defaultPortsNormalize() {
    assertTrue(haBearerAllowed("https://ha.example", "https://ha.example:443/x"))
    assertTrue(haBearerAllowed("https://ha.example:443", "https://ha.example/x"))
    assertTrue(haBearerAllowed("http://ha.local", "http://ha.local:80/x"))
  }

  @Test
  fun httpInstanceUpgradesToHttpsOnlyOnTheSamePort() {
    assertTrue(haBearerAllowed("http://ha.local:8123", "https://ha.local:8123/x"))
    // 80 and 443 are different ports, so a default-port upgrade is another service.
    assertFalse(haBearerAllowed("http://ha.local", "https://ha.local/x"))
  }

  @Test
  fun httpsInstanceNeverSendsOverHttp() {
    assertFalse(haBearerAllowed("https://ha.example:8123", "http://ha.example:8123/x"))
    assertFalse(haBearerAllowed("https://ha.example", "http://ha.example:443/x"))
    assertFalse(haBearerAllowed("https://ha.example", "http://ha.example/x"))
  }

  @Test
  fun differentHost() {
    assertFalse(haBearerAllowed("https://ha.example", "https://cdn.example/icon.png"))
    assertFalse(haBearerAllowed("https://ha.example", "https://api.ha.example/icon.png"))
    assertFalse(haBearerAllowed("https://ha.example", "https://ha.example@evil.example/x"))
    assertFalse(haBearerAllowed("https://ha.example", "//evil.example/x"))
  }

  @Test
  fun relativeUrlsResolveAgainstTheInstance() {
    assertTrue(haBearerAllowed("https://ha.example", "/api/image_proxy/camera.door?token=t"))
    assertTrue(haBearerAllowed("http://ha.local:8123/", "/api/image/serve/a/512x512"))
    assertTrue(haBearerAllowed("https://ha.example", "//ha.example/api/x"))
  }

  @Test
  fun refusesOtherSchemesAndNonsense() {
    assertFalse(haBearerAllowed("https://ha.example", "content://media/1"))
    assertFalse(haBearerAllowed("https://ha.example", "data:image/png;base64,AAAA"))
    assertFalse(haBearerAllowed("ftp://ha.example", "ftp://ha.example/x"))
    assertFalse(haBearerAllowed("", "https://ha.example/x"))
  }
}
