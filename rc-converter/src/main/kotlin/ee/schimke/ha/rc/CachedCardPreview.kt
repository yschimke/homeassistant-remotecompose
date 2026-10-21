@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import android.view.View
import android.view.ViewGroup
import androidx.annotation.RestrictTo
import androidx.compose.remote.core.Operation
import androidx.compose.remote.core.RemoteContext
import androidx.compose.remote.core.VariableSupport
import androidx.compose.remote.core.operations.layout.Container
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.profile.Profile
import androidx.compose.remote.creation.profile.RcPlatformProfiles
import androidx.compose.remote.player.compose.ExperimentalRemotePlayerApi
import androidx.compose.remote.player.compose.RemoteComposePlayerFlags
import androidx.compose.remote.player.core.platform.BitmapLoader
import androidx.compose.remote.player.view.RemoteComposePlayer
import androidx.compose.remote.player.view.platform.RemoteComposeView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.HA_ACTION_NAME
import ee.schimke.ha.rc.components.HaAction
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Cached counterpart to upstream `androidx.compose.remote.tooling.preview.RemotePreview`.
 *
 * The difference that matters: this version keys the captured `.rc` bytes on a caller-supplied
 * [cacheKey] and stores hits in [LocalCardDocumentCache]. Two calls with the same key skip the
 * encode entirely and play the cached bytes — even across Activity recreation, because the cache is
 * process-scoped.
 *
 * Cache-key contract: include everything that bakes into the document (the card YAML, theme colours
 * / dark flag, profile), and nothing that the host can update by named binding (entity state,
 * attributes, `is_on`). Snapshot data is read by [content] on capture but must NOT be part of the
 * key — `LiveValues.state` / `.attribute` / `.isOn` (in rc-components) are exactly the seam through
 * which a running player gets fresh data without re-encoding.
 *
 * The one snapshot value folded in here rather than by the caller is a picture-entity slot's
 * `entity_picture` ([cardPictureSignature]): the player fetches a picture by URL once per document,
 * so a new URL (a camera's rotating token or next frame) plays as a new document — a re-encode on
 * the converter path, a fresh decode of the same template bytes on the template path.
 *
 * **Live snapshot push.** When [card] and [snapshot] are supplied, the running player is fed
 * named-binding writes for the entities the card references each time [snapshot] changes. This
 * covers the regression introduced by document caching: without it, the document is encoded once
 * per `(card, theme)` and no value updates ever reach the player. The push:
 * 1. Walks `card.raw` once with [cardEntityIds] to enumerate the entities the card consumes
 *    (`entity:` / `entities:`, including nested cards in stack / conditional / picture-elements).
 * 2. On every [snapshot] change, computes the bindings via [cardSnapshotBindings] and writes only
 *    those that actually changed since the last push (`<id>.state`, `<id>.is_on`) — into the
 *    Compose player's named values, or through the AndroidX `RemoteComposePlayer`'s `setUserLocal*`
 *    setters when that player is drawing.
 *
 * [onDocument] observes the encoded bytes once per document (on a cache hit too, since the caller
 * cares about the document, not about who encoded it). It exists for the preview module, which
 * hands them to the compose-preview render harness so the published catalog carries the Remote
 * Compose data tier next to the baked PNG — see `:previews`' `RcDocumentCapture.kt`. Null (the
 * default) for every in-app caller: no observer, no behaviour change.
 *
 * **Player.** [playback] picks who draws the bytes — see [CardPlayback]. The default,
 * [CardPlayback.Compose], is the Compose Multiplatform `RcComposePlayer` (via [HaRcComposePlayer]):
 * the document becomes real Compose layout nodes, so the slot sizes like any other composable —
 * `fillMaxWidth()` gives an adaptive (wrap-content) height, `Modifier.height(...) / .size(...)`
 * pins it — and animations run on the composition's frame clock rather than the View player's own,
 * which is what makes preview stills deterministic. [CardPlayback.LauncherWidget] keeps the
 * AndroidX View player for previews of the mobile launcher widget, which the platform draws with
 * that player; it is also the per-document fallback when rc-player-compose reports a document it
 * can't fully render.
 *
 * Sizing on the AndroidX path: the document is authored with the wrap-friendly measure path
 * (FEATURE_PAINT_MEASURE = 0, baked by `androidXExperimentalWrap`) and played through
 * [WrapAdaptiveRemoteDocumentPlayer], which primes the View's paint context before Compose measures
 * it — the workaround for https://github.com/yschimke/homeassistant-remotecompose/issues/153.
 */
@OptIn(ExperimentalRemotePlayerApi::class)
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
@Composable
fun CachedCardPreview(
  cacheKey: Any,
  profile: Profile = RcPlatformProfiles.ANDROIDX,
  modifier: Modifier = Modifier,
  card: CardConfig? = null,
  snapshot: HaSnapshot? = null,
  liveBindings: Boolean = true,
  bitmapLoader: BitmapLoader = BitmapLoader.UNSUPPORTED,
  onDocument: ((ByteArray) -> Unit)? = null,
  playback: CardPlayback = CardPlayback.Compose,
  template: CardTemplatePlayback? = null,
  content: @RemoteComposable @Composable () -> Unit,
) {
  // AndroidX path only: flip the View player into wrap-content mode —
  // idempotent assignment. See KDoc above.
  RemoteComposePlayerFlags.shouldPlayerWrapContentSize = true

  val context = LocalContext.current
  val cache = LocalCardDocumentCache.current
  val debugBorders = LocalRcDebugBorders.current
  // A capture bakes the display's density and font scale into its sizes, and the cache outlives
  // them (a display-size or font-size change; a render run's previews of several devices), so
  // they key the document too: otherwise a card replays bytes captured for another display.
  val density = LocalDensity.current
  // Mix the debug-borders flag into the cache key so toggling it
  // re-encodes the document (the wrapper changes the captured bytes).
  val effectiveCacheKey =
    remember(cacheKey, debugBorders, density.density, density.fontScale) {
      val displayed = DisplayKeyedCacheKey(cacheKey, density.density, density.fontScale)
      if (debugBorders) DebugBorderedCacheKey(displayed) else displayed
    }

  val pictureSignature =
    remember(card, snapshot) {
      if (card != null && snapshot != null) cardPictureSignature(card, snapshot) else ""
    }
  val documentKey =
    remember(effectiveCacheKey, pictureSignature) {
      if (pictureSignature.isEmpty()) effectiveCacheKey
      else PictureKeyedCacheKey(effectiveCacheKey, pictureSignature)
    }

  val cardDocument =
    remember(documentKey, template) {
      template?.let {
        // Template bytes never change; a copy is a new document to the player, which decodes it
        // again and fetches its slot pictures from the current snapshot's URLs. The same pictures
        // play the same copy, so a card scrolled back into view isn't decoded again.
        val bytes =
          if (pictureSignature.isEmpty()) it.bytes else pictureCopy(it.bytes, pictureSignature)
        CardDocument(bytes = bytes, widthPx = 0, heightPx = 0)
      }
        ?: cache.get(documentKey)
        ?: harcTrace(HarcTraceSections.CAPTURE_DOCUMENT) {
            runBlocking {
              val captureContent: @RemoteComposable @Composable () -> Unit =
                if (debugBorders) {
                  { DebugRcBorderWrapper { content() } }
                } else {
                  content
                }
              // A constrained capture [profile] (e.g. `widgetsProfile`,
              // the launcher's stricter op set) can reject a card whose
              // ops fall outside its vocabulary by throwing mid-capture.
              // This capture runs synchronously inside composition via
              // `runBlocking`, so an uncaught throw would take down the
              // whole host screen (the card-history preview captures every
              // card with the widgets profile — see CardHistoryScreen).
              // Degrade to an empty document instead: the card renders
              // blank, matching how the launcher shows a widget it can't
              // paint, rather than crashing the screen hosting the preview.
              val captured = runCatching {
                captureSingleRemoteDocument(
                  context = context,
                  profile = profile,
                  content = captureContent,
                )
              }
                .recoverCatching {
                  // First fall back to a blank document under the
                  // requested profile — matches a launcher slot the
                  // profile can't paint.
                  captureSingleRemoteDocument(context = context, profile = profile) {}
                }
                .getOrElse {
                  // The constrained profile rejected even an empty
                  // capture (a blank document has no root ops to admit).
                  // Capture the blank under the unconstrained AndroidX
                  // profile, which never rejects, so the host screen
                  // still composes instead of crashing a second time.
                  captureSingleRemoteDocument(
                    context = context,
                    profile = RcPlatformProfiles.ANDROIDX,
                  ) {}
                }
              CardDocument(bytes = captured.bytes, widthPx = 0, heightPx = 0)
            }
          }
          .also { cache.put(documentKey, it) }
    }
  // A template's time-derived bindings (a clock card's date line) read the same clock the direct
  // render reads, so an injected clock reaches both paths.
  val clock = LocalHaClock.current
  val bindingsFor: (HaSnapshot) -> CardSnapshotBindings =
    template?.let { t -> { snap -> t.bindings(snap, clock) } }
      ?: { snap ->
        cardSnapshotBindings(entityIds(card), snap)
      }

  // Hand the encoded document to an observer once the composition succeeds. `SideEffect` rather
  // than `remember`: publishing from a `remember` block is the `RememberReturnType` lint error,
  // because an abandoned or retried composition would have published anyway. A render harness
  // collecting this still has its per-preview window open in the apply phase.
  SideEffect { onDocument?.invoke(cardDocument.bytes) }

  val dispatcher = LocalHaActionDispatcher.current
  val inputDrafts = remember(card) { ee.schimke.composeai.rcplayer.runtime.RcInputDrafts() }

  val entityIds = remember(card) { entityIds(card) }
  // [liveBindings] is off for cards that refresh by document re-encode (those that declare a
  // `CardConverter.dataSignature`): their dynamic content is baked, not bound, so pushing the
  // entity's raw named bindings would clobber a per-card-formatted `<id>.state` with the plain
  // value. The re-encode (driven by the signature in the cache key) is authoritative for them.
  // A design template carries no values at all, so it always takes the push — every slot binding
  // the client can derive, on every snapshot.
  val pushBindings =
    (liveBindings || template != null) &&
      card != null &&
      snapshot != null &&
      (entityIds.isNotEmpty() || template != null)
  // Track the last value pushed to each player so a redundant snapshot recompose doesn't re-issue
  // identical writes. Reset when [cacheKey] changes so the next push is unconditional.
  // A template swapping in (or out) is a new document too, so it resets the push state as well.
  val pushedNamed = remember(documentKey, template) { HashMap<String, Any?>() }
  val pushedView = remember(documentKey, template) { HashMap<String, Any?>() }
  // Compose player: live values the running document reads by name. Re-created with the document
  // ([cacheKey]) — the new document carries its own initial bake.
  //
  // Seeded with the current snapshot's bindings before the player's first frame. The cached bytes
  // carry the values of their first capture, so a card scrolled back into view would otherwise
  // draw those, then tween every gauge, toggle and label to the live values a frame later — the
  // first frame shows current data, and only a later change animates
  // (docs/architecture/live-card-updates.md § First frame).
  val namedValues =
    remember(documentKey, template) {
      mutableStateMapOf<String, RcNamedValue>().also { values ->
        if (pushBindings) {
          pushSnapshotBindings(NamedValueWriter(values), bindingsFor(snapshot!!), pushedNamed)
        }
      }
    }
  // AndroidX player (launcher-widget previews, per-document fallback): the View handle survives
  // recomposition but is replaced if the player is torn down and rebuilt (cache invalidation,
  // theme flip, resize).
  val playerHolder = remember { mutableStateOf<RemoteComposePlayer?>(null) }

  if (pushBindings && snapshot != null) {
    // A relative time ("3 h ago", see `isRelativeTimeState`) moves with the clock, not the
    // entity: while the card shows one, the host's tick (`LocalRelativeTimeTick`) re-runs the
    // push. Only changed values are written, so a tick that rolls no label over writes nothing.
    // Keyed on the injected clock too: a template's time-derived bindings change with it.
    val tick = relativeTimeTickFor(entityIds, snapshot)
    LaunchedEffect(namedValues, snapshot, tick, clock) {
      pushSnapshotBindings(NamedValueWriter(namedValues), bindingsFor(snapshot), pushedNamed)
    }
    LaunchedEffect(playerHolder.value, snapshot, tick, clock) {
      val updater = playerHolder.value ?: return@LaunchedEffect
      pushSnapshotBindings(ViewPlayerWriter(updater), bindingsFor(snapshot), pushedView)
    }
  }

  // The player may keep the first callback it was given (same bytes, new card or snapshot); read
  // both at tap time.
  val latestSnapshot = rememberUpdatedState(snapshot)
  val latestTemplate = rememberUpdatedState(template)
  // A template's images resolve through the card's own entities; nothing else it names is fetched.
  val playerBitmapLoader =
    remember(bitmapLoader, template != null) {
      if (template == null) bitmapLoader
      else
        BitmapLoader { url ->
          val real =
            latestTemplate.value?.bitmapUrl?.invoke(url, latestSnapshot.value)
              ?: throw java.io.IOException("template image not resolvable: $url")
          bitmapLoader.loadBitmap(real)
        }
    }
  val onNamedAction: (String, Any?) -> Unit = { name, value ->
    if (name == HA_ACTION_NAME) {
      val current = latestTemplate.value
      if (current != null) {
        // A template is remote content: never dispatch an action it encodes. It only picks which
        // of this card's own, locally resolved actions runs.
        dispatcher.dispatch(current.resolveTap(decodeHaAction(value), latestSnapshot.value))
      } else {
        decodeHaAction(value)?.let(dispatcher::dispatch)
      }
    }
  }
  // The AndroidX View player, primed so `modifier` can be wrap-content on the height axis (#153)
  // while pinned EXACTLY constraints still dominate the View's measure. Accessibility services and
  // checkers see that View, not the document's semantics inside it, so it carries the card's name.
  val playerLabel = remember(card, snapshot) { playerLabel(card, snapshot) }
  val androidXPlayer: @Composable () -> Unit = {
    WrapAdaptiveRemoteDocumentPlayer(
      documentBytes = cardDocument.bytes,
      modifier = modifier,
      bitmapLoader = playerBitmapLoader,
      init = { player -> playerHolder.value = player },
      onNamedAction = onNamedAction,
      contentDescription = playerLabel,
    )
  }
  when (playback) {
    CardPlayback.Compose ->
      HaRcComposePlayer(
        documentBytes = cardDocument.bytes,
        modifier = modifier,
        bitmapLoader = playerBitmapLoader,
        namedValues = namedValues,
        onNamedAction = onNamedAction,
        fallback = androidXPlayer,
        inputDrafts = inputDrafts,
        onInputSubmit = { action, values ->
          if (card != null && snapshot != null) {
            if (
              action.startsWith(ee.schimke.ha.model.MESSAGE_SUBMIT + ":") &&
                card.type == ee.schimke.ha.model.MESSAGE_CARD_TYPE
            ) {
              decodeHaAction(ee.schimke.ha.model.messageAction(card, snapshot, values).toString())
                ?.let(dispatcher::dispatch)
            } else if (
              card.type == "entities" && action.startsWith(ee.schimke.ha.model.MESSAGE_SUBMIT + ":")
            ) {
              val configured =
                decodeHaAction(action.removePrefix(ee.schimke.ha.model.MESSAGE_SUBMIT + ":"))
                  as? ee.schimke.ha.rc.components.HaAction.CallService
              val id = configured?.entityId
              val value = values[id]
              if (id != null && value != null && id in cardEntityIds(card)) {
                val domain = id.substringBefore('.')
                val options =
                  (snapshot.states[id]?.attributes?.get("options")
                      as? kotlinx.serialization.json.JsonArray)
                    ?.map { (it as kotlinx.serialization.json.JsonPrimitive).content }
                    .orEmpty()
                if (
                  value.length <= 4096 &&
                    (domain == "input_text" ||
                      (domain in setOf("select", "input_select") && value in options))
                ) {
                  dispatcher.dispatch(
                    ee.schimke.ha.rc.components.HaAction.CallService(
                      domain,
                      if (domain == "input_text") "set_value" else "select_option",
                      id,
                      kotlinx.serialization.json.buildJsonObject {
                        put(
                          if (domain == "input_text") "value" else "option",
                          kotlinx.serialization.json.JsonPrimitive(value),
                        )
                      },
                    )
                  )
                }
              }
            }
          }
        },
      )
    CardPlayback.LauncherWidget -> androidXPlayer()
  }
}

/**
 * Per template array, the copy last played for one set of slot pictures. A rotating picture (a
 * camera's token) replaces it; keyed weakly by the template, so a dropped template takes it along.
 */
private val pictureCopies = java.util.WeakHashMap<ByteArray, Pair<String, ByteArray>>()

/** [template]'s copy for [pictures]: the one played before when the pictures haven't changed. */
private fun pictureCopy(template: ByteArray, pictures: String): ByteArray =
  synchronized(pictureCopies) {
    pictureCopies[template]?.takeIf { it.first == pictures }?.second
      ?: template.copyOf().also { pictureCopies[template] = pictures to it }
  }

/**
 * Wrapper key that distinguishes a debug-bordered render of [inner] from the plain render — same
 * card YAML, different bytes (the wrapping `DebugRcBorderWrapper` is captured into the document).
 */
private data class DebugBorderedCacheKey(val inner: Any)

/** [inner] with its picture-entity slots' current URLs ([cardPictureSignature]): a new picture. */
private data class PictureKeyedCacheKey(val inner: Any, val pictures: String)

/** [inner] as captured on a display of [density] and [fontScale]. */
private data class DisplayKeyedCacheKey(val inner: Any, val density: Float, val fontScale: Float)

/** Where [pushSnapshotBindings] writes: the Compose player's named values or the View player. */
internal interface BindingWriter {
  fun string(name: String, value: String)

  fun int(name: String, value: Int)

  fun float(name: String, value: Float)

  /** Called once after a push that changed at least one binding. */
  fun flush() {}
}

/**
 * Compose player: plain names (`<id>.state`); rc-player resolves them against the document's
 * `USER:` named variables, like the View player's `setUserLocal*` setters do.
 */
internal class NamedValueWriter(private val values: MutableMap<String, RcNamedValue>) :
  BindingWriter {
  override fun string(name: String, value: String) {
    values[name] = RcNamedValue.Text(value)
  }

  override fun int(name: String, value: Int) {
    values[name] = RcNamedValue.Integer(value)
  }

  override fun float(name: String, value: Float) {
    values[name] = RcNamedValue.FloatValue(value)
  }
}

private class ViewPlayerWriter(private val updater: RemoteComposePlayer) : BindingWriter {
  override fun string(name: String, value: String) = updater.setUserLocalString(name, value)

  override fun int(name: String, value: Int) = updater.setUserLocalInt(name, value)

  override fun float(name: String, value: Float) = updater.setUserLocalFloat(name, value)

  override fun flush() = refreshBoundPlayerViews(updater)
}

/**
 * Push the diff between the previous push (tracked in [pushed]) and the current [snapshot] for the
 * named bindings of [entityIds] into [writer]. Catches per-binding failures so a single unknown
 * name doesn't take down the whole push (the View player rejects writes for names not declared in
 * the document).
 *
 * Boolean bindings are pushed as ints — `LiveValues.isOn` creates a `RemoteBoolean` which Remote
 * Compose stores internally as a `RemoteInt` (0/1), so both players see it on the int channel. Int
 * (`<id>.state_int`) and float (`<id>.numeric_state`) bindings go through their own typed setters.
 */
internal fun pushSnapshotBindings(
  writer: BindingWriter,
  entityIds: Set<String>,
  snapshot: HaSnapshot,
  pushed: MutableMap<String, Any?>,
) = pushSnapshotBindings(writer, cardSnapshotBindings(entityIds, snapshot), pushed)

internal fun pushSnapshotBindings(
  writer: BindingWriter,
  bindings: CardSnapshotBindings,
  pushed: MutableMap<String, Any?>,
) {
  var changed = false
  fun write(name: String, value: Any?, block: () -> Unit) {
    if (pushed[name] == value) return
    runCatching(block).onSuccess {
      pushed[name] = value
      changed = true
    }
  }
  for ((name, value) in bindings.strings) write(name, value) { writer.string(name, value) }
  for ((name, value) in bindings.booleans) {
    write(name, value) { writer.int(name, if (value) 1 else 0) }
  }
  for ((name, value) in bindings.ints) write(name, value) { writer.int(name, value) }
  for ((name, value) in bindings.floats) write(name, value) { writer.float(name, value) }
  if (changed) writer.flush()
}

private fun refreshBoundPlayerViews(view: View) {
  if (view is RemoteComposeView) {
    val context = view.remoteContext
    val previousMode = context.mode
    context.mode = RemoteContext.ContextMode.DATA
    try {
      // Alpha21 keeps expressions in the root container. Its paint pass skips those data ops,
      // so evaluate dirty bindings before painting, without registering listeners again.
      applyDirtyDataOperations(view.document.document.operations, context)
    } finally {
      context.mode = previousMode
    }
  }
  view.invalidate()
  if (view is ViewGroup) {
    for (index in 0 until view.childCount) refreshBoundPlayerViews(view.getChildAt(index))
  }
}

private fun applyDirtyDataOperations(operations: List<Operation>, context: RemoteContext) {
  for (operation in operations) {
    if (operation is Container) {
      applyDirtyDataOperations(operation.list, context)
    } else if (operation is VariableSupport && operation.isDirty) {
      operation.markNotDirty()
      operation.updateVariables(context)
      operation.apply(context)
    }
  }
}

private fun entityIds(card: CardConfig?): Set<String> = card?.let { cardEntityIds(it) }.orEmpty()

/**
 * What a card is called, for the View player that hosts it: its `title` or `name`, else its first
 * entity's friendly name, else its type.
 */
private fun playerLabel(card: CardConfig?, snapshot: HaSnapshot?): String? {
  if (card == null) return null
  fun text(key: String): String? =
    (card.raw[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
  val entity = cardEntityIds(card).firstOrNull()
  val friendlyName = entity?.let {
    snapshot?.states?.get(it)?.attributes?.get("friendly_name") as? JsonPrimitive
  }
  return text("title")
    ?: text("name")
    ?: friendlyName?.contentOrNull?.takeIf { it.isNotBlank() }
    ?: entity
    ?: card.type
}

/**
 * Plays a card design template in place of a local capture. [bytes] is a values-free document from
 * the design server (or cache), and [bindings] derives every slot binding from a snapshot, reading
 * "now" from the given [HaClock] (the [LocalHaClock] in scope, when played).
 *
 * Taps are resolved from the card's **local** config, never from the template: the bytes are
 * remote, so the action a template encodes only picks *which* of the card's own actions runs. A tap
 * on a slot's entity (`<domain>.s<N>`) runs [slotTapActions]`[N]` — one per row of a list card — —
 * unless the converter rebuilds a more specific one from the card's config ([slotAction]) — and any
 * other tap runs the card's [tapAction].
 */
class CardTemplatePlayback(
  val bytes: ByteArray,
  val bindings: (snapshot: HaSnapshot, clock: HaClock) -> CardSnapshotBindings,
  val tapAction: HaAction,
  val slotTapActions: List<HaAction> = emptyList(),
  /** A card-specific action for a tap on a slot: see [CardConverter.designAction]. */
  /**
   * The URL to fetch for an image URL the template encodes, or null to refuse it: only the card's
   * own slot pictures resolve (`CardDesignSlots.pictureUrl`).
   */
  val bitmapUrl: (url: String, snapshot: HaSnapshot?) -> String? = { _, _ -> null },
  val slotAction: (snapshot: HaSnapshot?, slot: Int, templateAction: HaAction) -> HaAction? =
    { _, _, _ ->
      null
    },
) {
  /** The card-local action for a tap whose template-encoded action is [templateAction]. */
  fun resolveTap(templateAction: HaAction?, snapshot: HaSnapshot? = null): HaAction {
    if (templateAction == null) return tapAction
    // A tap on no slot (a card-level control) may still pick one of the card's own actions.
    val slot =
      actionEntityId(templateAction)?.let(::slotIndexOf)
        ?: return slotAction(snapshot, NO_SLOT, templateAction) ?: tapAction
    if (slot !in slotTapActions.indices) return tapAction
    return slotAction(snapshot, slot, templateAction) ?: slotTapActions[slot]
  }

  companion object {
    /** The slot passed to [slotAction] for a tap that names no slot entity. */
    const val NO_SLOT: Int = -1
  }

  private fun slotIndexOf(entityId: String): Int? =
    entityId.substringAfter('.', "").takeIf { it.startsWith("s") }?.drop(1)?.toIntOrNull()

  private fun actionEntityId(action: HaAction): String? =
    when (action) {
      is HaAction.CallService -> action.entityId
      is HaAction.Toggle -> action.entityId
      is HaAction.MoreInfo -> action.entityId
      is HaAction.AlarmKey -> action.entityId
      is HaAction.AlarmPin -> action.entityId
      is HaAction.AlarmIntent -> action.entityId
      is HaAction.Navigate,
      is HaAction.Url,
      HaAction.None -> null
    }
}
