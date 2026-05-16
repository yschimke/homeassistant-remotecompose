@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import android.util.Log
import androidx.annotation.RestrictTo
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.profile.Profile
import androidx.compose.remote.creation.profile.RcPlatformProfiles
import androidx.compose.remote.player.compose.ExperimentalRemotePlayerApi
import androidx.compose.remote.player.compose.RemoteComposePlayerFlags
import androidx.compose.remote.player.core.platform.BitmapLoader
import androidx.compose.remote.player.core.state.StateUpdater
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.HA_ACTION_NAME
import ee.schimke.ha.rc.components.PICTURE_BITMAP_SIZE_PX
import ee.schimke.ha.rc.components.pictureBindingName
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.contentOrNull

private const val TAG = "CachedCardPreview"

/**
 * Cached counterpart to upstream
 * `androidx.compose.remote.tooling.preview.RemotePreview`.
 *
 * The difference that matters: this version keys the captured `.rc`
 * bytes on a caller-supplied [cacheKey] and stores hits in
 * [LocalCardDocumentCache]. Two calls with the same key skip the
 * encode entirely and play the cached bytes — even across Activity
 * recreation, because the cache is process-scoped.
 *
 * Cache-key contract: include everything that bakes into the document
 * (the card YAML, theme colours / dark flag, profile), and nothing
 * that the host can update by named binding (entity state, attributes,
 * `is_on`). Snapshot data is read by [content] on capture but must
 * NOT be part of the key — `LiveValues.state` / `.attribute` /
 * `.isOn` (in rc-components) are exactly the seam through which a
 * running player gets fresh data without re-encoding.
 *
 * **Live snapshot push.** When [card] and [snapshot] are supplied,
 * the running player is fed named-binding writes for the entities
 * the card references each time [snapshot] changes. This covers the
 * regression introduced by document caching: without it, the document
 * is encoded once per `(card, theme)` and no value updates ever reach
 * the player. The push:
 *   1. Walks `card.raw` once with [cardEntityIds] to enumerate the
 *      entities the card consumes (`entity:` / `entities:`, including
 *      nested cards in stack / conditional / picture-elements).
 *   2. Captures the player's [StateUpdater] via
 *      `WrapAdaptiveRemoteDocumentPlayer(init = ...)`.
 *   3. On every [snapshot] change, computes the bindings via
 *      [cardSnapshotBindings] and writes only those that actually
 *      changed since the last push (`<id>.state`, `<id>.is_on`).
 *
 * Sizing model: the captured document is authored with the
 * wrap-friendly measure path (FEATURE_PAINT_MEASURE = 0, baked by
 * `androidXExperimentalWrap`). Playback goes through
 * [WrapAdaptiveRemoteDocumentPlayer], which warms up the
 * `RemoteComposePlayer`'s paint context with one off-screen draw at
 * the parent's max constraints, then re-measures. This unblocks the
 * alpha010 wrap-h bug (the `RemoteComposeView` skips the document's
 * measure pass until paint context is set, causing a
 * `Modifier.fillMaxWidth()`-only host to balloon to the authored
 * canvas height) — see
 * https://github.com/yschimke/homeassistant-remotecompose/issues/153.
 * Callers can therefore size the slot via [modifier] alone:
 * `fillMaxWidth()` for adaptive height, or `Modifier.height(...) /
 * .size(...)` for an EXACTLY constraint that pins the slot.
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
    bitmapLoader: BitmapLoader = BitmapLoader.UNSUPPORTED,
    content: @RemoteComposable @Composable () -> Unit,
) {
    // Flip the player into wrap-content mode — idempotent assignment.
    // Today this only matters when the host's parent is bounded
    // (EXACTLY); see KDoc above. Kept on so future alpha bumps that
    // make wrap-content work for unbounded parents pick it up.
    RemoteComposePlayerFlags.shouldPlayerWrapContentSize = true

    val context = LocalContext.current
    val cache = LocalCardDocumentCache.current
    val debugBorders = LocalRcDebugBorders.current
    // Mix the debug-borders flag into the cache key so toggling it
    // re-encodes the document (the wrapper changes the captured bytes).
    val effectiveCacheKey =
        remember(cacheKey, debugBorders) {
            if (debugBorders) DebugBorderedCacheKey(cacheKey) else cacheKey
        }

    val cardDocument =
        remember(effectiveCacheKey) {
            cache.get(effectiveCacheKey)
                ?: runBlocking {
                        val captured =
                            captureSingleRemoteDocument(
                                context = context,
                                profile = profile,
                                content =
                                    if (debugBorders) {
                                        { DebugRcBorderWrapper { content() } }
                                    } else {
                                        content
                                    },
                            )
                        CardDocument(bytes = captured.bytes, widthPx = 0, heightPx = 0)
                    }
                    .also { cache.put(effectiveCacheKey, it) }
        }

    val dispatcher = LocalHaActionDispatcher.current
    val imageResolver = LocalRemoteImageResolver.current

    val entityIds = remember(card) { card?.let { cardEntityIds(it) }.orEmpty() }
    val pictureEntityIds =
        remember(card) {
            val ids = card?.let { cardPictureEntityIds(it) }.orEmpty()
            if (ids.isNotEmpty()) {
                Log.d(
                    TAG,
                    "card type=${card?.type} picture-entities=$ids " +
                        "resolverWired=${imageResolver != null}",
                )
            }
            ids
        }
    // The handles survive recomposition but get replaced if the player
    // is torn down and rebuilt (cache invalidation, theme flip).
    val updaterHolder = remember { mutableStateOf<StateUpdater?>(null) }
    // Hold the player View so the picture-entity push can call
    // `invalidate()` after a `setUserLocalBitmap` — that override
    // updates the named-data map but doesn't itself mark the View
    // dirty, so without an explicit invalidate the previous frame
    // sticks on screen.
    val playerHolder = remember {
        mutableStateOf<androidx.compose.remote.player.view.RemoteComposePlayer?>(null)
    }
    // Tracks the last value we pushed for each binding so a redundant
    // snapshot recompose doesn't re-issue identical writes. Reset when
    // [cacheKey] changes — the new document carries its own initial
    // bake, and we want the next push to be unconditional.
    val pushed = remember(cacheKey) { HashMap<String, Any?>() }
    // Picture-entity URLs we've already fetched + pushed. Distinct map
    // because the channel (setUserLocalBitmap) is separate from the
    // string / int channel above and the values are URLs, not the
    // bitmaps themselves (we don't want to retain bitmap references).
    val pushedPictureUrls = remember(cacheKey) { HashMap<String, String>() }

    if (card != null && snapshot != null && entityIds.isNotEmpty()) {
        LaunchedEffect(updaterHolder.value, snapshot) {
            val updater = updaterHolder.value ?: return@LaunchedEffect
            pushSnapshotBindings(updater, entityIds, snapshot, pushed)
        }
    }

    // Live bitmap push for picture-entity cards. Distinct from the
    // string / boolean push above because resolving a URL to bytes is
    // suspending (Coil fetch), so we can't fold it into the
    // synchronous binding loop. Skips quietly when no resolver is
    // wired — call sites that don't provide one (previews, host-less
    // tests) fall back to whatever the player's BitmapLoader returns
    // for the URL baked into the document.
    if (
        card != null &&
            snapshot != null &&
            pictureEntityIds.isNotEmpty() &&
            imageResolver != null
    ) {
        LaunchedEffect(updaterHolder.value, snapshot) {
            val updater = updaterHolder.value ?: return@LaunchedEffect
            val didPush =
                pushPictureEntityBitmaps(
                    updater = updater,
                    entityIds = pictureEntityIds,
                    snapshot = snapshot,
                    pushedUrls = pushedPictureUrls,
                    resolver = imageResolver,
                )
            if (didPush) {
                // `setUserLocalBitmap` writes through `setNamedDataOverride`
                // which mutates the named-data map but doesn't invalidate
                // the player View — without this call the View keeps
                // painting the doc's baked placeholder. See #264.
                playerHolder.value?.invalidate()
            }
        }
    }

    // Use the wrap-adaptive player (a primed `RemoteComposePlayer`
    // View that has had its paint context warmed up before Compose
    // measures it). Lets `modifier` be wrap-content on the height
    // axis without the alpha010 bug ballooning the slot to the
    // authored canvas size; pinned EXACTLY constraints continue to
    // dominate the inner View's measure.
    WrapAdaptiveRemoteDocumentPlayer(
        documentBytes = cardDocument.bytes,
        modifier = modifier,
        bitmapLoader = bitmapLoader,
        init = { player ->
            updaterHolder.value = player.stateUpdater
            playerHolder.value = player
        },
        onNamedAction = { name, value ->
            if (name == HA_ACTION_NAME) {
                decodeHaAction(value)?.let(dispatcher::dispatch)
            }
        },
    )
}

/**
 * Wrapper key that distinguishes a debug-bordered render of [inner]
 * from the plain render — same card YAML, different bytes (the
 * wrapping `DebugRcBorderWrapper` is captured into the document).
 */
private data class DebugBorderedCacheKey(val inner: Any)

/**
 * Push the diff between the previous push (tracked in [pushed]) and
 * the current [snapshot] for the named bindings of [entityIds] into
 * [updater]. Catches per-binding failures so a single unknown name
 * doesn't take down the whole push (the player rejects writes for
 * names not declared in the document).
 *
 * Boolean bindings are pushed as ints — `LiveValues.isOn` creates a
 * `RemoteBoolean` which alpha010 stores internally as a `RemoteInt`
 * (0/1), and the player only exposes `setUserLocalInt` for that
 * channel.
 */
private fun pushSnapshotBindings(
    updater: StateUpdater,
    entityIds: Set<String>,
    snapshot: HaSnapshot,
    pushed: MutableMap<String, Any?>,
) {
    val bindings = cardSnapshotBindings(entityIds, snapshot)
    for ((name, value) in bindings.strings) {
        if (pushed[name] == value) continue
        runCatching { updater.setUserLocalString(name, value) }
            .onSuccess { pushed[name] = value }
    }
    for ((name, value) in bindings.booleans) {
        if (pushed[name] == value) continue
        runCatching { updater.setUserLocalInt(name, if (value) 1 else 0) }
            .onSuccess { pushed[name] = value }
    }
}

/**
 * For each picture-entity in [entityIds], read the current
 * `entity_picture` URL from [snapshot] and — if it changed since the
 * last push — ask [resolver] for a fresh [Bitmap] and write it into
 * the running player under `pictureBindingName(id)`.
 *
 * `setUserLocalBitmap` writes through `RemoteContext.setNamedDataOverride`,
 * which takes precedence over whatever the player's `BitmapLoader`
 * resolved from the URL baked at capture time. So the document keeps
 * its original URL and the override is what's drawn — no re-capture
 * needed when HA rotates the `?token=`.
 *
 * Per-entity errors are caught so a single failing fetch (404, network
 * blip, decode error) doesn't take down the whole push.
 */
private suspend fun pushPictureEntityBitmaps(
    updater: StateUpdater,
    entityIds: Set<String>,
    snapshot: HaSnapshot,
    pushedUrls: MutableMap<String, String>,
    resolver: RemoteImageResolver,
): Boolean {
    var pushedAny = false
    for (entityId in entityIds) {
        val url =
            snapshot.states[entityId]
                ?.attributes
                ?.get("entity_picture")
                ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                ?.contentOrNull
        if (url == null) {
            Log.v(TAG, "picture entity=$entityId no entity_picture in snapshot; skipping")
            continue
        }
        if (pushedUrls[entityId] == url) {
            // Same URL as the previous push: the override is still
            // current, no need to re-fetch.
            continue
        }
        val previous = pushedUrls[entityId]
        Log.d(
            TAG,
            "picture entity=$entityId url=$url" +
                if (previous != null) " (was=$previous)" else " (first push)",
        )
        val fetched = runCatching { resolver.resolve(url) }.getOrElse { ex ->
            Log.w(TAG, "picture entity=$entityId resolve threw: ${ex.message}", ex)
            null
        }
        if (fetched == null) {
            Log.w(TAG, "picture entity=$entityId resolver returned null bitmap for url=$url")
            continue
        }
        // Picture-entity documents bake a fixed-size placeholder so
        // the named-bitmap slot has non-zero dimensions (the URL form
        // of `addNamedBitmap*` doesn't carry width/height — see
        // `picturePlaceholderBitmap`). Scale the override to match
        // so the player draws into the slot at the expected size.
        val sized =
            if (fetched.width == PICTURE_BITMAP_SIZE_PX && fetched.height == PICTURE_BITMAP_SIZE_PX) {
                fetched
            } else {
                android.graphics.Bitmap.createScaledBitmap(
                    fetched,
                    PICTURE_BITMAP_SIZE_PX,
                    PICTURE_BITMAP_SIZE_PX,
                    /* filter = */ true,
                )
            }
        val name = pictureBindingName(entityId)
        runCatching { updater.setUserLocalBitmap(name, sized) }
            .onSuccess {
                pushedUrls[entityId] = url
                pushedAny = true
                Log.d(
                    TAG,
                    "picture entity=$entityId pushed name=$name " +
                        "bitmap=${sized.width}x${sized.height} (fetched=${fetched.width}x${fetched.height})",
                )
            }
            .onFailure { ex ->
                Log.w(TAG, "picture entity=$entityId setUserLocalBitmap failed: ${ex.message}", ex)
            }
    }
    return pushedAny
}
