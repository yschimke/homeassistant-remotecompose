@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.remote.player.core.platform.BitmapLoader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import ee.schimke.composeai.rcplayer.compose.LocalRcTimeSource
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.compose.RcImageLoader
import ee.schimke.composeai.rcplayer.compose.composeSupportReport
import ee.schimke.composeai.rcplayer.compose.rememberRcNamedValues
import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.runtime.RcHostActionValue
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.composeai.rcplayer.runtime.RcPlayerEvent
import ee.schimke.composeai.rcplayer.runtime.RcTimeSnapshot
import ee.schimke.composeai.rcplayer.runtime.RcTimeSource
import ee.schimke.terrazzo.shared.RcErrorBoundary
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Which player draws a card's captured Remote Compose document.
 *
 * Both play the identical bytes; the choice is only who interprets them.
 */
enum class CardPlayback {
  /**
   * The Compose Multiplatform player (`RcComposePlayer` from `rc-player-compose`). The default for
   * every in-app and preview surface: it lays the document out as real Compose nodes, so
   * wrap-content height comes from ordinary Compose measurement (no #153 workaround), and it
   * animates on the composition's frame clock, so a preview still is deterministic.
   */
  Compose,

  /**
   * The AndroidX View player (`RemoteComposePlayer`) at the slot's EXACT size, via
   * [WrapAdaptiveRemoteDocumentPlayer]. Reserved for surfaces that specifically preview the
   * **mobile launcher widget**: the real widget is `RemoteViews(DrawInstructions)` drawn by the
   * platform `RemoteComposePlayer`, so its previews must use the same player to show what the home
   * screen will draw.
   */
  LauncherWidget,
}

/**
 * Play [documentBytes] with the Compose Multiplatform `RcComposePlayer`, wired to this app's
 * host-side contracts:
 * - [bitmapLoader] resolves URL / file images. A loader that is also an [RcImageLoader] (the
 *   `rc-image-coil` `CoilBitmapLoader`) is used directly; any other [BitmapLoader] is adapted with
 *   a single blocking `loadBitmap` call off the main thread.
 * - [namedValues] carries live binding writes (`<entity>.state`, …) into the running document
 *   without re-creating it, so document animations are not restarted.
 * - [onNamedAction] receives the document's named host actions (`ha` + JSON payload).
 * - A frozen [LocalHaClock] (previews, tests) pins the player's time source too, so time-driven
 *   expressions resolve to the same instant the converters encoded, and decodes a plain
 *   [BitmapLoader]'s images inline rather than on `Dispatchers.IO`, so a capture never races them.
 *
 * **Per-document fallback.** If rc-player-compose's own capability report says it can't fully
 * render a document, [fallback] draws it instead (the AndroidX player) and the reasons are logged —
 * a gap is never papered over silently.
 */
@Composable
fun HaRcComposePlayer(
  documentBytes: ByteArray,
  modifier: Modifier = Modifier,
  bitmapLoader: BitmapLoader = BitmapLoader.UNSUPPORTED,
  imageEpoch: Any? = null,
  namedValues: SnapshotStateMap<String, RcNamedValue> = rememberRcNamedValues(),
  onNamedAction: (name: String, value: Any?) -> Unit = { _, _ -> },
  fallback: @Composable () -> Unit,
  inputDrafts: ee.schimke.composeai.rcplayer.runtime.RcInputDrafts = remember {
    ee.schimke.composeai.rcplayer.runtime.RcInputDrafts()
  },
  onInputSubmit: (String, Map<String, String>) -> Unit = { _, _ -> },
) {
  val error = remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
  // The player only skips a recomposition when every argument is equal to the last, and a host
  // recomposes this on every data change (its caller's snapshot moved). So nothing handed to the
  // player is rebuilt per composition: a new registry, event lambda or modifier would make the
  // player recompose every component of the card, though the new values reach it through
  // [namedValues] alone.
  val submit by androidx.compose.runtime.rememberUpdatedState(onInputSubmit)
  val editing = LocalHaInputEditing.current
  val inputs =
    remember(inputDrafts, editing) {
      ee.schimke.composeai.rcplayer.compose.rcInputComponents(
        inputDrafts,
        editing,
        onSubmit = { action, values ->
          runCatching { submit(action, values) }
            .onSuccess { error.value = null }
            .onFailure { error.value = it.message ?: "Message was not sent" }
        },
      )
    }
  val namedAction by androidx.compose.runtime.rememberUpdatedState(onNamedAction)
  val onEvent: (RcPlayerEvent) -> Unit = remember {
    { event ->
      if (event is RcPlayerEvent.HostNamedAction) {
        namedAction(event.name, event.value.toHostPayload())
      }
    }
  }
  val decoded = remember(documentBytes) { decodedForComposePlayer(documentBytes) }
  val document = decoded.document
  if (document == null) {
    fallback()
    return
  }
  // One player per card, across documents: a new document (a clone with a new picture, a
  // re-encode) plays in the same player, which keeps drawing the last document's pictures and size
  // until its own load, so a changed card swaps in place rather than blinking or collapsing. A
  // clone ([cloneForPlayer]) shares its source's bytes but decodes apart, so the player still sees
  // a new document with its own image fetches.
  // A document that throws while it plays is disabled, and an atomic-symbol warning takes its place
  // (RcErrorBoundary); the rest of the dashboard carries on.
  RcErrorBoundary(
    documentBytes,
    modifier,
    namedValues = { namedValues.filter { (name, value) -> decoded.declared.accepts(name, value) } },
  ) { fail ->
    run {
      val clock = LocalHaClock.current
      // A frozen clock means a preview or test: decode inline, so the image is in place by the
      // frame a capture waits on rather than landing from a background thread after it.
      val baseImageLoader =
        remember(bitmapLoader, clock.isFrozen) {
          bitmapLoader.asRcImageLoader(inline = clock.isFrozen)
        }
      // The player fetches a document's images once per (document, loader). A new loader identity
      // fetches them again, drawing the last ones until they land: for a new [imageEpoch] (the
      // host's picture URLs moved, the document didn't), and for a picture clone
      // (cloneForPlayer), which shares its source's RcDocument but decodes apart.
      val imageLoader =
        remember(baseImageLoader, decoded, imageEpoch) { ReloadingImageLoader(baseImageLoader) }
      // rc-player rejects a write to a name the document doesn't declare (the View player's setters
      // dropped it), and the host pushes every binding of every entity the card references. So the
      // player gets only the values this document declares, with a matching type.
      val declared = decoded.declared
      // Seeded before the player's first frame, so it starts settled on the live values: a value
      // that
      // only arrives through the effect below lands a frame late and tweens in from the document's
      // baked one on every load (docs/architecture/live-card-updates.md § First frame).
      val playerValues =
        remember(document) {
          mutableStateMapOf<String, RcNamedValue>().apply {
            putAll(namedValues.filter { (name, value) -> declared.accepts(name, value) })
          }
        }
      LaunchedEffect(playerValues, namedValues) {
        snapshotFlow { namedValues.toMap() }
          .collect { values ->
            val accepted = values.filter { (name, value) -> declared.accepts(name, value) }
            playerValues.keys.retainAll(accepted.keys)
            playerValues.putAll(accepted)
          }
      }
      val frozenTime = remember(clock) { if (clock.isFrozen) clock.asRcTimeSource() else null }
      val guardedEvent: (RcPlayerEvent) -> Unit =
        remember(fail) { { event -> runCatching { onEvent(event) }.onFailure(fail) } }
      val player: @Composable () -> Unit = {
        RcComposePlayer(
          document,
          imageLoader,
          customComponents = inputs,
          modifier = WrapUnpinnedHeight,
          namedValues = playerValues,
          onEvent = guardedEvent,
        )
      }
      if (frozenTime != null) {
        CompositionLocalProvider(LocalRcTimeSource provides frozenTime, content = player)
      } else {
        player()
      }
    }
  }
  error.value?.let { androidx.compose.foundation.text.BasicText(it) }
}

/**
 * Wrap-content height for a slot whose height the caller didn't pin: measure the player with an
 * unbounded height so the document reports its content height, then clamp to the slot's bounds. A
 * pinned (EXACTLY) height passes straight through.
 */
private val WrapUnpinnedHeight: Modifier = Modifier.layout { measurable, constraints ->
  if (constraints.hasFixedHeight) {
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
  } else {
    val placeable = measurable.measure(constraints.copy(maxHeight = Constraints.Infinity))
    val height = placeable.height.coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(placeable.width, height) { placeable.place(0, 0) }
  }
}

/**
 * A document decoded for the Compose player, with the named variables it declares. Compared by
 * identity: it is what the player keys its state on, so two decodes of one document play apart.
 */
internal class DecodedForComposePlayer
private constructor(val document: RcDocument?, val declared: Map<String, Int>) {
  constructor(document: RcDocument?) : this(document, document?.declaredNamedVariables().orEmpty())

  /** Another play of the same document: shares the decode, nothing else. */
  fun clone(): DecodedForComposePlayer = DecodedForComposePlayer(document, declared)
}

/**
 * Decoded documents by their bytes' own array. A card that scrolls (back) into view plays the same
 * array it played before: the document cache's, or a design template shared by every card of its
 * key. Decoding one and checking it is renderable costs about a millisecond of main thread on a
 * desktop JVM, several on a phone, and a section's cards all compose in the same frame, so without
 * this a dashboard dropped frames each time a section came on screen. [RcDocument] is immutable, so
 * players share one. Weak keys: a document goes with the last array that holds it.
 */
private val decodedDocuments = java.util.WeakHashMap<ByteArray, DecodedForComposePlayer>()

/**
 * Decodes [documentBytes] for the Compose player ahead of time, off the main thread, so the first
 * frame that plays the same array finds it decoded. A host warming the cards it is about to show
 * calls this with the bytes it will play.
 */
fun preloadForComposePlayer(documentBytes: ByteArray) {
  decodedForComposePlayer(documentBytes)
}

/**
 * Clones by their own array, each with the array it copies. Weak keys: a clone goes with the last
 * player holding it, and keeps its source alive no longer than that.
 */
private val cloneSources = java.util.WeakHashMap<ByteArray, ByteArray>()

/**
 * A copy of [source] that plays as a new document (its own player state, its slot pictures fetched
 * afresh) while sharing [source]'s decode: an array copy and a map entry, rather than a decode on
 * the main thread. [RcDocument] is immutable, so every clone plays the one decoded document.
 */
fun cloneForPlayer(source: ByteArray): ByteArray =
  source.copyOf().also { clone -> synchronized(cloneSources) { cloneSources[clone] = source } }

/**
 * [decodeForComposePlayer], decoded once per array ([decodedDocuments]), and once per source for a
 * [cloneForPlayer] copy.
 */
internal fun decodedForComposePlayer(bytes: ByteArray): DecodedForComposePlayer {
  synchronized(decodedDocuments) {
    decodedDocuments[bytes]?.let {
      return it
    }
  }
  val source = synchronized(cloneSources) { cloneSources[bytes] }
  val decoded =
    source?.let { decodedForComposePlayer(it).clone() }
      ?: harcTrace(HarcTraceSections.DECODE_DOCUMENT) {
        DecodedForComposePlayer(decodeForComposePlayer(bytes))
      }
  synchronized(decodedDocuments) { decodedDocuments[bytes] = decoded }
  return decoded
}

/**
 * Decode [bytes] for the Compose player, or null when the player reports it can't fully render the
 * document (or can't decode it at all). External (URL / file) images count as renderable: the host
 * resolves them through the image loader.
 */
internal fun decodeForComposePlayer(bytes: ByteArray): RcDocument? {
  val document =
    runCatching { RcDocumentCodec.decode(bytes) }
      .onFailure { Log.w(TAG, "rc-player-compose could not decode the document", it) }
      .getOrNull() ?: return null
  val report =
    runCatching {
      document.composeSupportReport(
        allowExternalImagePlaceholders = true,
        availableCustomComponents = ee.schimke.composeai.rcplayer.protocol.RcInput.configs,
      )
    }
      .onFailure { Log.w(TAG, "rc-player-compose capability report failed", it) }
      .getOrNull() ?: return null
  if (!report.fullyRenderable) {
    Log.w(TAG, "Falling back to the AndroidX player: ${report.issues}")
    return null
  }
  return document
}

/** Named variables [RcDocument] declares, by name, with their [RcNamedVariable] type. */
internal fun RcDocument.declaredNamedVariables(): Map<String, Int> =
  operations.filterIsInstance<RcNamedVariable>().associate { it.name to it.type }

/**
 * Whether rc-player will accept [value] for host name [name]: the document declares it (bare, or in
 * the `USER:` domain the View player's `setUserLocal*` setters write to) with a matching type.
 */
internal fun Map<String, Int>.accepts(name: String, value: RcNamedValue): Boolean {
  val type = this[name] ?: this["USER:$name"] ?: return false
  return when (value) {
    is RcNamedValue.Text -> type == RcNamedVariable.STRING_TYPE
    is RcNamedValue.Integer -> type == RcNamedVariable.INT_TYPE
    is RcNamedValue.FloatValue -> type == RcNamedVariable.FLOAT_TYPE
    else -> true
  }
}

/** Convert a CMP host-action payload to the plain value the HA action decoder expects. */
internal fun RcHostActionValue.toHostPayload(): Any? =
  when (this) {
    is RcHostActionValue.TextValue -> value
    is RcHostActionValue.IntegerValue -> value
    is RcHostActionValue.FloatValue -> value
    is RcHostActionValue.FloatListValue -> value
    else -> null
  }

/**
 * Adapt an AndroidX [BitmapLoader] to the CMP player's image API. A loader that already implements
 * [RcImageLoader] (the Coil loader, which can await a network fetch) is returned as-is.
 *
 * The blocking `loadBitmap` call runs on `Dispatchers.IO`, unless [inline]: then it runs on the
 * player's own (main) dispatcher. Previews and tests pass [inline] so the image is decoded before
 * the frame they capture. On `Dispatchers.IO` nothing they wait on waits for it, so a busy render
 * run captured the placeholder some of the time.
 */
internal fun BitmapLoader.asRcImageLoader(inline: Boolean = false): RcImageLoader =
  this as? RcImageLoader ?: BlockingBitmapLoaderAdapter(this, inline)

private class BlockingBitmapLoaderAdapter(
  private val delegate: BitmapLoader,
  private val inline: Boolean,
) : RcImageLoader {
  override suspend fun load(data: RcBitmapData): ImageBitmap? {
    if (delegate === BitmapLoader.UNSUPPORTED) return null
    val name = data.externalImageName() ?: return null
    val decode = {
      delegate.loadBitmap(name).use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
    }
    return try {
      if (inline) decode() else withContext(Dispatchers.IO) { decode() }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Same degradation as the AndroidX player: keep the placeholder.
      Log.w(TAG, "loadBitmap failed for $name", e)
      null
    }
  }
}

/** The URL / path an external [RcBitmapData] references, or null for an inline image. */
internal fun RcBitmapData.externalImageName(): String? =
  when (encoding) {
    RcBitmapData.ENCODING_URL,
    RcBitmapData.ENCODING_FILE -> data.decodeToString()
    else -> null
  }

/** Pin rc-player's clock to a frozen [HaClock] so the player and the converters agree on "now". */
internal fun HaClock.asRcTimeSource(): RcTimeSource {
  val clock = this
  return object : RcTimeSource {
    override fun currentTimeMillis(): Long = clock.now().toEpochMilli()

    override fun snapshot(epochMillis: Long): RcTimeSnapshot {
      val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), clock.zone())
      return RcTimeSnapshot(
        epochMillis,
        t.year,
        t.monthValue,
        t.dayOfMonth,
        t.dayOfYear,
        t.hour,
        t.minute,
        t.second,
        t.dayOfWeek.value,
        t.offset.totalSeconds,
      )
    }
  }
}

private const val TAG = "HaRcComposePlayer"

/** [delegate] under a new identity: a new image load for the player, same loading. */
private class ReloadingImageLoader(private val delegate: RcImageLoader) : RcImageLoader {
  override suspend fun load(bitmap: RcBitmapData): ImageBitmap? = delegate.load(bitmap)

  override fun cached(bitmap: RcBitmapData): ImageBitmap? = delegate.cached(bitmap)
}
