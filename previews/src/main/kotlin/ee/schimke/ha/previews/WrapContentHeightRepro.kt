@file:Suppress("RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.RcProfiles
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.core.operations.Header
import androidx.compose.remote.creation.RemoteComposeWriter
import androidx.compose.remote.creation.RemoteComposeWriterAndroid
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.border as rcBorder
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth as rcFillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding as rcPadding
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.platform.AndroidxRcPlatformServices
import androidx.compose.remote.creation.profile.Profile
import androidx.compose.remote.player.compose.ExperimentalRemotePlayerApi
import androidx.compose.remote.player.compose.RemoteComposePlayerFlags
import androidx.compose.remote.player.compose.RemoteDocumentPlayer
import ee.schimke.ha.rc.WrapAdaptiveRemoteDocumentPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking

/**
 * Minimal repro for an alpha010 wrap-content limitation.
 *
 * **Setup**
 *
 *  - One `Profile` ("wrapProfile") that bakes
 *    `Header.FEATURE_PAINT_MEASURE = 0` so the player runs a real
 *    measure pass against the parent's max constraints.
 *  - `RemoteComposePlayerFlags.shouldPlayerWrapContentSize = true`,
 *    so the player's modifier becomes `wrapContentSize()` instead of
 *    `Modifier.size(documentWidth.dp, documentHeight.dp)`.
 *  - Document content is a `RemoteColumn(fillMaxWidth)` with a
 *    fixed-height `RemoteText` inside (≈25 dp tall).
 *
 * **Expected**
 *
 * With the wrap profile + wrap-content flag + a host modifier of
 * `Modifier.width(180.dp)` (no height), the player View should
 * report ~25 dp as its measured height up to Compose, so the slot
 * shrinks to ~25 dp. That is what `RemoteComposePlayerFlags.kt` and
 * the comment on `Header.FEATURE_PAINT_MEASURE` advertise.
 *
 * **Actual**
 *
 * The slot stretches to the parent's full available height. The
 * document itself does measure to ~25 dp internally — visible if
 * you wrap content in a bordered `RemoteBox(fillMaxWidth)` and
 * watch where the border ends — but the View does not propagate
 * that intrinsic height to its `AndroidView` parent in Compose.
 *
 * Run on `androidx.compose.remote:remote-creation-compose:1.0.0-alpha010`,
 * `androidx.compose.remote:remote-player-compose:1.0.0-alpha010`,
 * `androidx.compose.compiler:compiler:1.5.x` (Robolectric Compose
 * preview pipeline; same behaviour observed in real Activity hosts).
 *
 * Three side-by-side hosts in this preview:
 *  1. **width pinned**: `Modifier.width(180.dp)` (no height) —
 *     height should adapt; in alpha010 the slot fills available height.
 *  2. **width + height pinned**: `Modifier.width(180.dp).height(40.dp)` —
 *     control case; works because the EXACTLY constraint dominates.
 *  3. **wrap both**: `Modifier` (no width / height) — same failure
 *     mode as (1).
 *
 * Each card draws a 1.dp red border on its host (Compose UI side)
 * and a 1.rdp blue border around an outer `RemoteBox(fillMaxWidth)`
 * that follows the document's intrinsic content height. When wrap
 * works, blue and red coincide; when it doesn't, blue sits inside
 * a much taller red rectangle.
 */
private val wrapProfile: Profile =
    Profile(
        CoreDocument.DOCUMENT_API_LEVEL,
        RcProfiles.PROFILE_ANDROIDX or RcProfiles.PROFILE_EXPERIMENTAL,
        AndroidxRcPlatformServices(),
    ) { creationDisplayInfo, profile, _ ->
        RemoteComposeWriterAndroid(
            profile,
            RemoteComposeWriter.hTag(Header.DOC_WIDTH, creationDisplayInfo.width),
            RemoteComposeWriter.hTag(Header.DOC_HEIGHT, creationDisplayInfo.height),
            RemoteComposeWriter.hTag(Header.DOC_CONTENT_DESCRIPTION, ""),
            RemoteComposeWriter.hTag(Header.DOC_PROFILES, profile.operationsProfiles),
            RemoteComposeWriter.hTag(Header.FEATURE_PAINT_MEASURE, 0),
        )
    }

@Preview(name = "wrap-content height repro · alpha010",
    widthDp = 411, heightDp = 600, showBackground = true,
    backgroundColor = 0xFFFFFFFFL,
)
@OptIn(ExperimentalRemotePlayerApi::class)
@Composable
fun WrapContentHeightReproAlpha010() {
    RemoteComposePlayerFlags.shouldPlayerWrapContentSize = true
    Column(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
    ) {
        Text(
            text = "alpha010 wrap-content height: width pinned, height NOT adaptive",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Red = host slot (Compose). Blue = document's intrinsic content height. " +
                "Expected: red == blue. Actual: red >> blue when no height constraint.",
            style = MaterialTheme.typography.labelSmall,
        )

        Text(
            text = "1) width pinned, height wrap → BUG: slot fills column",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        Row(modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF2E7D32))) {
            ReproPlayer(modifier = Modifier.width(180.dp).border(1.dp, Color(0xFFD32F2F)))
        }

        Text(
            text = "2) width + height both pinned → control case (works)",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        Row(modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF2E7D32))) {
            ReproPlayer(
                modifier = Modifier.width(180.dp).height(40.dp).border(1.dp, Color(0xFFD32F2F)),
            )
        }
    }
}

/**
 * Same setup as [WrapContentHeightReproAlpha010] but the player is
 * [WrapAdaptiveRemoteDocumentPlayer], which pre-measures the
 * captured document with a real `RemoteContext` (1×1 throwaway
 * canvas to seed the paint context) and pins the host `Box` to the
 * resulting intrinsic dimensions before delegating to the upstream
 * `RemoteDocumentPlayer` at EXACTLY constraints. Result: the host
 * slot tracks the document's intrinsic content height even when
 * only width is constrained.
 */
@Preview(name = "wrap-content height fix · WrapAdaptive player",
    widthDp = 411, heightDp = 300, showBackground = true,
    backgroundColor = 0xFFFFFFFFL,
)
@OptIn(ExperimentalRemotePlayerApi::class)
@Composable
fun WrapContentHeightFixAlpha010() {
    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Text(
            text = "fix: WrapAdaptiveRemoteDocumentPlayer (pre-measures the document)",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Red = host slot. Blue = document's intrinsic content height. Should coincide.",
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            text = "1) width=180, height=wrap → adaptive height",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        Row(modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF2E7D32))) {
            FixedPlayer(modifier = Modifier.width(180.dp).border(1.dp, Color(0xFFD32F2F)))
        }
        Text(
            text = "2) width=fillMax, height=wrap → adaptive height",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        Row(modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF2E7D32))) {
            FixedPlayer(modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFFD32F2F)))
        }
    }
}

@OptIn(ExperimentalRemotePlayerApi::class)
@Composable
private fun FixedPlayer(modifier: Modifier) {
    val context = LocalContext.current
    val documentBytes =
        remember {
            runBlocking {
                captureSingleRemoteDocument(context = context, profile = wrapProfile) {
                    RemoteBox(
                        modifier = RemoteModifier
                            .rcFillMaxWidth()
                            .rcBorder(1.rdp, Color(0xFF1565C0).rc, RemoteRoundedCornerShape(0.rdp)),
                    ) {
                        RemoteColumn(
                            modifier = RemoteModifier.rcFillMaxWidth().rcPadding(4.rdp),
                        ) {
                            RemoteText(text = "Hello", modifier = RemoteModifier.rcFillMaxWidth())
                        }
                    }
                }
                    .bytes
            }
        }
    WrapAdaptiveRemoteDocumentPlayer(documentBytes = documentBytes, modifier = modifier)
}

@OptIn(ExperimentalRemotePlayerApi::class)
@Composable
private fun ReproPlayer(modifier: Modifier) {
    val context = LocalContext.current
    val document =
        remember {
            runBlocking {
                captureSingleRemoteDocument(
                    context = context,
                    profile = wrapProfile,
                ) {
                    RemoteBox(
                        modifier = RemoteModifier
                            .rcFillMaxWidth()
                            .rcBorder(1.rdp, Color(0xFF1565C0).rc, RemoteRoundedCornerShape(0.rdp)),
                    ) {
                        RemoteColumn(modifier = RemoteModifier.rcFillMaxWidth().rcPadding(4.rdp)) {
                            RemoteText(text = "Hello", modifier = RemoteModifier.rcFillMaxWidth())
                        }
                    }
                }.bytes
            }
        }
    val coreDocument = remember(document) {
        CoreDocument().apply {
            initFromBuffer(RemoteComposeBuffer.fromInputStream(java.io.ByteArrayInputStream(document)))
        }
    }
    Box(modifier = modifier) {
        RemoteDocumentPlayer(
            document = coreDocument,
            documentWidth = 411,
            documentHeight = 600,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

