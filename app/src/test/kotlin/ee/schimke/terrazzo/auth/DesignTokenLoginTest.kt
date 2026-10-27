package ee.schimke.terrazzo.auth

import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/** Settings names who a design server token is for, from the token alone. */
class DesignTokenLoginTest {
  private fun token(payload: String) =
    Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".signature"

  @Test
  fun aDeviceTokenNamesItsLogin() {
    assertEquals("yschimke", designTokenLogin(token("device|yschimke|1824624381643")))
  }

  @Test
  fun anythingElseNamesNobody() {
    assertNull(designTokenLogin("pasted-from-somewhere"))
    assertNull(designTokenLogin(token("session|yschimke|1")))
    assertNull(designTokenLogin(token("device||1")))
  }
}
