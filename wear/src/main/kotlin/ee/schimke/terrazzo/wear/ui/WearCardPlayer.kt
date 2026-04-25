@file:Suppress("RestrictedApi")

package ee.schimke.terrazzo.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.player.compose.RemoteDocumentPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import ee.schimke.terrazzo.wearsync.proto.CardDoc
import java.io.ByteArrayInputStream

/**
 * Plays a [CardDoc] via [RemoteDocumentPlayer]. The bytes were baked
 * phone-side under the wear-dark Terrazzo theme so the document is
 * already styled to match the chrome around it.
 *
 * The player is given the document's intrinsic size; we wrap it in a
 * surface tinted the way phone-side widgets are so empty/transparent
 * regions don't show through to the watch face.
 */
@Composable
fun WearCardPlayer(
    doc: CardDoc,
    modifier: Modifier = Modifier,
) {
    val core = remember(doc.id, doc.updatedAtMs, doc.rcBytes.size) {
        runCatching {
            CoreDocument().apply {
                initFromBuffer(RemoteComposeBuffer.fromInputStream(ByteArrayInputStream(doc.rcBytes)))
            }
        }.getOrNull()
    }
    val width = doc.widthPx.coerceAtLeast(1)
    val height = doc.heightPx.coerceAtLeast(1)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(width.toFloat() / height.toFloat())
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (core != null) {
            RemoteDocumentPlayer(
                document = core,
                documentWidth = width,
                documentHeight = height,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // Decode failed (corrupt bytes / mismatched RC version).
            // Fall back to a thin label so the layout doesn't collapse.
            Text(
                text = "Card unavailable",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Lightweight placeholder while we're still waiting for the phone's
 * `/wear/card/<id>` DataItem to arrive. Used by list screens where
 * the [CardDoc] for a [ee.schimke.terrazzo.wearsync.proto.CardRef] may
 * not be in [WearSyncRepository.cardDocs] yet.
 */
@Composable
fun WearCardSkeleton(title: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
