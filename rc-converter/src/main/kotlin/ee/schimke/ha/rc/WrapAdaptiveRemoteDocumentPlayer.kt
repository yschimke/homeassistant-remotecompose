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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import kotlin.math.min

/**
 * Wrap-content-friendly drop-in for [androidx.compose.remote.player.compose.RemoteDocumentPlayer].
 *
 * Works around an `androidx.compose.remote:remote-player-compose:1.0.0-alpha010` bug where pinning
 * width but leaving height unbounded fails to size the host slot to the document's intrinsic
 * content height. See https://github.com/yschimke/homeassistant-remotecompose/issues/153.
 *
 * Root cause: `RemoteComposeView.onMeasure` only invokes `CoreDocument.measure(...)` when its
 * `AndroidPaintContext` is non-null, and the paint context is constructed lazily on the first
 * `dispatchDraw` (`AndroidRemoteContext.useCanvas(canvas)`). The Compose-side
 * `RemoteDocumentPlayer` wraps the view in an `AndroidView` whose first `onMeasure` runs before the
 * first draw, so the View skips the document measure pass and reports the authored canvas size
 * instead of the intrinsic content size — the host slot ends up sized to the captured canvas.
 *
 * The fix here:
 * 1. Read the parent's max constraints via [BoxWithConstraints].
 * 2. Build a single [RemoteComposePlayer] view, warm it up with one off-screen `measure → layout →
 *    draw` cycle at the parent's max constraints. The draw populates the paint context.
 * 3. Re-measure the warmed-up view with the same specs; this time `onMeasure` runs the document's
 *    measure pass and reports the intrinsic content size.
 * 4. Pin a Compose `Box` to the resulting `(measuredWidth, measuredHeight)` and embed the same view
 *    via [AndroidView] inside. The view runs at EXACTLY constraints from the pinned `Box`, so the
 *    cached intrinsic measurement carries through.
 *
 * Single-View, no throwaway measurer — the same view we warm up is the one Compose displays.
 * Re-warms only when [documentBytes] or the parent's constraints change.
 *
 * **Pinned (EXACTLY) slots.** When the parent fixes *both* axes (`Modifier.size(...)`, or a
 * `fillMaxSize()` inside an already-sized box — the card-history resize preview), the intrinsic
 * measurement above is the wrong model: a full-bleed `fillMaxSize()` document (the launcher widget
 * surface, [ee.schimke.ha.rc.components.RemoteHaWidgetSurface]) has *no* intrinsic size, so steps
 * 2–4 would collapse the slot to ~1px and render blank. Instead we play the document at the
 * parent's EXACT size — exactly as the launcher widget does — so it fills the slot and its runtime
 * size-breakpoints (see `RemoteSizeBreakpoint`) read the real canvas. The View is rebuilt (and
 * re-primed) whenever that size changes: a `RemoteComposePlayer` re-measured at a new size after
 * its first draw paints blank or only fills up to its first size, so re-using one View across a
 * resize is exactly the "widget goes blank once resized" bug. Decoded bytes are cached by the
 * caller, so a resize only rebuilds the View + prime, not the document.
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

    if (constraints.hasFixedWidth && constraints.hasFixedHeight) {
      // The parent pins the slot (EXACTLY on both axes) — the launcher
      // widget and the long-press resize preview, whose size is forced
      // from outside. Build the player for this exact size and rebuild
      // it whenever the size changes (key on width/height too).
      //
      // We deliberately do NOT re-use one primed View across resizes: a
      // `RemoteComposePlayer` that has been measured + drawn once paints
      // blank — or only fills up to its first size — when it is later
      // re-measured at a different size (its layout is cached at the
      // first draw and never rebuilt; with `FEATURE_PAINT_MEASURE = 1`
      // it doesn't even re-run the document measure). That is the
      // "widget goes blank / doesn't fill once resized" bug. Re-keying
      // on the size gives each slot a freshly primed View that fills it
      // and reflows its size-breakpoints; the caller caches the decoded
      // bytes, so only the View + prime is rebuilt per (discrete) size.
      val view =
        remember(documentBytes, maxWPx, maxHPx) {
          RemoteComposePlayer(context).apply {
            setBitmapLoader(bitmapLoader)
            setDocument(RemoteDocument(decode(documentBytes)))
            // One off-screen draw warms the paint context so the first
            // on-screen frame isn't blank; EXACTLY constraints size the
            // View directly, so no intrinsic re-measure is needed.
            primeFixed(this, maxWPx, maxHPx)
            init(this)
            addIdActionListener { id, value -> onNamedAction(id.toString(), value) }
          }
        }
      // Swap the embedded View when a resize produces a new instance.
      key(view) { AndroidView(factory = { view }, modifier = Modifier.fillMaxSize()) }
    } else {
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
      val widthDp = with(density) { clampWarmupDimension(view.measuredWidth, maxWPx).toDp() }
      val heightDp = with(density) { clampWarmupDimension(view.measuredHeight, maxHPx).toDp() }
      Box(modifier = Modifier.size(widthDp, heightDp)) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
      }
    }
  }
}

private fun decode(bytes: ByteArray): CoreDocument =
  CoreDocument().apply {
    initFromBuffer(RemoteComposeBuffer.fromInputStream(ByteArrayInputStream(bytes)))
  }

/**
 * Two-phase warmup:
 * - first `measure → layout → draw` populates the `AndroidPaintContext` (without it the document's
 *   measure pass is silently skipped);
 * - `forceLayout()` clears the cached measure result;
 * - second `measure` re-runs `onMeasure` with the same specs but a populated paint context, so the
 *   View reports the intrinsic content size instead of the authored canvas size.
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

/**
 * Single off-screen `measure → layout → draw` at an EXACTLY-sized slot, purely to populate the
 * `AndroidPaintContext` (the first on-screen frame would otherwise be blank). Unlike
 * [primeAndMeasure] there's no intrinsic re-measure: an EXACTLY constraint sizes the View directly,
 * and subsequent (e.g. animated) sizes are handled by the View re-measuring in place.
 */
private fun primeFixed(view: RemoteComposePlayer, wPx: Int, hPx: Int) {
  val w = wPx.coerceIn(1, MAX_WARMUP_DIMENSION_PX)
  val h = hPx.coerceIn(1, MAX_WARMUP_DIMENSION_PX)
  val widthSpec = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY)
  val heightSpec = View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
  view.measure(widthSpec, heightSpec)
  view.layout(0, 0, w, h)
  val warmupBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
  view.draw(Canvas(warmupBmp))
  warmupBmp.recycle()
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
