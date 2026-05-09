@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.border
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.player.compose.RemoteComposePlayerFlags
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Idempotent: flips `RemoteComposePlayerFlags.shouldPlayerWrapContentSize`
 * to `true` so any `RemoteComposeView` inside a `RemoteDocumentPlayer`
 * wraps to the captured document's intrinsic content size instead of
 * pinning to the modifier-driven size.
 *
 * Call from process-init code (`Application.onCreate()`) so production
 * starts in wrap-content mode, and from preview entry points (which
 * never hit the Application class) so `@Preview` rendering matches.
 * The JVM-static field defaults to `false`; without this call the
 * player ignores wrap-friendly profile bits and the host slot dictates
 * size unconditionally.
 */
@OptIn(androidx.compose.remote.player.compose.ExperimentalRemotePlayerApi::class)
fun enableRemoteComposeWrapContent() {
    RemoteComposePlayerFlags.shouldPlayerWrapContentSize = true
}

/**
 * When `true`, hosts that render RemoteCompose card documents draw
 * debug borders around their slot (Compose UI side) and around the
 * captured document's outermost element (RemoteCompose side). Used to
 * spot mismatches between the host's pinned slot size and the
 * document's intrinsic content size — i.e. whether each converter's
 * `naturalHeightDp` is overshooting / undershooting per card.
 *
 * Default `false`. Provide `true` from a debug menu, screenshot test,
 * or experiment preview to switch it on. Hosts that read this flag:
 *
 *  - `DashboardViewScreen.CardSlot` — red `1.dp` Compose border around
 *    the host `Box` that owns the player.
 *  - `CachedCardPreview` — blue `1.rdp` border drawn inside the
 *    captured document, around an outer wrapper that follows the
 *    document's intrinsic content size. Drawn in the document so it
 *    tracks the player's actual paint, not the host slot.
 *
 * The flag is mixed into the document cache key inside
 * [CachedCardPreview], so flipping it forces a re-encode rather than
 * playing back stale bytes.
 */
val LocalRcDebugBorders = staticCompositionLocalOf { false }

/**
 * Wraps [content] in a `RemoteBox` that fills the host width and
 * carries a visible border so a captured document outlines its own
 * top-level container at playback time.
 *
 * `fillMaxWidth()` (not `fillMaxSize`) so the wrapper's height tracks
 * the converter's intrinsic content height. With a wrap-friendly
 * profile / player flag the host slot can then size itself to that
 * intrinsic height, and the host border tightly hugs the document
 * border. With a paint-measure profile the wrapper still draws but
 * sits inside the slot the host pinned via `Modifier.height(...)`.
 */
@Composable
@RemoteComposable
internal fun DebugRcBorderWrapper(content: @Composable @RemoteComposable () -> Unit) {
    RemoteBox(
        modifier = RemoteModifier
            .fillMaxWidth()
            .border(1.rdp, Color(0xFF1565C0).rc, RemoteRoundedCornerShape(0.rdp)),
    ) {
        content()
    }
}
