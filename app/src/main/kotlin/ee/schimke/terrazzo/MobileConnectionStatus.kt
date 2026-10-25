package ee.schimke.terrazzo

import ee.schimke.terrazzo.core.session.SessionConnectionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal const val CONNECTION_ERROR_GRACE_MS = 2_000L

enum class ConnectionStatus(val label: String) {
  Failed("Failed"),
  Connecting("Connecting"),
  Connected("Connected"),
  Disconnected("Paused"),
}

/** Presentation only: retries and the underlying socket state remain owned by the session. */
internal fun mobileConnectionStatus(
  connection: StateFlow<SessionConnectionStatus>,
  foreground: Flow<Boolean>,
): Flow<ConnectionStatus> = channelFlow {
  foreground.distinctUntilChanged().collectLatest { active ->
    if (!active) {
      send(ConnectionStatus.Disconnected)
      return@collectLatest
    }
    // A retry's Failed -> Connecting transition must not restart the deadline indefinitely.
    connection
      .map { it == SessionConnectionStatus.Connected }
      .distinctUntilChanged()
      .collectLatest { connected ->
        if (connected) {
          send(ConnectionStatus.Connected)
        } else {
          send(ConnectionStatus.Connecting)
          delay(CONNECTION_ERROR_GRACE_MS)
          send(ConnectionStatus.Failed)
        }
      }
  }
}
