@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ee.schimke.terrazzo.shared

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.agent.AgentRelayClient
import ee.schimke.ha.client.agent.AgentRelayException
import ee.schimke.ha.client.agent.AgentRelayState
import ee.schimke.ha.client.agent.AgentSessionEndedException
import ee.schimke.ha.client.design.*
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * How a person designs a card in the studio: with their agent (everyone's flow), or themselves in
 * UI Builder (under More options). [Agent] is an earlier API-key flow, read from old settings and
 * treated as the agent flow.
 */
enum class CardStudioMode(val label: String) {
  Builder("Myself, in UI Builder"),
  Agent("With your agent"),
  CloudAgent("With your agent"),
}

/** The studio choice remembered between visits: whether the person designs in UI Builder. */
@Serializable
data class CardStudioDefaults(val mode: CardStudioMode? = null) {
  fun encode(): String = Json.encodeToString(serializer(), this)

  companion object {
    private val json = Json { ignoreUnknownKeys = true }

    /** Earlier settings (with an API-key provider and model) still read: those keys are gone. */
    fun decode(text: String): CardStudioDefaults = runCatching {
      json.decodeFromString(serializer(), text)
    }
      .getOrDefault(CardStudioDefaults())
  }
}

/**
 * A Home Assistant's own design server (the Card Designs add-on), which the studio uses in place of
 * a design server elsewhere: no sign-in (it is the Home Assistant's), that Home Assistant's
 * [accessToken] on every call, and the editor opened through it ([link]).
 */
class HomeDesignServer(
  val haBaseUrl: String,
  val accessToken: suspend () -> String,
  /** A one-time link that opens the add-on's page [path] signed in; null when this user can't. */
  val link: suspend (path: String) -> String?,
)

class CardSelectionException(message: String) : Exception(message)

/**
 * Shared phone/desktop authoring surface, as a wizard: sign in, choose how to design, design, then
 * use the design on the card. Each answered step folds to one line; [defaults] pre-answer the ones
 * answered last time.
 */
@Composable
fun CardStudioScreen(
  card: CardConfig,
  snapshot: HaSnapshot,
  onBack: () -> Unit,
  initialServerUrl: String = "https://ha.coo.ee",
  refreshSnapshot: (suspend () -> HaSnapshot)? = null,
  /**
   * The design server's device token: with it, a design opens and publishes with no web sign-in.
   */
  designToken: String = "",
  /**
   * Signs this device in to the design server (a Custom Tab), which also signs the browser in to UI
   * Builder. Null where the host can't: the studio then falls back to a web handoff page.
   */
  onSignIn: (() -> Unit)? = null,
  /** The GitHub login [designToken] was made for, when the host can tell. */
  account: String? = null,
  /** The choices remembered from last time, and where to remember new ones. */
  defaults: CardStudioDefaults = CardStudioDefaults(),
  onDefaultsChange: (CardStudioDefaults) -> Unit = {},
  /**
   * Open UI Builder in the system browser, which shares the sign-in [onSignIn] made, rather than in
   * an embedded browser view.
   */
  openEditorInBrowser: Boolean = false,
  /**
   * The card's design key as the host draws it (its theme, mode and card style): a design published
   * here is published for this card in each theme of that style.
   */
  designKey: DesignKey? = null,
  /**
   * Called with a design published privately for this card (its skin id): the host downloads it and
   * switches this card to it. Null where the host can't, which hides publishing.
   */
  onPublished: (suspend (designId: String) -> Unit)? = null,
  /**
   * This Home Assistant's own design server, used instead of [initialServerUrl] when it has one.
   */
  homeServer: HomeDesignServer? = null,
  /** Stable key scoped to the signed-in home and card, for recovering a cloud request. */
  cloudSessionKey: String? = null,
  /** Why this host cannot load shared selections, and an immediate retry when available. */
  selectionProblem: String? = null,
  onRetrySelections: (() -> Unit)? = null,
  preview: @Composable () -> Unit,
) {
  val recovered = remember(cloudSessionKey) { cloudSessionKey?.let { CardAgentSessions.get(it) } }
  var server by remember { mutableStateOf(initialServerUrl) }
  var mode by remember {
    // One flow for everyone: Ask your agent. UI Builder stays under More options.
    mutableStateOf(
      if (defaults.mode == CardStudioMode.Builder && recovered == null) CardStudioMode.Builder
      else CardStudioMode.CloudAgent
    )
  }
  var instruction by remember { mutableStateOf(recovered?.instruction.orEmpty()) }
  /** The cloud session's owner capability: the agent itself joins with a code. */
  var cloudSession by remember { mutableStateOf(recovered?.ownerToken) }
  var cloudServer by remember { mutableStateOf(recovered?.server) }
  var cloudLive by remember { mutableStateOf<AgentRelayState?>(null) }
  var cloudRevision by remember { mutableLongStateOf(0L) }
  var cloudTarget by remember { mutableStateOf<String?>(null) }
  var cloudTargetProblem by remember { mutableStateOf<String?>(null) }
  var draft by remember { mutableStateOf<JsonObject?>(null) }
  var cloudDraftReady by remember { mutableStateOf(false) }
  var draftAssets by remember { mutableStateOf<Map<String, ByteArray>>(emptyMap()) }
  /** The agent's draft built as "Use it" would publish it, to play before deciding. */
  var draftPreview by remember { mutableStateOf<ByteArray?>(null) }
  var draftPreviewProblem by remember { mutableStateOf<String?>(null) }
  var editorUrl by remember { mutableStateOf<String?>(null) }
  var externalUrl by remember(editorUrl) { mutableStateOf<String?>(null) }
  var showAdvanced by remember { mutableStateOf(false) }
  val instructionIntoView = remember { BringIntoViewRequester() }
  var instructionFocused by remember { mutableStateOf(false) }
  var viewportHeight by remember { mutableIntStateOf(0) }
  // The text field brings only its cursor into view, and focus can arrive before the IME shrinks
  // the viewport: relocate the whole field again after that layout change, and as it grows.
  LaunchedEffect(instructionFocused, viewportHeight, instruction) {
    if (instructionFocused && viewportHeight > 0) instructionIntoView.bringIntoView()
  }
  // Which step's request the message belongs to.
  var messageStep by remember { mutableStateOf(0) }
  var message by remember { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }
  // The handoffs opened for this card: its designs, among any opened for other cards of its type.
  val openedHandoffs = remember(card) { mutableStateListOf<String>() }
  var publishedId by remember { mutableStateOf<String?>(null) }
  var requestFailed by remember { mutableStateOf(false) }
  var job by remember { mutableStateOf<Job?>(null) }
  val scope = rememberCoroutineScope()
  val uriHandler = LocalUriHandler.current
  @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
  val latestSnapshot by rememberUpdatedState(snapshot)
  // A Home Assistant's own design server needs no sign-in of its own.
  val signedIn = designToken.isNotBlank() || homeServer != null
  // A host that can sign in asks for it first: everything after works without a web sign-in.
  val needsSignIn = onSignIn != null && !signedIn

  LaunchedEffect(homeServer, server, mode) {
    if (mode != CardStudioMode.CloudAgent || cloudSession != null) return@LaunchedEffect
    cloudTarget = null
    cloudTargetProblem = null
    val studio = homeServer?.let {
      CardStudioClient.throughHomeAssistant(it.haBaseUrl, it.accessToken)
    }
    try {
      cloudTarget = studio?.upstreamServer() ?: studioServerUrl(server)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      cloudTargetProblem =
        "Couldn't find an HTTPS upstream design server. Check the design server configuration."
    } finally {
      studio?.close()
    }
  }

  // Each new draft is built for this card and played here, with its live values, before the person
  // can use it: what they see is what Use it publishes.
  LaunchedEffect(cloudRevision, cloudSession, cloudServer, designKey) {
    draftPreview = null
    draftPreviewProblem = null
    val owner = cloudSession ?: return@LaunchedEffect
    val target = cloudServer ?: return@LaunchedEffect
    val key = designKey ?: return@LaunchedEffect
    if (cloudRevision == 0L) return@LaunchedEffect
    val upstream = CardStudioClient(target)
    try {
      draftPreview = upstream.agentSessionPreview(owner, key)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      draftPreviewProblem = e.message ?: "Couldn't draw your agent's draft."
    } finally {
      upstream.close()
    }
  }

  fun saveDefaults(update: CardStudioDefaults.() -> CardStudioDefaults) {
    onDefaultsChange(CardStudioDefaults(mode).update())
  }

  fun launch(step: Int, block: suspend (CardStudioClient) -> String?) {
    if (busy) return
    messageStep = step
    requestFailed = false
    busy = true
    job = scope.launch {
      message = null
      var studio: CardStudioClient? = null
      try {
        studio =
          homeServer?.let { CardStudioClient.throughHomeAssistant(it.haBaseUrl, it.accessToken) }
            ?: CardStudioClient(server)
        message = block(studio)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        requestFailed = true
        message =
          when (e) {
            is CardStudioException,
            is CardPublishException,
            is CardSelectionException -> e.message
            is IllegalArgumentException ->
              "The draft or connection settings are invalid. Check the HTTPS address and try again."
            else ->
              "Could not complete the request. Check the server, provider key, model, and network, then try again."
          }
      } finally {
        studio?.close()
        busy = false
      }
    }
  }

  /** The bearer for the signed-in calls: the Home Assistant's own token, or the device token. */
  suspend fun bearer(): String = homeServer?.accessToken?.invoke() ?: designToken

  suspend fun currentSnapshot(fresh: Boolean): HaSnapshot =
    if (fresh && refreshSnapshot != null) refreshSnapshot() else latestSnapshot

  fun openInBuilder() =
    launch(3) { studio ->
      val now = currentSnapshot(fresh = true)
      val seed = draft ?: studio.seed(card, now)
      val url =
        if (signedIn) {
          val opened = studio.openAsUser(bearer(), seed, card, now, assets = draftAssets)
          draft = opened.document
          openedHandoffs += opened.handoff
          // The add-on names its editor by path; a browser reaches it through Home Assistant.
          homeServer?.let { it.link(opened.url) ?: throw CardStudioException(403) } ?: opened.url
        } else {
          require(draftAssets.isEmpty()) { "Sign in before opening a draft with images." }
          val handoff = studio.openDraft(seed, card, now)
          draft = handoff.document
          openedHandoffs += handoffToken(handoff.url)
          handoff.url
        }
      if (openEditorInBrowser) {
        uriHandler.openUri(url)
        if (onPublished != null)
          "Opened in your browser. Save your design there, then come back to use it."
        else if (selectionProblem != null)
          "Opened in your browser. Save your design there; retry loading card selections here."
        else
          "Opened in your browser. Save your design there; applying it in this host is not yet supported."
      } else {
        editorUrl = url
        null
      }
    }

  fun startCloudSession() =
    launch(3) { _ ->
      // Use the configured upstream, not the private add-on's HA-authenticated proxy. The cloud
      // receives only cardPreviewData; it never receives the Home Assistant access token.
      val target = cloudTarget ?: return@launch "The upstream design server is unavailable."
      val upstream = CardStudioClient(target)
      try {
        val seed = upstream.seed(card, currentSnapshot(fresh = true))
        // With a connected agent the request goes straight to it: no code.
        val owner = upstream.startAgentSession(seed, instruction, AgentLinkStore.get(target))
        cloudSession = owner
        cloudDraftReady = false
        cloudRevision = 0
        cloudLive = null
        cloudServer = target
        cloudSessionKey?.let { key ->
          CardAgentSessions.put(
            key,
            SavedCardAgentSession(
              target,
              owner,
              instruction,
              // A session in use lasts up to a day; the relay says when it ends sooner.
              Clock.System.now().toEpochMilliseconds() + 24 * 3_600_000L,
            ),
          )
        }
      } finally {
        upstream.close()
      }
      if (AgentLinkStore.get(target) != null) "Sent to your agent."
      else "Request created. Give your agent the code below."
    }

  /** The agent's latest draft and its images, checked; returns its revision (0: none yet). */
  suspend fun loadCloudDraft(owner: String, target: String): Long {
    val upstream = CardStudioClient(target)
    try {
      val (candidate, revision) = upstream.agentSessionDraft(owner)
      if (revision == 0L) return 0
      val images =
        (candidate["assets"] as? JsonObject).orEmpty().mapValues { (key, value) ->
          val digest =
            value.jsonObject["contentDigest"]?.jsonPrimitive?.contentOrNull?.removePrefix("sha256:")
              ?: error("Missing image digest")
          upstream.agentSessionAsset(owner, key, digest)
        }
      require(images.values.sumOf { it.size } <= 2 * 1024 * 1024)
      draft = candidate
      draftAssets = images
      cloudDraftReady = true
      cloudRevision = revision
      return revision
    } finally {
      upstream.close()
    }
  }

  fun draftReceivedMessage() =
    if (onPublished != null) "Draft received. Review it in UI Builder before publishing."
    else if (selectionProblem != null)
      "Draft received. Review it in UI Builder; retry loading card selections here."
    else "Draft received. Review it in UI Builder; applying it in this host is not yet supported."

  fun forgetCloudSession() {
    cloudSessionKey?.let(CardAgentSessions::remove)
    cloudSession = null
    cloudServer = null
    cloudLive = null
    cloudDraftReady = false
  }

  /** Something the person does on the relay: a message, a code, letting an agent in. */
  fun relayCall(block: suspend (AgentRelayClient, String) -> String?) =
    launch(3) { _ ->
      val owner = cloudSession ?: return@launch null
      val relay = AgentRelayClient(cloudServer ?: return@launch null)
      try {
        block(relay, owner).also { cloudLive = relay.state(owner) }
      } catch (e: AgentSessionEndedException) {
        forgetCloudSession()
        "The agent session ended. Start a new request to continue."
      } catch (e: AgentRelayException) {
        requestFailed = true
        e.message
      } finally {
        relay.close()
      }
    }

  // Drafts, presence and the agent's messages arrive on their own: the server holds each request
  // until something changes, so there's nothing to check by hand.
  LaunchedEffect(cloudSession, cloudServer) {
    val owner = cloudSession ?: return@LaunchedEffect
    val target = cloudServer ?: return@LaunchedEffect
    val relay = AgentRelayClient(target)
    try {
      relay.watch(owner).collect { state ->
        cloudLive = state
        if (state.revision > cloudRevision) {
          try {
            if (loadCloudDraft(owner, target) > 0) {
              messageStep = 3
              requestFailed = false
              message = draftReceivedMessage()
            }
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            // Keep the revision unloaded: the next relay answer (25 s at most) tries again, so a
            // dropped connection doesn't lose the draft.
            messageStep = 3
            requestFailed = true
            message = "The agent's draft couldn't be loaded yet; retrying."
          }
        }
      }
    } catch (e: AgentSessionEndedException) {
      forgetCloudSession()
      messageStep = 3
      message = "The agent session ended. Start a new request to continue."
    } finally {
      relay.close()
    }
  }

  fun revokeCloudSession() =
    launch(3) { _ ->
      val owner = cloudSession ?: return@launch null
      val upstream = CardStudioClient(cloudServer ?: return@launch null)
      try {
        upstream.revokeAgentSession(owner)
        forgetCloudSession()
      } finally {
        upstream.close()
      }
      "Agent access revoked."
    }

  fun refresh() =
    launch(0) { studio ->
      val fresh = studio.seed(card, currentSnapshot(fresh = true))
      draft = draft?.let { refreshCardDraft(it, fresh) } ?: fresh
      "Preview data refreshed. Your design edits are kept."
    }

  suspend fun tellAgent(accepted: Boolean) {
    val owner = cloudSession ?: return
    val target = cloudServer ?: return
    val relay = AgentRelayClient(target)
    try {
      runCatching { relay.decide(owner, accepted) }
    } finally {
      relay.close()
    }
  }
  /**
   * Use the agent's draft on this card in one tap: the design server opens it as the person's own
   * design (no browser), publishes it privately, and the card switches to it.
   */
  fun useDraft() {
    val key = designKey ?: return
    val candidate = draft ?: return
    launch(4) { studio ->
      val opened =
        studio.openAsUser(bearer(), candidate, card, currentSnapshot(fresh = true), draftAssets)
      openedHandoffs += opened.handoff
      val design =
        designForCard(studio.studioDesigns(bearer(), card.type), listOf(opened.handoff))
          ?: return@launch "The design server couldn't find the draft it just saved. Try again."
      val skin = studio.publishPrivately(bearer(), design.designId, key)
      onPublished?.invoke(skin)
      publishedId = skin
      tellAgent(accepted = true)
      "Done: this card uses your agent's design. Only you see it."
    }
  }

  /**
   * Ask for another try. Not a decision: the session stays open and the agent keeps listening for
   * what the person says next. Stop is what ends it.
   */
  fun notThis() =
    launch(3) { _ ->
      val owner = cloudSession ?: return@launch null
      val relay = AgentRelayClient(cloudServer ?: return@launch null)
      try {
        relay.comment(owner, "Not this one. Please try a different design.")
      } finally {
        relay.close()
      }
      "Told your agent to try again. Say what you'd like instead, or stop here."
    }

  fun publish() {
    val key = designKey ?: return
    launch(4) { studio ->
      // The library anchor can be a tile even when the design was opened for a custom card.
      val designs = studio.studioDesigns(bearer(), card.type)
      val design = designForCard(designs, openedHandoffs)
      if (design == null) {
        requestFailed = true
        if (designs.isEmpty()) "Open this card in UI Builder first and save your design there."
        else
          "You have designs for more than one card like this. Open this card in UI Builder from here, save, then publish."
      } else {
        val skin = studio.publishPrivately(bearer(), design.designId, key)
        onPublished?.invoke(skin)
        publishedId = skin
        // Tell a connected agent it's done: the design is now on the card.
        val owner = cloudSession
        val target = cloudServer
        if (owner != null && target != null) {
          val relay = AgentRelayClient(target)
          try {
            runCatching { relay.decide(owner, accepted = true) }
          } finally {
            relay.close()
          }
        }
        "Published privately: only you see it. This card switches to it once it downloads."
      }
    }
  }

  BoxWithConstraints(Modifier.fillMaxSize()) {
    // A landscape IME can leave less height than a toolbar plus a multiline field.
    // Let the toolbar scroll with the form in short viewports, keeping Back reachable.
    val compactHeight = maxHeight < 240.dp
    Scaffold(
      topBar = {
        if (!compactHeight)
          TopAppBar(
            title = { Text("Ask your agent", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
          )
      }
    ) { padding ->
      val url = editorUrl
      if (url != null) {
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
          FlowRow(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            if (compactHeight) TextButton(onClick = onBack) { Text("Back") }
            TextButton(onClick = { editorUrl = null }) { Text("Done editing") }
            TextButton(onClick = { uriHandler.openUri(externalUrl ?: url) }) {
              Text("Open in browser")
            }
          }
          CardEditorBrowser(url, Modifier.fillMaxWidth().weight(1f)) { externalUrl = it }
        }
        return@Scaffold
      }
      Column(
        Modifier.fillMaxSize()
          .padding(padding)
          .consumeWindowInsets(padding)
          .onSizeChanged { viewportHeight = it.height }
          .verticalScroll(rememberScrollState())
          .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        if (compactHeight) {
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Ask your agent", style = MaterialTheme.typography.titleLarge)
          }
        }
        val shownDraft = draftPreview.takeIf { publishedId == null }
        if (shownDraft != null) {
          Text(
            "Your agent's draft",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
          )
          val values = remember(card, snapshot) { exactTemplateBindings(card, snapshot) }
          RemoteComposeCard(shownDraft, Modifier.fillMaxWidth(), values)
        } else preview()
        if (mode == CardStudioMode.Builder) {
          BuilderSteps(
            signInStep = onSignIn != null && homeServer == null,
            signedIn = signedIn,
            account = account,
            onSignIn = onSignIn,
            designed = openedHandoffs.isNotEmpty(),
            openEditorInBrowser = openEditorInBrowser,
            canPublish = onPublished != null,
            published = publishedId != null,
            busy = busy,
            serverReady = server.isNotBlank() && designKey != null,
            message = message,
            messageStep = messageStep,
            requestFailed = requestFailed,
            onOpen = { openInBuilder() },
            onPublish = { publish() },
            onCancel = { job?.cancel() },
            onBackToAgent = {
              mode = CardStudioMode.CloudAgent
              saveDefaults { copy(mode = CardStudioMode.CloudAgent) }
            },
          )
        } else {
          // Using a design on the card needs the person's own account, once.
          if (onPublished != null && needsSignIn) {
            Text(
              "Sign in once so the designs you use are yours alone.",
              style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { onSignIn?.invoke() }) { Text("Sign in with GitHub") }
          }
          if (cloudSession == null) {
            // The setup, once: after it, a request goes straight to the connected agent.
            cloudTarget?.let { target ->
              AgentConnection(target, onCopy = { clipboard.setText(AnnotatedString(it)) })
            }
            OutlinedTextField(
              instruction,
              { instruction = it },
              label = { Text("What should change?") },
              placeholder = { Text("Make the temperature bigger and the name quieter") },
              enabled = !busy,
              minLines = if (compactHeight) 1 else 2,
              modifier =
                Modifier.fillMaxWidth().bringIntoViewRequester(instructionIntoView).onFocusChanged {
                  instructionFocused = it.isFocused
                },
            )
            cloudTargetProblem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
              onClick = { startCloudSession() },
              enabled = !busy && instruction.isNotBlank() && cloudTarget != null,
            ) {
              Text("Send to your agent")
            }
            Text(
              "Your agent sees this card's text and current values, never your Home Assistant " +
                "login, and can only suggest a design.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            Text(instruction, style = MaterialTheme.typography.titleMedium)
            AgentLivePanel(
              state = cloudLive,
              busy = busy,
              onCopyInvite = { code ->
                clipboard.setText(
                  AnnotatedString(
                    AgentRelayClient.invite(
                      cloudServer.orEmpty(),
                      code,
                      "the design of this ${card.type} card",
                    )
                  )
                )
                messageStep = 3
                requestFailed = false
                message = "Invite copied. Paste it to your agent."
              },
              onNewCode = { relayCall { relay, owner -> relay.newJoinCode(owner).let { null } } },
              onClaim = { code ->
                relayCall { relay, owner ->
                  relay.claim(owner, code)
                  "Agent connected."
                }
              },
              onSend = { text ->
                relayCall { relay, owner ->
                  relay.comment(owner, text)
                  null
                }
              },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              if (cloudDraftReady && publishedId == null) {
                if (onPublished != null)
                  Button(
                    onClick = { useDraft() },
                    enabled = !busy && signedIn && draftPreview != null,
                  ) {
                    Text("Use it")
                  }
                OutlinedButton(onClick = { notThis() }, enabled = !busy) { Text("Not this") }
              }
              TextButton(onClick = { revokeCloudSession() }, enabled = !busy) {
                Text(if (publishedId != null) "Done" else "Stop")
              }
            }
            if (cloudDraftReady && publishedId == null)
              draftPreviewProblem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
          }
          if (messageStep == 3 || messageStep == 4)
            StudioRequestFeedback(busy, message, requestFailed)
        }
        if (onPublished == null && selectionProblem != null) {
          Text(selectionProblem, color = MaterialTheme.colorScheme.error)
          onRetrySelections?.let { retry ->
            TextButton(onClick = retry) { Text("Retry loading card selections") }
          }
        }
        HorizontalDivider()
        TextButton(onClick = { showAdvanced = !showAdvanced }) {
          Text(if (showAdvanced) "Hide options" else "More options")
        }
        if (showAdvanced) {
          if (mode != CardStudioMode.Builder)
            TextButton(
              onClick = {
                mode = CardStudioMode.Builder
                saveDefaults { copy(mode = CardStudioMode.Builder) }
              }
            ) {
              Text("Design it myself in UI Builder")
            }
          if (cloudDraftReady)
            TextButton(onClick = { openInBuilder() }, enabled = !busy) {
              Text("Open the agent's draft in UI Builder")
            }
          if (homeServer != null) {
            Text(
              "Designs are saved on this Home Assistant's own design server.",
              style = MaterialTheme.typography.bodyMedium,
            )
          } else {
            OutlinedTextField(
              server,
              {
                server = it
                draft = null
                draftAssets = emptyMap()
                message = null
              },
              label = { Text("Design server") },
              singleLine = true,
              enabled = !busy && cloudSession == null,
              modifier = Modifier.fillMaxWidth(),
            )
          }
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { refresh() }, enabled = !busy && server.isNotBlank()) {
              Text("Refresh preview data")
            }
            if (draft != null && !busy) {
              TextButton(
                onClick = {
                  draft = null
                  draftAssets = emptyMap()
                  message = null
                }
              ) {
                Text("Start over from the card")
              }
            }
          }
          if (messageStep == 0) StudioRequestFeedback(busy, message, requestFailed)
        }
        Text(
          if (onPublished != null)
            "Nothing changes until you choose Use it, and only for you. Your Home Assistant dashboard stays as it is."
          else if (selectionProblem != null)
            "Publishing will be available once card selections load."
          else "This host can create and review drafts but can't apply a custom design yet.",
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
  }
}

private enum class StepState {
  /** Waiting on an earlier step: its title only, dimmed. */
  Locked,
  Active,
  /** Answered: folded to its summary, with Change when it can be answered again. */
  Done,
}

@Composable
private fun StudioStep(
  number: Int,
  title: String,
  state: StepState,
  summary: String = "",
  onChange: (() -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  Surface(
    shape = MaterialTheme.shapes.large,
    color = if (state == StepState.Active) colors.surfaceContainer else colors.surface,
    border =
      if (state == StepState.Active) null
      else androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Surface(
          shape = androidx.compose.foundation.shape.CircleShape,
          color = if (state == StepState.Locked) colors.surfaceVariant else colors.primary,
          contentColor =
            if (state == StepState.Locked) colors.onSurfaceVariant else colors.onPrimary,
          modifier = Modifier.size(28.dp),
        ) {
          Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text(
              if (state == StepState.Done) "✓" else number.toString(),
              style = MaterialTheme.typography.labelLarge,
            )
          }
        }
        Column(Modifier.weight(1f)) {
          Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = if (state == StepState.Locked) colors.onSurfaceVariant else colors.onSurface,
          )
          if (state == StepState.Done && summary.isNotEmpty())
            Text(
              summary,
              style = MaterialTheme.typography.bodyMedium,
              color = colors.onSurfaceVariant,
            )
        }
        if (state == StepState.Done && onChange != null)
          TextButton(onClick = onChange) { Text("Change") }
      }
      if (state == StepState.Active) content()
    }
  }
}

@Composable
private fun StudioRequestFeedback(busy: Boolean, message: String?, failed: Boolean) {
  if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
  message?.let {
    Text(
      it,
      color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
  }
}

@Composable
internal expect fun CardEditorBrowser(
  url: String,
  modifier: Modifier,
  onNavigation: (String) -> Unit,
)

/** Designing the card yourself in UI Builder: sign in, open, save there, publish it here. */
@Composable
private fun BuilderSteps(
  signInStep: Boolean,
  signedIn: Boolean,
  account: String?,
  onSignIn: (() -> Unit)?,
  designed: Boolean,
  openEditorInBrowser: Boolean,
  canPublish: Boolean,
  published: Boolean,
  busy: Boolean,
  serverReady: Boolean,
  message: String?,
  messageStep: Int,
  requestFailed: Boolean,
  onOpen: () -> Unit,
  onPublish: () -> Unit,
  onCancel: () -> Unit,
  onBackToAgent: () -> Unit,
) {
  var number = 1
  if (signInStep) {
    StudioStep(
      number = number++,
      title = "Sign in",
      state = if (signedIn) StepState.Done else StepState.Active,
      summary = account?.let { "Signed in as $it" } ?: "Signed in with GitHub",
    ) {
      Text(
        "Sign in once with GitHub. Your designs are saved to your account and open in UI " +
          "Builder without signing in again.",
        style = MaterialTheme.typography.bodyMedium,
      )
      Button(onClick = { onSignIn?.invoke() }) { Text("Sign in with GitHub") }
    }
  }
  StudioStep(
    number = number++,
    title = "Edit in UI Builder",
    state = if (signInStep && !signedIn) StepState.Locked else StepState.Active,
  ) {
    Text(
      if (openEditorInBrowser)
        "UI Builder opens in your browser. Save your design there, then come back here."
      else "Save your design in UI Builder, then come back here.",
      style = MaterialTheme.typography.bodyMedium,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = onOpen, enabled = !busy) {
        Text(if (designed) "Open again" else "Open in UI Builder")
      }
      if (busy && messageStep == 3) TextButton(onClick = onCancel) { Text("Cancel") }
    }
    if (messageStep == 3) StudioRequestFeedback(busy, message, requestFailed)
  }
  if (canPublish) {
    StudioStep(
      number = number,
      title = "Use it on this card",
      state =
        when {
          published -> StepState.Done
          signInStep && !signedIn -> StepState.Locked
          else -> StepState.Active
        },
      summary = "Published privately",
    ) {
      Text(
        "Publishes the design you saved for this card, for your devices only, and switches this " +
          "card to it.",
        style = MaterialTheme.typography.bodyMedium,
      )
      Button(onClick = onPublish, enabled = !busy && signedIn && serverReady) {
        Text("Publish privately and use it")
      }
    }
    if (messageStep == 4) StudioRequestFeedback(busy, message, requestFailed)
  }
  TextButton(onClick = onBackToAgent) { Text("Ask your agent instead") }
}
