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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

/** How a person designs a card in the studio: themselves in UI Builder, or with an AI agent. */
enum class CardStudioMode(val label: String) {
  Builder("Myself, in UI Builder"),
  Agent("With an AI agent"),
  CloudAgent("With a cloud agent"),
}

/**
 * The studio choices remembered between visits, as strong defaults: the step they answer starts
 * answered, folded to one line with a Change button. No API key: keys stay in memory
 * ([StudioKeys]).
 */
@Serializable
data class CardStudioDefaults(
  val mode: CardStudioMode? = null,
  val provider: CardAgentProvider = CardAgentProvider.OpenRouter,
  val model: String = "",
  val endpoint: String = "",
) {
  /** Whether the agent's provider, model (and endpoint) are chosen: its setup can fold away. */
  val agentReady: Boolean
    get() = model.isNotBlank() && (provider != CardAgentProvider.Agent || endpoint.isNotBlank())

  fun encode(): String = Json.encodeToString(serializer(), this)

  companion object {
    fun decode(text: String): CardStudioDefaults = runCatching {
      Json.decodeFromString(serializer(), text)
    }
      .getOrDefault(CardStudioDefaults())
  }
}

/**
 * Provider API keys for this app process only: entered once per run, never written to disk, never
 * sent to Home Assistant or the design server.
 */
private object StudioKeys {
  val keys = mutableStateMapOf<CardAgentProvider, String>()
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

internal class CardSelectionException(message: String) : Exception(message)

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
  preview: @Composable () -> Unit,
) {
  val recovered = remember(cloudSessionKey) { cloudSessionKey?.let { CardAgentSessions.get(it) } }
  var server by remember { mutableStateOf(initialServerUrl) }
  var mode by remember {
    mutableStateOf(if (recovered != null) CardStudioMode.CloudAgent else defaults.mode)
  }
  var choosingMode by remember { mutableStateOf(false) }
  var provider by remember { mutableStateOf(defaults.provider) }
  var model by remember { mutableStateOf(defaults.model) }
  var endpoint by remember { mutableStateOf(defaults.endpoint) }
  val apiKey = StudioKeys.keys[provider].orEmpty()
  var editingAgent by remember { mutableStateOf(!defaults.agentReady) }
  var instruction by remember { mutableStateOf(recovered?.instruction.orEmpty()) }
  var cloudSession by remember {
    mutableStateOf(recovered?.let { CardAgentSession(it.ownerToken, it.agentToken) })
  }
  var cloudServer by remember { mutableStateOf(recovered?.server) }
  var cloudTarget by remember { mutableStateOf<String?>(null) }
  var cloudTargetProblem by remember { mutableStateOf<String?>(null) }
  var draft by remember { mutableStateOf<JsonObject?>(null) }
  var cloudDraftReady by remember { mutableStateOf(false) }
  var draftAssets by remember { mutableStateOf<Map<String, ByteArray>>(emptyMap()) }
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

  fun saveDefaults(update: CardStudioDefaults.() -> CardStudioDefaults) {
    onDefaultsChange(CardStudioDefaults(mode, provider, model.trim(), endpoint.trim()).update())
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
        else
          "Opened in your browser. Save your design there; applying it in this host is not yet supported."
      } else {
        editorUrl = url
        null
      }
    }

  fun generate() =
    launch(3) { studio ->
      val seed = draft ?: studio.seed(card, currentSnapshot(fresh = true))
      val agent = AgentCardDesigner()
      try {
        draft = agent.design(provider, apiKey, model, instruction, seed, endpoint)
      } finally {
        agent.close()
      }
      saveDefaults { copy(mode = CardStudioMode.Agent) }
      editingAgent = false
      "Draft ready. Open it in UI Builder to review and save it."
    }

  fun startCloudSession() =
    launch(3) { _ ->
      // Use the configured upstream, not the private add-on's HA-authenticated proxy. The cloud
      // receives only cardPreviewData; it never receives the Home Assistant access token.
      val target = cloudTarget ?: return@launch "The upstream design server is unavailable."
      val upstream = CardStudioClient(target)
      try {
        val seed = upstream.seed(card, currentSnapshot(fresh = true))
        cloudSession = upstream.startAgentSession(seed, instruction)
        cloudDraftReady = false
        cloudServer = target
        cloudSessionKey?.let { key ->
          val session = checkNotNull(cloudSession)
          CardAgentSessions.put(
            key,
            SavedCardAgentSession(
              target,
              session.ownerToken,
              session.agentToken,
              instruction,
              Clock.System.now().toEpochMilliseconds() + 3_600_000,
            ),
          )
        }
      } finally {
        upstream.close()
      }
      "Request created. Give the agent the connection details below."
    }

  fun retrieveCloudDraft() =
    launch(3) { _ ->
      val session = cloudSession ?: return@launch null
      val upstream = CardStudioClient(cloudServer ?: return@launch null)
      try {
        val (candidate, revision) = upstream.agentSessionDraft(session.ownerToken)
        if (revision == 0L) return@launch "The agent hasn't submitted a draft yet."
        val images =
          (candidate["assets"] as? JsonObject).orEmpty().mapValues { (key, value) ->
            val digest =
              value.jsonObject["contentDigest"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.removePrefix("sha256:") ?: error("Missing image digest")
            upstream.agentSessionAsset(session.ownerToken, key, digest)
          }
        require(images.values.sumOf { it.size } <= 2 * 1024 * 1024)
        draft = candidate
        draftAssets = images
        cloudDraftReady = true
      } finally {
        upstream.close()
      }
      if (onPublished != null) "Draft received. Review it in UI Builder before publishing."
      else "Draft received. Review it in UI Builder; applying it in this host is not yet supported."
    }

  fun revokeCloudSession() =
    launch(3) { _ ->
      val session = cloudSession ?: return@launch null
      val upstream = CardStudioClient(cloudServer ?: return@launch null)
      try {
        upstream.revokeAgentSession(session.ownerToken)
        cloudSessionKey?.let(CardAgentSessions::remove)
        cloudSession = null
        cloudServer = null
        cloudDraftReady = false
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
            title = { Text("Design this card", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
            Text("Design this card", style = MaterialTheme.typography.titleLarge)
          }
        }
        preview()
        var number = 1
        if (onSignIn != null && homeServer == null) {
          StudioStep(
            number = number++,
            title = "Sign in",
            state = if (signedIn) StepState.Done else StepState.Active,
            summary = account?.let { "Signed in as $it" } ?: "Signed in with GitHub",
          ) {
            Text(
              "Sign in once with GitHub. Your designs are saved to your account and open " +
                "in UI Builder without signing in again.",
              style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onSignIn) { Text("Sign in with GitHub") }
          }
        }
        val modeChosen = mode != null && !choosingMode
        StudioStep(
          number = number++,
          title = "How do you want to design it?",
          state =
            when {
              needsSignIn -> StepState.Locked
              modeChosen -> StepState.Done
              else -> StepState.Active
            },
          summary = mode?.label.orEmpty(),
          onChange = { choosingMode = true },
        ) {
          CardStudioMode.entries.forEach { option ->
            StudioChoice(
              title = option.label,
              detail =
                when (option) {
                  CardStudioMode.Builder ->
                    "Open a copy of this card, filled with its live values, in the visual editor."
                  CardStudioMode.Agent ->
                    "Describe what you want; Claude, OpenAI or OpenRouter drafts it with your own API key."
                  CardStudioMode.CloudAgent ->
                    "Give a cloud agent a temporary task for this card, then review its draft."
                },
              selected = mode == option,
              enabled = !busy,
            ) {
              mode = option
              choosingMode = false
              saveDefaults { copy(mode = option) }
            }
          }
        }
        val designed = openedHandoffs.isNotEmpty()
        StudioStep(
          number = number++,
          title =
            if (mode == CardStudioMode.Builder) "Edit in UI Builder" else "Describe the design",
          state =
            when {
              needsSignIn || !modeChosen -> StepState.Locked
              else -> StepState.Active
            },
        ) {
          if (mode == CardStudioMode.CloudAgent) {
            OutlinedTextField(
              instruction,
              { instruction = it },
              label = { Text("What should change?") },
              enabled = !busy && cloudSession == null,
              modifier = Modifier.fillMaxWidth(),
            )
            Text(
              "The design server at ${cloudTarget ?: "the configured upstream"} receives this card's display text, current values and binding contract. The agent receives temporary access to this request; no Home Assistant token is sent. Access expires after one hour.",
              style = MaterialTheme.typography.bodySmall,
            )
            Text(
              if (onPublished != null)
                "The agent can rearrange the card's supported components and bindings. Review the result in UI Builder before publishing it."
              else
                "The agent can rearrange supported components and bindings. Review and save the result in UI Builder; applying it in this host is not yet supported.",
              style = MaterialTheme.typography.bodySmall,
            )
            val session = cloudSession
            if (session == null) {
              cloudTargetProblem?.let { Text(it) }
              Button(
                onClick = { startCloudSession() },
                enabled = !busy && instruction.isNotBlank() && cloudTarget != null,
              ) {
                Text("Create agent request")
              }
            } else {
              Text(
                "Share the temporary connection details with your cloud agent. Anyone holding them can read and edit this card draft."
              )
              FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                  onClick = {
                    clipboard.setText(
                      AnnotatedString(
                        "Redesign this card. Connect to the MCP endpoint ${cloudServer.orEmpty()}/api/studio/agent-mcp " +
                          "with Authorization: Bearer ${session.agentToken}; call get_card_request and " +
                          "submit_card_draft. If your client cannot connect MCP, GET ${cloudServer.orEmpty()}/api/studio/agent-session " +
                          "with Authorization: Bearer ${session.agentToken}. Read instruction, document and revision. " +
                          "POST to the same URL with the same Authorization and JSON body " +
                          "{\"revision\": <revision>, \"document\": <complete edited document>}. " +
                          "Preserve all bindings; the person reviews and publishes the draft."
                      )
                    )
                    messageStep = 3
                    message = "Connection details copied."
                  },
                  enabled = !busy,
                ) {
                  Text("Copy agent details")
                }
                OutlinedButton(onClick = { retrieveCloudDraft() }, enabled = !busy) {
                  Text("Check for draft")
                }
                TextButton(onClick = { revokeCloudSession() }, enabled = !busy) {
                  Text("Revoke access")
                }
              }
              if (cloudDraftReady)
                Button(onClick = { openInBuilder() }, enabled = !busy) {
                  Text("Review in UI Builder")
                }
            }
          } else if (mode == CardStudioMode.Agent) {
            AgentSetup(
              provider = provider,
              model = model,
              endpoint = endpoint,
              apiKey = apiKey,
              expanded = editingAgent,
              enabled = !busy,
              onExpand = { editingAgent = true },
              onProvider = {
                provider = it
                model = ""
              },
              onModel = { model = it },
              onEndpoint = { endpoint = it },
              onApiKey = { StudioKeys.keys[provider] = it },
            )
            OutlinedTextField(
              instruction,
              { instruction = it },
              label = { Text("What should change?") },
              placeholder = {
                Text("Make the state easier to read and fit this card at phone width.")
              },
              enabled = !busy,
              minLines = if (compactHeight) 2 else 3,
              maxLines = if (compactHeight) 2 else Int.MAX_VALUE,
              modifier =
                Modifier.fillMaxWidth().bringIntoViewRequester(instructionIntoView).onFocusChanged {
                  instructionFocused = it.isFocused
                },
            )
            Text(
              "Sending shares this card's display text and current values with ${provider.label}. Other entities, Home Assistant tokens and entity-picture URLs stay on this device.",
              style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              val ready =
                apiKey.isNotBlank() &&
                  model.isNotBlank() &&
                  instruction.isNotBlank() &&
                  (provider != CardAgentProvider.Agent || endpoint.isNotBlank())
              if (draft == null || busy) {
                Button(onClick = { generate() }, enabled = !busy && ready) {
                  Text(if (busy && messageStep == 3) "Working…" else "Draft it")
                }
              } else {
                Button(onClick = { openInBuilder() }) { Text("Review in UI Builder") }
                OutlinedButton(onClick = { generate() }, enabled = ready) { Text("Draft again") }
              }
              if (busy && messageStep == 3)
                TextButton(onClick = { job?.cancel() }) { Text("Cancel") }
            }
          } else {
            Text(
              if (openEditorInBrowser)
                "UI Builder opens in your browser. Save your design there, then come back here."
              else "Save your design in UI Builder, then come back here.",
              style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Button(onClick = { openInBuilder() }, enabled = !busy && server.isNotBlank()) {
                Text(if (designed) "Open again" else "Open in UI Builder")
              }
              if (busy && messageStep == 3)
                TextButton(onClick = { job?.cancel() }) { Text("Cancel") }
            }
          }
          if (messageStep == 3) StudioRequestFeedback(busy, message, requestFailed)
        }
        if (onPublished != null) {
          StudioStep(
            number = number,
            title = "Use it on this card",
            state =
              when {
                publishedId != null -> StepState.Done
                needsSignIn || !modeChosen -> StepState.Locked
                else -> StepState.Active
              },
            summary = "Published privately",
            onChange = { publishedId = null },
          ) {
            Text(
              if (!signedIn) "Sign in to use designs you save in UI Builder."
              else
                "Publishes the design you saved for this card, for your devices only, and switches this card to it.",
              style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Button(
                onClick = { publish() },
                enabled = !busy && signedIn && designKey != null && server.isNotBlank(),
              ) {
                Text("Publish privately and use it")
              }
              if (busy && messageStep == 4)
                TextButton(onClick = { job?.cancel() }) { Text("Cancel") }
            }
          }
          if (messageStep == 4) StudioRequestFeedback(busy, message, requestFailed)
        }
        HorizontalDivider()
        TextButton(onClick = { showAdvanced = !showAdvanced }) {
          Text(if (showAdvanced) "Hide options" else "More options")
        }
        if (showAdvanced) {
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
            "Designs stay drafts until you publish them. Nothing here changes your Home Assistant dashboard."
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
private fun StudioChoice(
  title: String,
  detail: String,
  selected: Boolean,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  OutlinedCard(
    onClick = onClick,
    enabled = enabled,
    border =
      androidx.compose.foundation.BorderStroke(
        if (selected) 2.dp else 1.dp,
        if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant,
      ),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(title, style = MaterialTheme.typography.titleSmall)
      Text(detail, style = MaterialTheme.typography.bodySmall)
    }
  }
}

/**
 * The agent's provider, model, endpoint and key. Once a provider and model are remembered it folds
 * to one line, opening only the key field when this run hasn't been given one yet.
 */
@Composable
private fun AgentSetup(
  provider: CardAgentProvider,
  model: String,
  endpoint: String,
  apiKey: String,
  expanded: Boolean,
  enabled: Boolean,
  onExpand: () -> Unit,
  onProvider: (CardAgentProvider) -> Unit,
  onModel: (String) -> Unit,
  onEndpoint: (String) -> Unit,
  onApiKey: (String) -> Unit,
) {
  val keyField: @Composable () -> Unit = {
    OutlinedTextField(
      apiKey,
      onApiKey,
      label = { Text("${provider.label} API key") },
      supportingText = {
        Text("Kept until the app closes; never saved or sent to Home Assistant.")
      },
      enabled = enabled,
      singleLine = true,
      visualTransformation = PasswordVisualTransformation(),
      modifier = Modifier.fillMaxWidth(),
    )
  }
  if (!expanded) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
      Text(
        "${provider.label} · $model",
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      TextButton(onClick = onExpand, enabled = enabled) { Text("Change") }
    }
    if (apiKey.isBlank()) keyField()
    return
  }
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    CardAgentProvider.entries.forEach { option ->
      FilterChip(
        provider == option,
        { if (provider != option) onProvider(option) },
        label = { Text(option.label) },
        enabled = enabled,
      )
    }
  }
  if (provider == CardAgentProvider.Agent) {
    OutlinedTextField(
      endpoint,
      onEndpoint,
      label = { Text("Agent chat-completions HTTPS endpoint") },
      enabled = enabled,
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
  }
  OutlinedTextField(
    model,
    onModel,
    label = { Text("Model ID") },
    enabled = enabled,
    singleLine = true,
    modifier = Modifier.fillMaxWidth(),
  )
  keyField()
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
