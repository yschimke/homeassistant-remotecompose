@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.remote.player.core.platform.BitmapLoader
import androidx.compose.remote.player.view.RemoteComposePlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.min
import java.io.ByteArrayInputStream

/**
 * Wrap-content-friendly drop-in for
 * [androidx.compose.remote.player.compose.RemoteDocumentPlayer].
 *
 * Works around an `androidx.compose.remote:remote-player-compose:1.0.0-alpha010`
 * bug where pinning width but leaving height unbounded fails to size
 * the host slot to the document's intrinsic content height. See
 * https://github.com/yschimke/homeassistant-remotecompose/issues/153.
 *
 * Root cause: `RemoteComposeView.onMeasure` only invokes
 * `CoreDocument.measure(...)` when its `AndroidPaintContext` is
 * non-null, and the paint context is constructed lazily on the first
 * `dispatchDraw` (`AndroidRemoteContext.useCanvas(canvas)`). The
 * Compose-side `RemoteDocumentPlayer` wraps the view in an
 * `AndroidView` whose first `onMeasure` runs before the first draw,
 * so the View skips the document measure pass and reports the
 * authored canvas size instead of the intrinsic content size — the
 * host slot ends up sized to the captured canvas.
 *
 * The fix here:
 *  1. Read the parent's max constraints via [BoxWithConstraints].
 *  2. Build a single [RemoteComposePlayer] view, warm it up with one
 *     off-screen `measure → layout → draw` cycle at the parent's max
 *     constraints. The draw populates the paint context.
 *  3. Re-measure the warmed-up view with the same specs; this time
 *     `onMeasure` runs the document's measure pass and reports the
 *     intrinsic content size.
 *  4. Pin a Compose `Box` to the resulting `(measuredWidth,
 *     measuredHeight)` and embed the same view via [AndroidView]
 *     inside. The view runs at EXACTLY constraints from the pinned
 *     `Box`, so the cached intrinsic measurement carries through.
 *
 * Single-View, no throwaway measurer — the same view we warm up is
 * the one Compose displays. Re-warms only when [documentBytes] or
 * the parent's constraints change.
 */
@Composable
fun WrapAdaptiveRemoteDocumentPlayer(
    documentBytes: ByteArray,
    modifier: Modifier = Modifier,
    bitmapLoader: BitmapLoader = BitmapLoader.UNSUPPORTED,
    init: (RemoteComposePlayer) -> Unit = {},
    onNamedAction: (name: String, value: Any?) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val maxWPx = constraints.maxWidth
        val maxHPx = constraints.maxHeight
        val view =
            remember(documentBytes, maxWPx, maxHPx) {
                RemoteComposePlayer(context).apply {
                    setBitmapLoader(bitmapLoader)
                    setDocument(RemoteDocument(decode(documentBytes)))
                    primeAndMeasure(this, maxWPx, maxHPx)
                    init(this)
                    // The View only exposes id-keyed action callbacks.
                    // The dashboard's `decodeHaAction` is keyed on the
                    // payload value (it doesn't care about the name),
                    // so we surface the id as the synthetic name and
                    // pass the value through.
                    addIdActionListener { id, value -> onNamedAction(id.toString(), value) }
                }
            }
        val widthDp = with(density) { view.measuredWidth.toDp() }
        val heightDp = with(density) { view.measuredHeight.toDp() }
        Box(modifier = Modifier.size(widthDp, heightDp)) {
            AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        }
    }
}

private fun decode(bytes: ByteArray): CoreDocument =
    CoreDocument().apply {
        initFromBuffer(RemoteComposeBuffer.fromInputStream(ByteArrayInputStream(bytes)))
    }

/**
 * Two-phase warmup:
 *  - first `measure → layout → draw` populates the
 *    `AndroidPaintContext` (without it the document's measure pass
 *    is silently skipped);
 *  - `forceLayout()` clears the cached measure result;
 *  - second `measure` re-runs `onMeasure` with the same specs but
 *    a populated paint context, so the View reports the intrinsic
 *    content size instead of the authored canvas size.
 */
private const val MAX_WARMUP_DIMENSION_PX = 4096

private fun primeAndMeasure(view: RemoteComposePlayer, maxWPx: Int, maxHPx: Int) {
    val widthSpec = toMeasureSpec(maxWPx)
    val heightSpec = toMeasureSpec(maxHPx)
    view.measure(widthSpec, heightSpec)

    val measuredW = view.measuredWidth
    val measuredH = view.measuredHeight

    val warmupW = clampWarmupDimension(measuredW, maxWPx)
    val warmupH = clampWarmupDimension(measuredH, maxHPx)

    view.layout(0, 0, warmupW, warmupH)
    val warmupBmp = Bitmap.createBitmap(warmupW, warmupH, Bitmap.Config.ARGB_8888)
    view.draw(Canvas(warmupBmp))
    warmupBmp.recycle()
    view.forceLayout()
    view.measure(widthSpec, heightSpec)
}

private fun clampWarmupDimension(measuredPx: Int, constraintMaxPx: Int): Int {
    val boundedByConstraint =
        if (constraintMaxPx == Constraints.Infinity) {
            measuredPx
        } else {
            min(measuredPx, constraintMaxPx)
        }
    return boundedByConstraint.coerceIn(1, MAX_WARMUP_DIMENSION_PX)
}

private fun toMeasureSpec(maxPx: Int): Int =
    if (maxPx == Constraints.Infinity) {
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    } else {
        View.MeasureSpec.makeMeasureSpec(maxPx, View.MeasureSpec.AT_MOST)
    }
