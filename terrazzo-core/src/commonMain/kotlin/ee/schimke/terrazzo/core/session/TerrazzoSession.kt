package ee.schimke.terrazzo.core.session

import ee.schimke.ha.client.HaPushNotification
import ee.schimke.ha.client.TerrazzoIntegration
import ee.schimke.ha.client.dashboard.AgentEdits
import ee.schimke.ha.client.dashboard.DashboardAgent
import ee.schimke.ha.client.dashboard.DashboardChatReply

/**
 * The Terrazzo integration's commands, for a session with a Home Assistant behind it that may have
 * the integration ([LiveHaSession], and [CachedHaSession] around one). A session without one (demo,
 * offline, or an app's own [HaSession] over Core alone) doesn't implement it, so it carries none of
 * these: ask [HaSession.terrazzo].
 *
 * Implementing this says only that the integration can be asked, not that it is there:
 * [terrazzoIntegration] answers that, and each member answers "unsupported" (null, empty, off or
 * false) when the integration is missing, too old for the command, or leaves it out for this user.
 * Those are also the defaults, so a partial implementation (a test's fake) overrides only what it
 * has.
 */
interface TerrazzoSession {
  /**
   * Whether this Home Assistant has the Terrazzo integration, and what it offers this user
   * (`terrazzo/info`, see [ee.schimke.ha.client.detectTerrazzo]): asked once per connection. Null
   * while it can't be asked. A UI tells [TerrazzoIntegration.Absent] and
   * [TerrazzoIntegration.TooOld] ("install or update the integration") from
   * [TerrazzoIntegration.Unsupported] ("update the app").
   */
  suspend fun terrazzoIntegration(): TerrazzoIntegration? = null

  /**
   * The integration's agent edit sessions on this Home Assistant
   * (docs/architecture/dashboard-editing.md), or null when the session can't reach them.
   */
  val agentEdits: AgentEdits?
    get() = null

  /**
   * The conversation agent that answers the integration's edit sessions (`terrazzo/agent/info`):
   * while one does, the app's Edit opens a chat with it. Off without the integration, or when it
   * can't be asked.
   */
  suspend fun dashboardAgent(): DashboardAgent = DashboardAgent.Off

  /**
   * One turn of the dashboard chat (`terrazzo/assist`,
   * [ee.schimke.ha.client.HaClient.terrazzoAssist]): Home Assistant's conversation agent, asked
   * from the dashboard [urlPath] the person has open. Throws where the session can't reach it.
   */
  suspend fun dashboardChat(
    text: String,
    urlPath: String?,
    view: String?,
    entityIds: List<String>,
    conversationId: String?,
    sessionId: String?,
  ): DashboardChatReply =
    throw UnsupportedOperationException("The dashboard chat needs the Terrazzo integration")

  /**
   * Registers this device's FCM token with the integration's silent push for [webhookId]: the
   * `push_url` and `push_token` to give HA, or null without the integration.
   */
  suspend fun registerSilentPush(
    webhookId: String,
    fcmToken: String,
    relayUrl: String,
  ): Pair<String, String>? = null

  /** The notifications the integration held for [webhookId]; each is answered once. */
  suspend fun takeHeldNotifications(webhookId: String): List<HaPushNotification> = emptyList()

  /**
   * Tells the integration which entities this device's widgets read, so a change to one wakes the
   * app, with a lease of [leaseSeconds] while the app follows them itself. The lease granted, in
   * seconds, or null without the integration.
   */
  suspend fun watchStates(
    webhookId: String,
    entityIds: Collection<String>,
    leaseSeconds: Int,
  ): Int? = null

  /**
   * Whether this Home Assistant runs its own card design server (`terrazzo/design_server`, for the
   * Card Designs add-on): its API is then at `<baseUrl>/api/terrazzo/designs`, with this session's
   * access token.
   */
  suspend fun privateDesignServer(): Boolean = false

  /** The current user's shared card selections, or null when the integration is too old. */
  suspend fun sharedDesignSelections(): Map<String, String>? = null

  /** Select a private design for the signed-in user across their devices. */
  suspend fun setSharedDesignSelection(card: String, designId: String): Unit =
    throw UnsupportedOperationException("Shared card selections are unavailable")

  /**
   * A one-time link that opens the design server's page [path] in a browser, signed in through this
   * Home Assistant ([privateDesignServer]); null where there is none or the user isn't an admin.
   */
  suspend fun privateDesignLink(path: String): String? = null
}

/**
 * This session's Terrazzo integration commands, or null when it has no Home Assistant behind it
 * that could have the integration (demo, offline).
 */
val HaSession.terrazzo: TerrazzoSession?
  get() = this as? TerrazzoSession
