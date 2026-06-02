package ee.schimke.terrazzo.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Unit coverage for [HaAuthService.parseTokenResponse]. The refresh call itself talks to the live
 * OkHttp engine and gets covered end-to-end via instrumented tests; this verifies the JSON shape we
 * expect from HA's `/auth/token` endpoint.
 */
class HaAuthServiceTokenParseTest {

  @Test
  fun parses_access_refresh_and_expires() {
    val body =
      """
      {
        "access_token": "abc",
        "refresh_token": "xyz",
        "expires_in": 1800,
        "token_type": "Bearer"
      }
      """
        .trimIndent()

    val tokens = HaAuthService.parseTokenResponse(body)

    assertEquals("abc", tokens.accessToken)
    assertEquals("xyz", tokens.refreshToken)
    assertEquals(1800L, tokens.expiresInSeconds)
    assertEquals("Bearer", tokens.tokenType)
  }

  @Test
  fun missing_refresh_token_is_null() {
    // HA usually doesn't rotate the refresh token on the
    // `grant_type=refresh_token` exchange, so the field is often absent.
    val body =
      """
      { "access_token": "abc", "expires_in": 1800, "token_type": "Bearer" }
      """
        .trimIndent()

    val tokens = HaAuthService.parseTokenResponse(body)

    assertEquals("abc", tokens.accessToken)
    assertNull(tokens.refreshToken)
  }

  @Test
  fun unknown_fields_are_ignored() {
    val body =
      """
      { "access_token": "abc", "ha_auth_provider": { "id": "homeassistant" } }
      """
        .trimIndent()

    val tokens = HaAuthService.parseTokenResponse(body)

    assertEquals("abc", tokens.accessToken)
  }

  @Test
  fun garbage_body_throws() {
    assertFailsWith<Exception> { HaAuthService.parseTokenResponse("not json") }
  }
}
