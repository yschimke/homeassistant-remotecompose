package ee.schimke.terrazzo.wear.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.glance.wear.WearWidgetBrush
import androidx.glance.wear.WearWidgetDocument
import androidx.glance.wear.core.WearWidgetParams
import ee.schimke.composeai.rccreation.compose.capture.RemoteCreationDisplayInfo
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.layout.RemoteOffset
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.drawWithContent
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxSize
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.state.RemoteFloat
import ee.schimke.composeai.rccreation.compose.state.RemotePaint
import ee.schimke.composeai.rccreation.compose.state.RemoteState
import ee.schimke.composeai.rccreation.compose.state.asRemoteDp
import ee.schimke.composeai.rccreation.compose.state.rc
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.rc.captureSingleRemoteDocument
import ee.schimke.ha.rc.wearWidgets

/**
 * How a design exported with the widget container's `hostFrame: device` is captured: compose-ai-
 * tools' `HostFramedWearWidgetDocument` (`wear-preview-runtime`), written for these tests on
 * rc-players' creation API, which the cards are written with (upstream's composes its content
 * inside Glance Wear's `WearWidgetDocument`, AndroidX's API).
 *
 * Upstream's container (`androidx.glance.wear.composable.WearWidgetContainer`) writes the host's
 * padding and corner radius into the document as constants at capture time, so a document captured
 * for one host shape (a squircle's 8dp insets and 26dp corners) keeps them when it plays in
 * another, such as a round host's 35dp/16dp stadium. This one declares them as the named floats in
 * [HostFrame] instead, each defaulting to the params it was captured with, so a device that plays
 * the document can write in its own host's values (`hardcodeNamedValues` in `rc-player-edit`, then
 * `adaptTo` to fold them into the literals a widget player reads) and one capture fits every host.
 *
 * The rest is what upstream draws for an empty brush: `surfaceContainerLow` in the rounded rect,
 * the content inset by the padding, on Glance Wear's own canvas and under its header.
 */
internal object HostFramedWearWidgetDocument {

  /** [content] in the named container, captured for a widget with [params] on this display. */
  suspend fun capture(
    context: Context,
    params: WearWidgetParams,
    content: @RemoteComposable @Composable () -> Unit,
  ): ByteArray {
    // Glance Wear's own capture of an empty container, for the canvas and header of whichever
    // Glance Wear is installed, as WearWidgetTemplate.instantiate takes them.
    val frame = WearWidgetDocument(WearWidgetBrush) {}.captureRawContent(context, params, false)
    val header = RcDocumentCodec.decode(frame.rcDocument, RcWireLimits()).header
    val resources = context.resources
    val captured =
      captureSingleRemoteDocument(
          context = context,
          creationDisplayInfo =
            RemoteCreationDisplayInfo(
              width = header.width,
              height = header.height,
              densityDpi = resources.displayMetrics.densityDpi,
              fontScale = resources.configuration.fontScale,
            ),
          profile = wearWidgets,
        ) {
          HostFramedWearWidgetContainer(params, content)
        }
        .bytes
    return RcDocumentCodec.encode(
      RcDocumentCodec.decode(captured, RcWireLimits()).copy(header = header),
    )
  }

  /**
   * The host frame values the document declares, in dp, each a `USER` float named [valueName]
   * (`USER:<valueName>` in the document). The names are the ones `WearWidgetTemplate` writes a
   * watch's `WearWidgetParams` into, so its `instantiate` plays a document exported this way
   * unchanged.
   */
  enum class HostFrame(val valueName: String) {
    HorizontalPadding("wear.container.horizontal_padding"),
    VerticalPadding("wear.container.vertical_padding"),
    CornerRadius("wear.container.corner_radius");

    /** This value in [params]: what a device writes in for its own host. */
    fun of(params: WearWidgetParams): Float =
      when (this) {
        HorizontalPadding -> params.horizontalPaddingDp
        VerticalPadding -> params.verticalPaddingDp
        CornerRadius -> params.cornerRadiusDp
      }
  }
}

// androidx.wear.compose.material3.ColorScheme.surfaceContainerLow, as upstream's container fills.
private val surfaceContainerLowColor = Color(red = 39, green = 36, blue = 48).rc

/** Upstream's `WearWidgetContainer`, its padding and corner radius the named [HostFrame] values. */
@RemoteComposable
@Composable
private fun HostFramedWearWidgetContainer(
  params: WearWidgetParams,
  content: @RemoteComposable @Composable () -> Unit,
) {
  fun named(frame: HostFramedWearWidgetDocument.HostFrame): RemoteFloat =
    RemoteFloat.createNamedRemoteFloat(frame.valueName, frame.of(params), RemoteState.Domain.User)
  val horizontalPadding = named(HostFramedWearWidgetDocument.HostFrame.HorizontalPadding)
  val verticalPadding = named(HostFramedWearWidgetDocument.HostFrame.VerticalPadding)
  val cornerRadius = named(HostFramedWearWidgetDocument.HostFrame.CornerRadius).asRemoteDp()
  RemoteBox(
    modifier =
      RemoteModifier.fillMaxSize().drawWithContent {
        val cornerRadiusOffset = RemoteOffset(cornerRadius.toPx(), cornerRadius.toPx())
        drawRoundRect(
          paint = RemotePaint().apply { color = surfaceContainerLowColor },
          cornerRadius = cornerRadiusOffset,
        )
        drawContent()
      },
  ) {
    RemoteBox(
      modifier =
        RemoteModifier.fillMaxSize()
          .padding(
            horizontal = horizontalPadding.asRemoteDp(),
            vertical = verticalPadding.asRemoteDp(),
          ),
      content = content,
    )
  }
}
