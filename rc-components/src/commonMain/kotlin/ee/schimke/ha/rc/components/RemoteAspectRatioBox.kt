@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteState
import androidx.compose.remote.creation.compose.state.toRemoteDp
import androidx.compose.runtime.Composable

/**
 * A full-width box whose height follows its **live** width: `height = width ÷ ratio`.
 *
 * RemoteCompose has no aspect-ratio modifier, and a Wrap-mode card is captured once and played at
 * whatever width its host gives it, so the height can't be a capture-time dp. Instead it is an
 * expression over `componentWidth()` — the runtime width of this box, in px — converted back to dp
 * through the document's density, so the same document is square at 160 dp and at 400 dp.
 *
 * [content] fills the sized box: give its root `RemoteModifier.fillMaxSize()`.
 *
 * Inside a `grid` cell the width is the cell's, as the grid computes it from its own width
 * ([LocalGridCellWidthPx]), so every cell of a row gets the same exact size.
 *
 * The expression binds to the component being written when it is first referenced (the outer box,
 * or the inner one itself — both are `fillMaxWidth()`, so both read the cell's width), and it is
 * named per call site so a second box never reuses the first one's width.
 */
@Composable
@RemoteComposable
fun RemoteAspectRatioBox(
  ratio: Float,
  modifier: RemoteModifier = RemoteModifier,
  content: @Composable @RemoteComposable () -> Unit,
) {
  require(ratio > 0f && ratio.isFinite()) { "aspect ratio must be positive: $ratio" }
  val name = rememberExpressionName(AspectExpressionPrefix)
  // In a grid cell, the cell's width as the grid computes it; anywhere else, this box's own.
  val widthPx =
    LocalGridCellWidthPx.current
      ?: RemoteFloat.createNamedRemoteFloatExpression(
        name = name,
        domain = RemoteState.Domain.User,
      ) {
        componentWidth()
      }
  // Layout modifiers take dp; componentWidth() is px, so convert at playback density.
  val height = (widthPx / ratio).toRemoteDp()
  RemoteBox(modifier = modifier.fillMaxWidth()) {
    RemoteBox(modifier = RemoteModifier.fillMaxWidth().height(height)) { content() }
  }
}

private const val AspectExpressionPrefix = "__terrazzo_aspect_"
