package ee.schimke.terrazzo.core.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.test.runTest

class HaAccessTokensTest {
  private val clock = TestTimeSource()

  private fun tokens(vararg minted: MintedAccessToken, onMint: () -> Unit = {}): HaAccessTokens {
    val queue = ArrayDeque(minted.toList())
    return HaAccessTokens(
      initialToken = "initial",
      initialLifetime = 30.minutes,
      mint = {
        onMint()
        queue.removeFirst()
      },
      timeSource = clock,
    )
  }

  @Test
  fun keepsTheTokenWhileFreshAndMintsOneAsItNearsExpiry() = runTest {
    var mints = 0
    val tokens = tokens(MintedAccessToken("second", 30.minutes)) { mints++ }

    clock += 28.minutes
    assertEquals("initial", tokens.get())
    assertEquals(0, mints)

    // Within the refresh margin of the 30 minute lifetime.
    clock += 1.minutes + 1.seconds
    assertEquals("second", tokens.get())
    assertEquals("second", tokens.current)
    assertEquals("second", tokens.get())
    assertEquals(1, mints)
  }

  @Test
  fun aTokenHaRejectedIsReplacedOnTheNextGet() = runTest {
    val tokens = tokens(MintedAccessToken("fresh"))
    assertEquals("initial", tokens.get())

    tokens.invalidate("initial")

    assertEquals("fresh", tokens.get())
    assertEquals("fresh", tokens.get())
  }

  @Test
  fun invalidatingAnOlderTokenKeepsTheCurrentOne() = runTest {
    val tokens = tokens(MintedAccessToken("fresh"), MintedAccessToken("unexpected"))
    tokens.invalidate("initial")
    assertEquals("fresh", tokens.get())

    tokens.invalidate("initial")

    assertEquals("fresh", tokens.get())
  }

  @Test
  fun aRefusedRefreshTokenSticksWithoutAnotherRoundTrip() = runTest {
    var mints = 0
    val tokens =
      HaAccessTokens(
        initialToken = "initial",
        mint = {
          mints++
          throw HaReauthRequiredException("invalid_grant")
        },
        timeSource = clock,
      )
    tokens.invalidate("initial")
    assertFalse(tokens.needsReauth.value)

    assertFailsWith<HaReauthRequiredException> { tokens.get() }
    assertTrue(tokens.needsReauth.value)
    assertFailsWith<HaReauthRequiredException> { tokens.get() }
    assertEquals(1, mints)
  }

  @Test
  fun aTransientRefreshFailureIsRetried() = runTest {
    var attempts = 0
    val tokens =
      HaAccessTokens(
        initialToken = null,
        mint = {
          if (++attempts == 1) error("offline")
          MintedAccessToken("fresh")
        },
        timeSource = clock,
      )

    assertFailsWith<IllegalStateException> { tokens.get() }
    assertFalse(tokens.needsReauth.value)
    assertEquals("fresh", tokens.get())
  }

  @Test
  fun aPinnedTokenIsReturnedEvenOnceRejected() = runTest {
    val tokens = HaAccessTokens("pinned", timeSource = clock)
    tokens.invalidate("pinned")
    clock += 2.minutes * 60

    assertFalse(tokens.canRefresh)
    assertEquals("pinned", tokens.get())
  }

  @Test
  fun refusedRefreshResponsesAreToldApartFromTransientOnes() {
    assertTrue(isRefreshTokenRejected(400, """{"error":"invalid_grant"}"""))
    assertTrue(isRefreshTokenRejected(400, """{"error":"invalid_request"}"""))
    assertTrue(isRefreshTokenRejected(401, ""))
    assertTrue(isRefreshTokenRejected(403, """{"error":"access_denied"}"""))
    assertFalse(isRefreshTokenRejected(400, "<html>Bad gateway page</html>"))
    assertFalse(isRefreshTokenRejected(500, """{"error":"invalid_grant"}"""))
    assertFalse(isRefreshTokenRejected(502, ""))
  }

  @Test
  fun reconnectBackoffDoublesToItsCapAndResets() {
    val backoff = ReconnectBackoff(initial = 2.seconds, max = 30.seconds)

    assertEquals(
      listOf(2.seconds, 4.seconds, 8.seconds, 16.seconds, 30.seconds, 30.seconds),
      List(6) { backoff.next() },
    )
    backoff.reset()
    assertEquals(2.seconds, backoff.next())
  }
}
