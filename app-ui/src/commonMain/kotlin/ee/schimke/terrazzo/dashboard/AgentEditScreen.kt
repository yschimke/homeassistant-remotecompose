package ee.schimke.terrazzo.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.HaCommandException
import ee.schimke.ha.client.agent.AgentLiveControls
import ee.schimke.ha.client.agent.AgentPresence
import ee.schimke.ha.client.agent.AgentRelayMessage
import ee.schimke.ha.client.agent.AgentRelayState
import ee.schimke.ha.client.dashboard.AgentEditSession
import ee.schimke.ha.client.dashboard.AgentEdits
import ee.schimke.ha.client.dashboard.DashboardConflictException
import ee.schimke.ha.client.dashboard.EditScope
import ee.schimke.ha.client.dashboard.openingRequest
import ee.schimke.ha.client.dashboard.scopeLabel
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardKey
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.shared.AgentConnection
import ee.schimke.terrazzo.shared.AgentLivePanel
import ee.schimke.terrazzo.shared.DashboardContent
import ee.schimke.terrazzo.shared.drawable
import ee.schimke.terrazzo.shared.encodePng
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Work on a dashboard with agents, in sessions (docs/architecture/agent-sessions.md). A session has
 * a scope, the whole dashboard or one section ([startScope]), and opens straight into its preview:
 * there is nothing to fill in first. The person talks to the agent in the conversation and pins
 * comments to cards on the preview; the agent's drafts replace the preview as they arrive, drawn
 * with the home's real cards and live states ([renderCard]). **Copy prompt** hands the session to
 * one or more agents; a connected agent gets it by itself. **Use it** applies a draft (Home
 * Assistant allows administrators only, and only while the dashboard still holds the config the
 * session started from); **Stop** ends it. One undo is kept.
 *
 * Without [startScope] the screen opens on the dashboard's sessions, open and recent, newest first.
 * The phone and the Terrazzo panel both show this; [onSharePrompt] is the platform's share sheet or
 * clipboard. [writeBlockedReason] (the phone's read-only mode, say) withholds Use it and Undo: a
 * session can still be started, drafted and reviewed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentEditScreen(
  edits: AgentEdits,
  urlPath: String?,
  title: String?,
  snapshot: HaSnapshot,
  onBack: () -> Unit,
  onSharePrompt: (String) -> Unit,
  onApplied: () -> Unit,
  writeBlockedReason: String? = null,
  cloudServer: String? = null,
  /** Open straight into a session about this (an open one if there is, else a new one). */
  startScope: EditScope? = null,
  /** A session to open at once: picked up from the dashboard's draft bar. */
  initialSessionId: String? = null,
  /**
   * Back to the dashboard, which keeps showing the session's drafts as they come
   * ([DashboardAgentDraft]): where a session opened from there goes back. Null where the dashboard
   * doesn't show drafts.
   */
  onDashboard: (() -> Unit)? = null,
  renderCard: @Composable (CardKey, CardConfig) -> Unit,
) {
  val scope = rememberCoroutineScope()
  val snackbars = remember { SnackbarHostState() }
  // Sessions through the design server's relay: presence and the conversation, live.
  val live = edits as? AgentLiveControls
  var sessionId by remember(edits, urlPath) { mutableStateOf(initialSessionId) }
  var session by remember(edits, urlPath) { mutableStateOf<AgentEditSession?>(null) }
  // When the last session reply arrived, and a clock ticking past it: a direct agent's presence
  // ages by elapsed time even when no session event comes (an agent that went quiet sends none).
  var receivedAt by remember(edits, urlPath) { mutableStateOf(0L) }
  var now by remember { mutableStateOf(nowMillis()) }
  LaunchedEffect(Unit) {
    while (true) {
      delay(10_000)
      now = nowMillis()
    }
  }
  LaunchedEffect(session) {
    receivedAt = nowMillis()
    now = receivedAt
  }
  val sinceReply = ((now - receivedAt).coerceAtLeast(0) / 1000.0)
  var problem by remember(edits, urlPath) { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }

  // A snackbar suspends until it is dismissed: shown on its own, so the buttons come back at once.
  fun tell(message: String) {
    scope.launch { snackbars.showSnackbar(message) }
  }

  fun run(block: suspend () -> Unit) {
    busy = true
    scope.launch {
      try {
        block()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        tell(agentEditFailure(e))
      } finally {
        busy = false
      }
    }
  }

  suspend fun open(about: EditScope?) {
    // Nothing typed: the opening says what the session is about; the person says the rest.
    val started = edits.start(urlPath, about.openingRequest(title), about)
    session = started.session
    sessionId = started.session.id
  }

  // Asked from a section (or a dashboard's own "Ask your agent"): straight into its session.
  var opened by remember(edits, urlPath, startScope) { mutableStateOf(false) }
  LaunchedEffect(edits, urlPath, startScope) {
    if (startScope == null || opened) return@LaunchedEffect
    opened = true
    run {
      val existing = runCatching {
        edits.open(urlPath)
      }
        .getOrNull()
        ?.firstOrNull { it.scope sameAs startScope }
      if (existing != null) {
        session = existing
        sessionId = existing.id
      } else open(startScope)
    }
  }

  val id = sessionId
  LaunchedEffect(edits, id) {
    if (id == null) return@LaunchedEffect
    // The subscription ends with its connection: watch again once the app has reconnected.
    while (true) {
      try {
        edits.watch(id).collect {
          session = it
          problem = null
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: HaCommandException) {
        problem = e.reason
      } catch (e: Throwable) {
        // Throwable, not Exception: on Wasm a failed fetch is an Error, and this loop must outlive
        // a network blip or a design server deploy.
        problem =
          if (cloudServer != null) e.message ?: "Reconnecting to the design server…"
          else "Reconnecting to Home Assistant…"
      }
      delay(3_000)
    }
  }

  val current = session?.takeIf { it.id == id }
  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(
            current?.scopeLabel(title) ?: "Agent sessions",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        },
        navigationIcon = {
          IconButton(
            onClick = {
              when {
                // Opened from the dashboard's draft bar or a section: back there.
                sessionId != null && sessionId == initialSessionId && onDashboard != null ->
                  onDashboard()
                sessionId != null && startScope == null && initialSessionId == null -> {
                  sessionId = null
                  session = null
                }
                else -> onBack()
              }
            }
          ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          if (current?.isOpen == true)
            TextButton(
              enabled = !busy,
              onClick = {
                run {
                  onSharePrompt(edits.prompt(current.id))
                  tell("Prompt copied. Give it to one or more agents.")
                }
              },
            ) {
              Text("Copy prompt")
            }
        },
      )
    },
    snackbarHost = { SnackbarHost(snackbars) },
  ) { padding ->
    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
      when {
        id == null && startScope == null ->
          SessionList(
            edits = edits,
            urlPath = urlPath,
            title = title,
            busy = busy,
            cloudServer = cloudServer,
            onCopy = onSharePrompt,
            onNew = { run { open(null) } },
            onOpen = { picked ->
              session = picked
              sessionId = picked.id
            },
          )
        current == null -> Text(problem ?: "Opening the session…", Modifier.padding(24.dp))
        else ->
          SessionReview(
            session = current,
            // A session not relayed by this device (an agent signed in to Home Assistant, or
            // another device) carries its presence and messages itself.
            live =
              live?.live(current.id)?.collectAsState()?.value
                ?: directLive(current, sinceReply)
                ?: pendingLive(current, current.agentListening && sinceReply < LISTENING_SECONDS),
            liveBusy = busy,
            onComment = { text, target ->
              run {
                if (live != null) edits.note(current.id, text, target)
                else session = edits.note(current.id, text, target)
              }
            },
            problem = problem,
            snapshot = snapshot,
            busy = busy,
            writeBlockedReason = writeBlockedReason,
            onApply = { draft ->
              run {
                session = edits.apply(current.id, draft.id)
                onApplied()
                tell("Done: your dashboard uses it.")
              }
            },
            onUndo = {
              run {
                session = edits.undo(current.id)
                onApplied()
              }
            },
            // Best effort, quietly: the agent sees the draft as drawn here when it asks.
            onRender = { draftId, png ->
              scope.launch { runCatching { edits.shareRender(current.id, draftId, png) } }
            },
            onDiscard = {
              run {
                edits.discard(current.id)
                if (startScope != null) onBack()
                sessionId = null
                session = null
              }
            },
            renderCard = renderCard,
          )
      }
    }
  }
}

/** Whether two scopes name the same part of a dashboard; a null scope is the whole of it. */
internal infix fun EditScope?.sameAs(other: EditScope?): Boolean {
  val a = this?.takeIf { it.isSection }
  val b = other?.takeIf { it.isSection }
  return a?.view == b?.view && a?.section == b?.section
}

/** What to tell the person when Home Assistant refuses an edit-session command. */
internal fun agentEditFailure(e: Exception): String =
  when {
    e is DashboardConflictException -> e.message.orEmpty()
    e is HaCommandException && e.code == "unauthorized" ->
      "Only a Home Assistant administrator can apply or undo a draft."
    e is HaCommandException && e.code == "unknown_command" ->
      "Update the Terrazzo integration in HACS to ask an agent."
    e is HaCommandException -> e.reason
    else -> e.message ?: "Home Assistant didn't answer."
  }

/** A dashboard's sessions, open and recent, and the one way to start another. */
@Composable
private fun SessionList(
  edits: AgentEdits,
  urlPath: String?,
  title: String?,
  busy: Boolean,
  cloudServer: String?,
  onCopy: (String) -> Unit,
  onNew: () -> Unit,
  onOpen: (AgentEditSession) -> Unit,
) {
  var sessions by remember(edits, urlPath) { mutableStateOf<List<AgentEditSession>?>(null) }
  LaunchedEffect(edits, urlPath) {
    sessions = runCatching { edits.recent(urlPath) }.getOrDefault(sessions.orEmpty())
  }
  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Button(enabled = !busy, onClick = onNew) { Text("New session") }
    // Connect once; after that a new session goes straight to the agent.
    if (cloudServer != null) AgentConnection(cloudServer, onCopy)
    val listed = sessions
    when {
      listed == null ->
        Text("Loading sessions…", color = MaterialTheme.colorScheme.onSurfaceVariant)
      listed.isEmpty() ->
        Text(
          "No sessions on ${title ?: "this dashboard"} yet.",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      else ->
        listed.forEach { s ->
          OutlinedButton(onClick = { onOpen(s) }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
              Text(s.scopeLabel(title), style = MaterialTheme.typography.titleSmall, maxLines = 1)
              Text(sessionLine(s), style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
          }
        }
    }
  }
}

/** A session's state in a few words, for the list. */
internal fun sessionLine(session: AgentEditSession): String {
  val drafts = session.drafts.size
  val counted = if (drafts == 0) "no draft yet" else "$drafts draft${if (drafts == 1) "" else "s"}"
  val status = session.agent?.status?.takeIf { it.isNotBlank() }
  return when {
    session.isApplied -> "In use · $counted"
    !session.isOpen -> "Stopped · $counted"
    status != null -> "Open · $status · $counted"
    else -> "Open · $counted"
  }
}

private val draftJson = Json {
  ignoreUnknownKeys = true
  isLenient = true
}

/** [config] as the dashboard renderer reads it, or null when it can't be drawn. */
internal fun previewDashboard(config: JsonObject?): Dashboard? = config?.let {
  runCatching { draftJson.decodeFromJsonElement(Dashboard.serializer(), it) }.getOrNull()
}

@Composable
private fun SessionReview(
  session: AgentEditSession,
  live: AgentRelayState?,
  liveBusy: Boolean,
  onComment: (String, String?) -> Unit,
  problem: String?,
  snapshot: HaSnapshot,
  busy: Boolean,
  writeBlockedReason: String?,
  onApply: (AgentEditSession.Draft) -> Unit,
  onUndo: () -> Unit,
  onDiscard: () -> Unit,
  onRender: (draftId: String, png: ByteArray) -> Unit,
  renderCard: @Composable (CardKey, CardConfig) -> Unit,
) {
  var shown by remember(session.id) { mutableStateOf<String?>(null) }
  // True for the frame the draft is captured for the agent: no Comment buttons in it.
  var capturing by remember { mutableStateOf(false) }
  // The card the next message is about, picked with a card's Comment button.
  var target by remember(session.id) { mutableStateOf<String?>(null) }
  // The newest draft unless the person picked another, or the applied one once applied.
  val draft =
    session.drafts.firstOrNull { it.id == (shown ?: session.applied?.draftId) }
      ?: session.latestDraft
  val headerScroll = rememberScrollState()
  Column(Modifier.fillMaxSize()) {
    Column(
      Modifier.fillMaxWidth()
        // With the conversation the header can grow: keep room for the preview below it.
        .heightIn(max = 420.dp)
        .verticalScroll(headerScroll)
        .padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        when {
          session.isApplied -> "Your dashboard uses it. Undo puts it back."
          !session.isOpen -> "This session was ${session.status}."
          draft == null -> "The dashboard as it is: comment on a card, or tell the agent below."
          else -> draft.summary.ifBlank { "Draft from the agent" }
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
      writeBlockedReason?.let {
        Text(
          "$it Drafts can be reviewed but not applied.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      val canWrite = !busy && writeBlockedReason == null
      if (session.isOpen) {
        AgentLivePanel(
          state = live,
          busy = liveBusy,
          onCopyInvite = {},
          onNewCode = {},
          onClaim = {},
          onSend = { text ->
            onComment(text, target)
            target = null
          },
          target = target,
          onClearTarget = { target = null },
          showCodes = false,
          composeBeforeJoin = true,
        )
      }
      if (session.drafts.size > 1) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          session.drafts.forEachIndexed { i, d ->
            FilterChip(
              selected = d.id == draft?.id,
              onClick = { shown = d.id },
              label = { Text("Draft ${i + 1}") },
            )
          }
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
          session.isApplied -> OutlinedButton(enabled = canWrite, onClick = onUndo) { Text("Undo") }
          session.isOpen -> {
            if (draft != null)
              Button(enabled = canWrite, onClick = { onApply(draft) }) { Text("Use it") }
            TextButton(enabled = !busy, onClick = onDiscard) { Text("Stop") }
          }
        }
      }
    }
    // The preview: the draft once there is one, the dashboard as it is until then.
    val dashboard = previewDashboard(draft?.config ?: session.baseline)
    if (dashboard == null) {
      if (draft != null)
        Text(
          "This draft can't be drawn here; ask the agent for a dashboard with a views list.",
          Modifier.padding(16.dp),
          color = MaterialTheme.colorScheme.error,
        )
    } else {
      val focus = session.scope?.takeIf { it.isSection }
      DashboardContent(
        dashboard,
        "agent-draft-${session.id}-${draft?.id ?: "baseline"}",
        snapshot,
        Modifier.weight(1f)
          .then(
            if (draft != null)
              Modifier.sharesRender(draft.id, session.isOpen, onRender) { capturing = it }
            else Modifier
          ),
      ) { key, card ->
        // A section session comments on its own cards; the rest of the dashboard is context.
        val commentable =
          session.isOpen &&
            !capturing &&
            (focus == null || (key.sectionIndex == focus.section && sameView(key, focus)))
        // The Comment buttons are this screen's, not the design's: hidden while it is captured.
        if (commentable)
          Box {
            renderCard(key, card)
            // Pin the next message to this card, so the agent knows which one you mean.
            Surface(
              color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
              shape = MaterialTheme.shapes.small,
              modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            ) {
              TextButton(onClick = { target = cardLabel(key, card) }) { Text("Comment") }
            }
          }
        else renderCard(key, card)
      }
    }
  }
}

/** Whether [key] is in [focus]'s view: a view without a path of its own is `view-<index>`. */
private fun sameView(key: CardKey, focus: EditScope): Boolean {
  val path = key.viewPath ?: return true
  val index = Regex("view-(\\d+)").matchEntire(path)?.groupValues?.get(1)?.toIntOrNull()
  return index == null || index == focus.view
}

/**
 * The live panel's state before anyone joined a session not relayed by this device: the person's
 * notes so far, and whether an agent signed in to Home Assistant is listening for it.
 */
internal fun pendingLive(session: AgentEditSession, agentListening: Boolean) =
  AgentRelayState(
    version = session.conversation.lastOrNull()?.seq ?: 0,
    kind = "dashboard",
    title = session.request,
    revision = session.drafts.size.toLong(),
    messages =
      session.conversation.map {
        AgentRelayMessage(
          it.seq,
          if (it.fromAgent) "agent" else "person",
          if (it.fromAgent) "message" else "comment",
          it.text,
          it.target,
        )
      },
    linked = agentListening,
  )

/** How a message names a card for the agent: its title or entity, and where it sits. */
internal fun cardLabel(key: CardKey, card: CardConfig): String {
  fun field(name: String) =
    (card.raw[name] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
  val name = field("title") ?: field("name") ?: field("heading") ?: field("entity") ?: card.type
  val where = buildString {
    // A view without a path of its own gets a made-up one ("view-0"): name it by position.
    key.viewPath?.let { path ->
      val position = Regex("view-(\\d+)").matchEntire(path)?.groupValues?.get(1)?.toIntOrNull()
      append(if (position != null) "view ${position + 1}, " else "view $path, ")
    }
    key.sectionIndex?.let { append("section ${it + 1}, ") }
    append("card ${key.cardIndex + 1}")
  }
  return "$name ($where)"
}

/**
 * The live panel's state for a session whose agent is signed in to Home Assistant directly: null
 * until the agent has been active, so the prompt to hand it shows until then. Presence counts as
 * connected for a minute and a half after the agent's last call, by Home Assistant's clock.
 */
internal fun directLive(session: AgentEditSession, sinceReply: Double = 0.0): AgentRelayState? {
  val agent = session.agent ?: return null
  val idle = session.agentIdleSeconds?.let { it + sinceReply }
  return AgentRelayState(
    version = session.conversation.lastOrNull()?.seq ?: 0,
    kind = "dashboard",
    title = session.request,
    revision = session.drafts.size.toLong(),
    agent =
      AgentPresence(
        joined = true,
        connected = idle != null && idle < 90,
        name = "Agent",
        status = agent.status,
        lastSeenSecondsAgo = idle?.toLong(),
      ),
    messages =
      session.conversation.map {
        AgentRelayMessage(
          it.seq,
          if (it.fromAgent) "agent" else "person",
          if (it.fromAgent) "message" else "comment",
          it.text,
          it.target,
        )
      },
    decision = if (session.isApplied) "accepted" else null,
  )
}

/**
 * Records what this draws and, a moment after a draft appears (its cards and pictures loaded),
 * hands it to [onRender] as a PNG at most [MAX_RENDER_WIDTH] wide: what the agent sees when it asks
 * how its draft looks.
 */
@Composable
private fun Modifier.sharesRender(
  draftId: String,
  enabled: Boolean,
  onRender: (String, ByteArray) -> Unit,
  onCapturing: (Boolean) -> Unit,
): Modifier {
  val layer = androidx.compose.ui.graphics.rememberGraphicsLayer()
  val latest by androidx.compose.runtime.rememberUpdatedState(onRender)
  LaunchedEffect(draftId, enabled) {
    if (!enabled) return@LaunchedEffect
    delay(2_000)
    // Draw one frame without this screen's own chrome, capture it, then put the chrome back.
    onCapturing(true)
    val drawn =
      try {
        androidx.compose.runtime.withFrameNanos {}
        androidx.compose.runtime.withFrameNanos {}
        runCatching { layer.toImageBitmap() }.getOrNull()
      } finally {
        onCapturing(false)
      } ?: return@LaunchedEffect
    if (drawn.width == 0 || drawn.height == 0) return@LaunchedEffect
    // Best effort: a render that can't be made is simply not shared.
    runCatching {
      drawn.drawable().scaledTo(MAX_RENDER_WIDTH, MAX_RENDER_HEIGHT).encodePng()?.takeIf {
        it.size <= MAX_RENDER_BYTES
      }
    }
      .getOrNull()
      ?.let { latest(draftId, it) }
  }
  return drawWithContent {
    layer.record { this@drawWithContent.drawContent() }
    drawLayer(layer)
  }
}

private const val MAX_RENDER_WIDTH = 1080
/** No image this project shares is over 1800px in either dimension. */
private const val MAX_RENDER_HEIGHT = 1800
private const val MAX_RENDER_BYTES = 1_400_000

/** This image no wider than [maxWidth] and no taller than [maxHeight], keeping its aspect ratio. */
internal fun androidx.compose.ui.graphics.ImageBitmap.scaledTo(
  maxWidth: Int,
  maxHeight: Int = Int.MAX_VALUE,
): androidx.compose.ui.graphics.ImageBitmap {
  if (width <= maxWidth && height <= maxHeight) return this
  val scale = minOf(maxWidth.toDouble() / width, maxHeight.toDouble() / height)
  val newWidth = (width * scale).toInt().coerceIn(1, maxWidth)
  val newHeight = (height * scale).toInt().coerceIn(1, maxHeight)
  val target = androidx.compose.ui.graphics.ImageBitmap(newWidth, newHeight)
  androidx.compose.ui.graphics
    .Canvas(target)
    .drawImageRect(
      this,
      dstSize = androidx.compose.ui.unit.IntSize(newWidth, newHeight),
      paint =
        androidx.compose.ui.graphics.Paint().apply {
          filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium
        },
    )
  return target
}

/** How long after an agent's last `await_request` it still counts as listening. */
private const val LISTENING_SECONDS = 90

private fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
