@file:Suppress("RestrictedApi")

package ee.schimke.terrazzo.wearsync

import android.content.Context
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.v2.captureSingleRemoteDocumentV2
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardRegistry
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.cardHeightDp
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.ProvideHaTheme

/**
 * Capture a `.rc` document for [card] under the supplied [haTheme]. The
 * capture is headless (uses `captureSingleRemoteDocumentV2` which is
 * `@RestrictTo(LIBRARY_GROUP)` — same path the phone widgets and
 * monitoring service take).
 *
 * Result holds the proto-serialisable bytes plus the document's
 * intrinsic pixel size so the wear-side player can place the card at
 * a sensible default rather than guessing.
 */
suspend fun captureThemedCardDocument(
    context: Context,
    registry: CardRegistry,
    card: CardConfig,
    snapshot: HaSnapshot,
    haTheme: HaTheme,
    widthPx: Int,
    densityDpi: Int = context.resources.configuration.densityDpi,
): CapturedCard {
    val heightDp = registry.cardHeightDp(card, snapshot).coerceAtLeast(MIN_CARD_HEIGHT_DP)
    val heightPx = (heightDp * densityDpi / 160f).toInt().coerceAtLeast(1)
    val captured = captureSingleRemoteDocumentV2(
        creationDisplayInfo = RemoteCreationDisplayInfo(widthPx, heightPx, densityDpi),
        context = context,
    ) {
        ProvideCardRegistry(registry) {
            ProvideHaTheme(haTheme) {
                RenderChild(card, snapshot, RemoteModifier.fillMaxWidth())
            }
        }
    }
    return CapturedCard(
        bytes = captured.bytes,
        widthPx = widthPx,
        heightPx = heightPx,
    )
}

data class CapturedCard(
    val bytes: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is CapturedCard && widthPx == other.widthPx && heightPx == other.heightPx &&
            bytes.contentEquals(other.bytes)
    override fun hashCode(): Int =
        (widthPx * 31 + heightPx) * 31 + bytes.contentHashCode()
}

/**
 * Floor on per-card capture height. A converter that returns a tiny
 * height (e.g. a divider) would otherwise emit a 0-height capture
 * surface and crash captureSingleRemoteDocumentV2.
 */
private const val MIN_CARD_HEIGHT_DP: Int = 48
