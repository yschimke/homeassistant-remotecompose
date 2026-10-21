@file:Suppress("RestrictedApi")

package ee.schimke.terrazzo.wear.widget

import android.content.Context
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.RemoteDensity
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.drawWithContent
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.state.RemoteDp
import androidx.compose.remote.creation.compose.state.RemotePaint
import androidx.compose.remote.creation.compose.state.RemoteState
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.wear.WearWidgetBrush
import androidx.glance.wear.WearWidgetDocument
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.core.WearWidgetRawContent
import ee.schimke.composeai.rcplayer.edit.RcApproximation
import ee.schimke.composeai.rcplayer.edit.RcDisplay
import ee.schimke.composeai.rcplayer.edit.RcHardcodedValue
import ee.schimke.composeai.rcplayer.edit.RcTargetProfiles
import ee.schimke.composeai.rcplayer.edit.adaptTo
import ee.schimke.composeai.rcplayer.edit.hardcodeDensity
import ee.schimke.composeai.rcplayer.edit.hardcodeNamedValues
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.rc.androidXExperimentalWrap

/**
 * A Wear widget template that fits any Glance Wear container: the widget's content inside Glance
 * Wear's container (as androidx's `WearWidgetContainer` draws it), with the container's padding and
 * corner radius left as named values ([HORIZONTAL_PADDING], [VERTICAL_PADDING], [CORNER_RADIUS], in
 * dp) rather than one widget's numbers. It is captured once, with the app's Remote Compose profile
 * (so its text may be `CORE_TEXT`) at host density, and [instantiate]d per widget on the watch.
 *
 * Instantiating follows `WearWidgetDocument.captureRawContent`: its named values become the
 * widget's [WearWidgetParams] (the same `horizontalPaddingDp.rdp` / `verticalPaddingDp.rdp` /
 * `cornerRadiusDp.rdp` it hands `WearWidgetContainer`), the document takes the header Glance Wear
 * writes for those params (its canvas, `(int)(dp × density)` pixels, and profile), the watch's
 * density and font scale are folded in, and anything the Wear widget profile can't play is
 * rewritten into what it can ([adaptTo]: `CORE_TEXT` as text layout).
 */
internal object WearWidgetTemplate {
  const val HORIZONTAL_PADDING = "wear.container.horizontal_padding"
  const val VERTICAL_PADDING = "wear.container.vertical_padding"
  const val CORNER_RADIUS = "wear.container.corner_radius"

  /** The capture canvas, at mdpi: [instantiate] replaces it with the widget's. */
  private val CANVAS = RemoteCreationDisplayInfo(200, 112, 160)

  /** A Large container's values, which only a host that sets none of them would see. */
  private const val DEFAULT_PADDING_DP = 8f
  private const val DEFAULT_CORNER_DP = 26f

  /**
   * [content] in the widget's container, as a template: see above. Androidx's [WearWidgetContainer]
   * is internal, so this draws what it draws for the default (empty) `WearWidgetBrush`: a round
   * rect in [SURFACE_CONTAINER_LOW] with the corner radius, under the content padded by the
   * horizontal and vertical padding.
   */
  suspend fun capture(context: Context, content: @Composable () -> Unit): ByteArray =
    captureSingleRemoteDocument(
        context = context,
        creationDisplayInfo = CANVAS,
        remoteDensity = RemoteDensity.Host,
        profile = androidXExperimentalWrap,
      ) {
        NamedContainer(content)
      }
      .bytes

  /** The container colour androidx's `WearWidgetContainer` fills for an empty brush. */
  private val SURFACE_CONTAINER_LOW = Color(39, 36, 48)

  @Composable
  private fun NamedContainer(content: @Composable () -> Unit) {
    val horizontal = named(HORIZONTAL_PADDING, DEFAULT_PADDING_DP)
    val vertical = named(VERTICAL_PADDING, DEFAULT_PADDING_DP)
    val corner = named(CORNER_RADIUS, DEFAULT_CORNER_DP)
    RemoteBox(
      modifier =
        RemoteModifier.fillMaxSize().drawWithContent {
          drawRoundRect(
            RemotePaint { color = SURFACE_CONTAINER_LOW.rc },
            cornerRadius = RemoteOffset(corner.toPx(), corner.toPx()),
          )
          drawContent()
        }
    ) {
      RemoteBox(modifier = RemoteModifier.fillMaxSize().padding(horizontal, vertical)) { content() }
    }
  }

  private fun named(name: String, defaultDp: Float) =
    RemoteDp.createNamedRemoteDp(name, defaultDp.dp, RemoteState.Domain.User)

  /**
   * [template] as the document for a widget with [params] on this watch, or null when it can't be
   * made playable as a Wear widget (the caller then captures through Glance Wear itself).
   */
  suspend fun instantiate(
    context: Context,
    template: ByteArray,
    params: WearWidgetParams,
    isPreview: Boolean = false,
  ): WearWidgetRawContent? {
    // Glance Wear's own capture of an empty container: its header (canvas, density, profile) and
    // extras are exactly what this widget's document carries.
    val frame =
      WearWidgetDocument(WearWidgetBrush.Companion) {}.captureRawContent(context, params, isPreview)
    val resources = context.resources
    val display = RcDisplay(resources.displayMetrics.density, resources.configuration.fontScale)
    val bytes =
      runCatching {
        val header = RcDocumentCodec.decode(frame.rcDocument, RcWireLimits()).header
        val filled =
          RcDocumentCodec.decode(template, RcWireLimits())
            .hardcodeNamedValues(
              mapOf(
                HORIZONTAL_PADDING to RcHardcodedValue.FloatValue(params.horizontalPaddingDp),
                VERTICAL_PADDING to RcHardcodedValue.FloatValue(params.verticalPaddingDp),
                CORNER_RADIUS to RcHardcodedValue.FloatValue(params.cornerRadiusDp),
              )
            )
            .hardcodeDensity(display.density, display.fontScale)
        val adapted =
          filled.adaptTo(
            RcTargetProfiles.WEAR_WIDGETS,
            setOf(RcApproximation.TEXT, RcApproximation.THEME_COLORS),
            display,
          )
        if (!adapted.isPlayable) return null
        RcDocumentCodec.encode(adapted.document.copy(header = header))
      }
        .getOrNull() ?: return null
    return WearWidgetRawContent(bytes, frame.extras, frame.widgetTitle)
  }
}
