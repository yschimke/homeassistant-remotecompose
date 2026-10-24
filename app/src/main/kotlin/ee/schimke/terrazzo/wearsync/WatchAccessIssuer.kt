package ee.schimke.terrazzo.wearsync

import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.core.session.SessionConnectionStatus
import ee.schimke.terrazzo.wearsync.proto.WearAccess
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Gives each watch that asks a long-lived access token of its own, so it can read its cards' values
 * from Home Assistant when the phone has none to send it: off home Wi-Fi with the phone away, or
 * while the phone app isn't running.
 *
 * Each watch's token is named for it ([tokenName]) in the user's Home Assistant profile, where they
 * can see and delete it. Issuing one replaces that watch's earlier token, and signing out deletes
 * every watch's ([revokeAll]). A watch that asks while the phone has no connected session is
 * answered once one connects ([onConnected]); demo and offline sessions issue nothing.
 */
internal class WatchAccessIssuer(
  /** Sends [access] to the watch [nodeId] over MessageClient. */
  private val send: suspend (nodeId: String, access: WearAccess) -> Unit,
  /** The watch's name as the user knows it (the Wear OS node's display name), if it can be read. */
  private val nodeName: suspend (nodeId: String) -> String?,
  private val now: () -> Long = System::currentTimeMillis,
) {
  private val mutex = Mutex()

  /** Watches that asked while no session could issue a token. */
  private val waiting = LinkedHashSet<String>()

  /**
   * When each watch was last issued a token, so a watch asking in a loop isn't sent a new one each
   * time.
   */
  private val issuedAt = HashMap<String, Long>()

  /** The watch [nodeId] asked for a token: issue one on [session] if it's connected, else later. */
  suspend fun request(nodeId: String, session: HaSession?) {
    val connected = session?.takeIf {
      it.connectionStatus.value == SessionConnectionStatus.Connected
    }
    mutex.withLock { if (connected == null || !issue(nodeId, connected)) addWaiting(nodeId) }
  }

  /** [session] connected: answer the watches waiting for a token. */
  suspend fun onConnected(session: HaSession) {
    mutex.withLock {
      for (nodeId in waiting.toList()) if (issue(nodeId, session)) waiting.remove(nodeId)
    }
  }

  /**
   * Sign-out: delete every watch's token from Home Assistant, so a watch that kept a copy can't use
   * it. Waits up to [timeoutMs] for [session] to connect; a session that can't (Home Assistant
   * refused its sign-in) deletes nothing, and the user can delete the tokens from their profile.
   */
  suspend fun revokeAll(session: HaSession, timeoutMs: Long = REVOKE_TIMEOUT_MS) {
    mutex.withLock {
      waiting.clear()
      issuedAt.clear()
      withTimeoutOrNull(timeoutMs) {
        session.connectionStatus.first { it == SessionConnectionStatus.Connected }
        runCatching { session.deleteLongLivedTokens(::isWatchToken) }
          .onFailure { if (it is CancellationException) throw it }
      }
    }
  }

  /** Issues [nodeId] a token on [session]; false when it couldn't, so it's tried again later. */
  private suspend fun issue(nodeId: String, session: HaSession): Boolean {
    val last = issuedAt[nodeId]
    if (last != null && now() - last < REISSUE_AFTER_MS) return true
    val outcome = runCatching {
      val name = tokenName(nodeName(nodeId), nodeId)
      session.deleteLongLivedTokens { it == name }
      val token = session.createLongLivedToken(name, TOKEN_LIFESPAN_DAYS) ?: return false
      val external = runCatching {
        session.fetchInstanceConfig()?.externalUrl
      }
        .getOrNull()
        ?.trimEnd('/')
        ?.takeIf { it.isNotBlank() && it != session.baseUrl.trimEnd('/') }
      send(nodeId, WearAccess(session.baseUrl, external.orEmpty(), token, now()))
    }
    outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
    if (outcome.isSuccess) issuedAt[nodeId] = now()
    return outcome.isSuccess
  }

  private fun addWaiting(nodeId: String) {
    waiting += nodeId
    while (waiting.size > MAX_WAITING) waiting.remove(waiting.first())
  }

  companion object {
    /** Every watch token's name starts with this. */
    const val TOKEN_NAME_PREFIX = "Terrazzo Watch"

    /** A watch asking again within this long gets no new token: its last one is still good. */
    const val REISSUE_AFTER_MS = 10 * 60_000L

    /**
     * How long a watch token lasts; the watch asks for a new one when Home Assistant refuses it.
     */
    const val TOKEN_LIFESPAN_DAYS = 365

    private const val REVOKE_TIMEOUT_MS = 10_000L
    private const val MAX_WAITING = 8

    /**
     * The token's name in Home Assistant: the watch's own name, so the user can tell them apart,
     * and the end of its node id, so two watches of the same model don't share one (issuing for one
     * would delete the other's).
     */
    fun tokenName(watchName: String?, nodeId: String): String {
      val name = watchName?.trim()?.takeIf { it.isNotEmpty() }?.take(60)
      val id = nodeId.filter { it.isLetterOrDigit() }.takeLast(4)
      return listOfNotNull(TOKEN_NAME_PREFIX, name, id.takeIf { it.isNotEmpty() })
        .joinToString(" · ")
    }

    fun isWatchToken(clientName: String): Boolean =
      clientName == TOKEN_NAME_PREFIX || clientName.startsWith("$TOKEN_NAME_PREFIX · ")
  }
}
