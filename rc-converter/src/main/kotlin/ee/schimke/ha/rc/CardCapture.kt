@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import android.content.Context
import androidx.annotation.RestrictTo
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.capture.rememberRemoteDocument
import androidx.compose.remote.player.compose.RemoteDocumentPlayer
import androidx.compose.remote.player.core.platform.BitmapLoader
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.HA_ACTION_NAME
import java.io.ByteArrayInputStream

/**
 * Captured RemoteCompose document for a single Lovelace card. This is the
 * unit that ships over the wire and ends up rendered by a launcher widget,
 * a tile, or a player composable.
 *
 * @property bytes serialized `.rc` document
 * @property widthPx / heightPx the document's natural size
 */
data class CardDocument(
    val bytes: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is CardDocument && bytes.contentEquals(other.bytes) &&
            widthPx == other.widthPx && heightPx == other.heightPx
    override fun hashCode(): Int =
        (bytes.contentHashCode() * 31 + widthPx) * 31 + heightPx

    /** Deserialize into a [CoreDocument] ready for playback. */
    fun decode(): CoreDocument = CoreDocument().apply {
        initFromBuffer(RemoteComposeBuffer.fromInputStream(ByteArrayInputStream(bytes)))
    }
}

/**
 * Headless capture path — produces a [CardDocument] without any visible
 * surface. This is what a widget worker calls: the dashboard YAML
 * changed (or the slot is freshly seeded), re-encode the card's bytes
 * and push to the app instance / Glance session.
 *
 * Consults [cache] before encoding: the snapshot is allowed to change
 * without forcing a re-encode, since named-binding writes from the
 * host carry value updates. Pass [forceRefresh] = true to bypass the
 * cache (e.g. when the YAML just changed but the cache key is hard
 * to evict surgically).
 *
 * alpha09 collapses the public/V2 capture functions into a single
 * `captureSingleRemoteDocument` which is itself `@RestrictTo(LIBRARY_GROUP)`
 * — suppressed here deliberately. It runs the composition headlessly
 * (no `VirtualDisplay`), so it's safe from a WorkManager worker.
 */
@Suppress("RestrictedApi")
@RestrictTo(RestrictTo.Scope.LIBRARY)
suspend fun captureCardDocument(
    context: Context,
    widthPx: Int,
    heightPx: Int,
    densityDpi: Int = context.resources.configuration.densityDpi,
    registry: CardRegistry,
    card: CardConfig,
    snapshot: HaSnapshot,
    cache: CardDocumentCache = defaultCardDocumentCache,
    forceRefresh: Boolean = false,
): CardDocument {
    val converter = registry.get(card.type)
        ?: error("No converter registered for card type='${card.type}'")
    val cacheKey = HeadlessCardCacheKey(card, widthPx, heightPx, densityDpi)
    if (!forceRefresh) cache.get(cacheKey)?.let { return it }
    val captured = captureSingleRemoteDocument(
        context = context,
        creationDisplayInfo = RemoteCreationDisplayInfo(widthPx, heightPx, densityDpi),
    ) {
        converter.Render(card, snapshot)
    }
    return CardDocument(captured.bytes, widthPx, heightPx).also { cache.put(cacheKey, it) }
}

/**
 * Default cache key for the headless capture path. Includes the size
 * inputs because the encoded document bakes them into its header and
 * recomputing layout for a different slot size produces different
 * bytes. Theme isn't part of the key today: widget capture uses the
 * default theme; if widgets gain a theme picker, extend this key.
 */
private data class HeadlessCardCacheKey(
    val card: CardConfig,
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
)

/**
 * In-composition capture — for previews and in-app playback. Encodes once
 * per (card, snapshot) change, then hands the document to
 * [RemoteDocumentPlayer].
 *
 * [bitmapLoader] resolves the names referenced by named-bitmap
 * documents (`RemoteHaImageNamed` / `rememberNamedRemoteBitmap(...)`).
 * Defaults to [BitmapLoader.UNSUPPORTED]; cards that don't use named
 * bitmaps are unaffected. See `rc-image-coil`'s `CoilBitmapLoader`
 * for an HTTP / disk-cached impl.
 */
@Composable
fun CardPlayer(
    card: CardConfig,
    snapshot: HaSnapshot,
    widthPx: Int,
    heightPx: Int,
    densityDpi: Int,
    registry: CardRegistry,
    modifier: Modifier = Modifier,
    bitmapLoader: BitmapLoader = BitmapLoader.UNSUPPORTED,
) {
    val converter = registry.get(card.type) ?: return
    val doc = rememberRemoteDocument(
        creationDisplayInfo = RemoteCreationDisplayInfo(widthPx, heightPx, densityDpi),
    ) {
        converter.Render(card, snapshot)
    }
    val document = doc.value ?: return
    val dispatcher = LocalHaActionDispatcher.current
    RemoteDocumentPlayer(
        document = document,
        documentWidth = widthPx,
        documentHeight = heightPx,
        modifier = modifier,
        bitmapLoader = bitmapLoader,
        onNamedAction = { name, value, _ ->
            if (name == HA_ACTION_NAME) {
                decodeHaAction(value)?.let(dispatcher::dispatch)
            }
        },
    )
}
