package ee.schimke.terrazzo

import ee.schimke.terrazzo.core.session.SessionConnectionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class MobileConnectionStatusTest {
  private class Presentation(
    scope: TestScope,
    initial: SessionConnectionStatus = SessionConnectionStatus.Disconnected,
  ) {
    val connection = MutableStateFlow(initial)
    val foreground = MutableStateFlow(true)
    val seen = mutableListOf<ConnectionStatus>()

    init {
      scope.backgroundScope.launch {
        mobileConnectionStatus(connection, foreground).collect { seen += it }
      }
    }
  }

  @Test
  fun coldStartupHasExactlyTwoSecondsToConnect() = runTest {
    val ui = Presentation(this)
    runCurrent()
    assertEquals(listOf(ConnectionStatus.Connecting), ui.seen)
    advanceTimeBy(1_999)
    runCurrent()
    assertEquals(ConnectionStatus.Connecting, ui.seen.last())
    advanceTimeBy(1)
    runCurrent()
    assertEquals(ConnectionStatus.Failed, ui.seen.last())
    ui.connection.value = SessionConnectionStatus.Connected
    runCurrent()
    assertEquals(ConnectionStatus.Connected, ui.seen.last())
  }

  @Test
  fun startupFailureThatRecoversWithinGraceNeverFlashesAnError() = runTest {
    val ui = Presentation(this, SessionConnectionStatus.Failed)
    runCurrent()
    advanceTimeBy(1_999)
    ui.connection.value = SessionConnectionStatus.Connected
    runCurrent()
    advanceTimeBy(10_000)
    runCurrent()
    assertEquals(listOf(ConnectionStatus.Connecting, ConnectionStatus.Connected), ui.seen)
  }

  @Test
  fun retryStateChangesDoNotExtendTheDeadline() = runTest {
    val ui = Presentation(this, SessionConnectionStatus.Connecting)
    runCurrent()
    advanceTimeBy(1_000)
    ui.connection.value = SessionConnectionStatus.Failed
    runCurrent()
    advanceTimeBy(500)
    ui.connection.value = SessionConnectionStatus.Connecting
    runCurrent()
    advanceTimeBy(500)
    runCurrent()
    assertEquals(ConnectionStatus.Failed, ui.seen.last())
    ui.connection.value = SessionConnectionStatus.Failed
    runCurrent()
    assertEquals(listOf(ConnectionStatus.Connecting, ConnectionStatus.Failed), ui.seen)
  }

  @Test
  fun backgroundCancelsDeadlineAndForegroundStartsFreshGrace() = runTest {
    val ui = Presentation(this)
    runCurrent()
    advanceTimeBy(1_500)
    ui.foreground.value = false
    runCurrent()
    advanceTimeBy(10_000)
    runCurrent()
    assertEquals(ConnectionStatus.Disconnected, ui.seen.last())
    assertFalse(ConnectionStatus.Failed in ui.seen)
    ui.connection.value = SessionConnectionStatus.Failed
    ui.foreground.value = true
    runCurrent()
    assertEquals(ConnectionStatus.Connecting, ui.seen.last())
    advanceTimeBy(1_999)
    runCurrent()
    assertEquals(ConnectionStatus.Connecting, ui.seen.last())
    advanceTimeBy(1)
    runCurrent()
    assertEquals(ConnectionStatus.Failed, ui.seen.last())
    ui.foreground.value = false
    runCurrent()
    ui.foreground.value = true
    runCurrent()
    assertEquals(ConnectionStatus.Connecting, ui.seen.last())
    ui.connection.value = SessionConnectionStatus.Connected
    runCurrent()
    assertEquals(ConnectionStatus.Connected, ui.seen.last())
  }

  @Test
  fun connectedSessionShowsCurrentStatusAndLaterDisconnectionGetsANewDeadline() = runTest {
    val ui = Presentation(this, SessionConnectionStatus.Connected)
    runCurrent()
    assertEquals(listOf(ConnectionStatus.Connected), ui.seen)
    ui.foreground.value = false
    runCurrent()
    ui.foreground.value = true
    runCurrent()
    assertEquals(ConnectionStatus.Connected, ui.seen.last())
    ui.connection.value = SessionConnectionStatus.Failed
    runCurrent()
    advanceTimeBy(1_000)
    ui.connection.value = SessionConnectionStatus.Connected
    runCurrent()
    ui.connection.value = SessionConnectionStatus.Disconnected
    runCurrent()
    advanceTimeBy(1_999)
    runCurrent()
    assertFalse(ConnectionStatus.Failed in ui.seen)
    advanceTimeBy(1)
    runCurrent()
    assertEquals(ConnectionStatus.Failed, ui.seen.last())
  }

  @Test
  fun changingTargetCancelsOldTimerAndStartsANewDeadline() = runTest {
    val old = MutableStateFlow(SessionConnectionStatus.Failed)
    val new = MutableStateFlow(SessionConnectionStatus.Connecting)
    val target = MutableStateFlow(old)
    val foreground = MutableStateFlow(true)
    val seen = mutableListOf<ConnectionStatus>()
    backgroundScope.launch {
      target.collectLatest {
        mobileConnectionStatus(it, foreground).collect { status -> seen += status }
      }
    }
    runCurrent()
    advanceTimeBy(1_500)
    target.value = new
    runCurrent()
    advanceTimeBy(500)
    runCurrent()
    assertFalse(ConnectionStatus.Failed in seen)
    old.value = SessionConnectionStatus.Connected
    runCurrent()
    assertEquals(ConnectionStatus.Connecting, seen.last())
    advanceTimeBy(1_500)
    runCurrent()
    assertEquals(ConnectionStatus.Failed, seen.last())
    new.value = SessionConnectionStatus.Connected
    runCurrent()
    assertEquals(ConnectionStatus.Connected, seen.last())
  }
}
