package ee.schimke.terrazzo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.core.session.SessionConnectionStatus
import kotlinx.coroutines.flow.flowOf

@Composable
internal fun rememberMobileConnectionStatus(session: HaSession): ConnectionStatus {
  val owner = LocalLifecycleOwner.current
  val lifecycleState by owner.lifecycle.currentStateAsState()
  val foreground = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
  // Reset synchronously: neither a previous target's error nor the last foreground's error may
  // paint for one frame before the producer starts. A connected session needs no loading frame.
  return key(session, owner, foreground) {
    val initial = remember {
      when {
        !foreground -> ConnectionStatus.Disconnected
        session.connectionStatus.value == SessionConnectionStatus.Connected ->
          ConnectionStatus.Connected
        else -> ConnectionStatus.Connecting
      }
    }
    val status by
      produceState(initial) {
        mobileConnectionStatus(session.connectionStatus, flowOf(foreground)).collect { value = it }
      }
    status
  }
}
