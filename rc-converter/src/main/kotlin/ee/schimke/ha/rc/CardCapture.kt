package ee.schimke.ha.rc

import android.content.Context
import androidx.annotation.RestrictTo
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.rememberRemoteDocument
import androidx.compose.remote.creation.compose.v2.captureSingleRemoteDocumentV2
import androidx.compose.remote.player.compose.RemoteDocumentPlayer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
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
 * surface. This is what a widget worker calls: the HA snapshot changed, so
 * re-encode the card's bytes and push to the app instance / Glance session.
 *
 * Uses `captureSingleRemoteDocumentV2` which is `@RestrictTo(LIBRARY_GROUP)`
 * — suppressed here deliberately. The non-V2 capture path requires a
 * `VirtualDisplay` which is prohibitive from a WorkManager worker.
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
): CardDocument {
    val converter = registry.get(card.type)
        ?: error("No converter registered for card type='${card.type}'")
    val captured = captureSingleRemoteDocumentV2(
        creationDisplayInfo = RemoteCreationDisplayInfo(widthPx, heightPx, densityDpi),
        context = context,
    ) {
        converter.Render(card, snapshot)
    }
    return CardDocument(captured.bytes, widthPx, heightPx)
}

/**
 * In-composition capture — for previews and in-app playback. Encodes once
 * per (card, snapshot) change, then hands the document to
 * [RemoteDocumentPlayer].
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
) {
    val converter = registry.get(card.type) ?: return
    val doc = rememberRemoteDocument(
        creationDisplayInfo = RemoteCreationDisplayInfo(widthPx, heightPx, densityDpi),
    ) {
        converter.Render(card, snapshot)
    }
    val document = doc.value ?: return
    RemoteDocumentPlayer(
        document = document,
        documentWidth = widthPx,
        documentHeight = heightPx,
        modifier = modifier,
    )
}
