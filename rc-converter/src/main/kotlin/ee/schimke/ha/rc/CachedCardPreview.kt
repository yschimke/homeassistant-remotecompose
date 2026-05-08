@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import androidx.annotation.RestrictTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.profile.Profile
import androidx.compose.remote.creation.profile.RcPlatformProfiles
import androidx.compose.remote.player.compose.RemoteDocumentPlayer
import androidx.compose.remote.player.core.state.StateUpdater
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.HA_ACTION_NAME
import kotlinx.coroutines.runBlocking

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
 *      `RemoteDocumentPlayer(init = ...)`.
 *   3. On every [snapshot] change, computes the bindings via
 *      [cardSnapshotBindings] and writes only those that actually
 *      changed since the last push (`<id>.state`, `<id>.is_on`).
 *
 * Sizing follows upstream `RemotePreview`: the captured document
 * carries its natural size in the header, and the player measures via
 * `RemoteComposePlayerFlags.shouldPlayerWrapContentSize` (on by
 * default), so callers size the slot via [modifier] and don't have to
 * thread pixels through.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
@Composable
fun CachedCardPreview(
    cacheKey: Any,
    profile: Profile = RcPlatformProfiles.ANDROIDX,
    modifier: Modifier = Modifier,
    card: CardConfig? = null,
    snapshot: HaSnapshot? = null,
    content: @RemoteComposable @Composable () -> Unit,
) {
    val context = LocalContext.current
    val cache = LocalCardDocumentCache.current

    val cardDocument =
        remember(cacheKey) {
            cache.get(cacheKey)
                ?: runBlocking {
                        val captured =
                            captureSingleRemoteDocument(
                                context = context,
                                profile = profile,
                                content = content,
                            )
                        CardDocument(bytes = captured.bytes, widthPx = 0, heightPx = 0)
                    }
                    .also { cache.put(cacheKey, it) }
        }

    val coreDocument = remember(cardDocument) { cardDocument.decode() }
    val windowInfo = LocalWindowInfo.current
    val dispatcher = LocalHaActionDispatcher.current

    val entityIds = remember(card) { card?.let { cardEntityIds(it) }.orEmpty() }
    // The handle survives recomposition but is replaced if the player
    // is torn down and rebuilt (cache invalidation, theme flip).
    val updaterHolder = remember { mutableStateOf<StateUpdater?>(null) }
    // Tracks the last value we pushed for each binding so a redundant
    // snapshot recompose doesn't re-issue identical writes. Reset when
    // [cacheKey] changes — the new document carries its own initial
    // bake, and we want the next push to be unconditional.
    val pushed = remember(cacheKey) { HashMap<String, Any?>() }

    if (card != null && snapshot != null && entityIds.isNotEmpty()) {
        LaunchedEffect(updaterHolder.value, snapshot) {
            val updater = updaterHolder.value ?: return@LaunchedEffect
            pushSnapshotBindings(updater, entityIds, snapshot, pushed)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        RemoteDocumentPlayer(
            document = coreDocument,
            documentWidth = windowInfo.containerSize.width,
            documentHeight = windowInfo.containerSize.height,
            modifier = Modifier.fillMaxSize(),
            init = { player -> updaterHolder.value = player.stateUpdater },
            onNamedAction = { name, value, _ ->
                if (name == HA_ACTION_NAME) {
                    decodeHaAction(value)?.let(dispatcher::dispatch)
                }
            },
        )
    }
}

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
