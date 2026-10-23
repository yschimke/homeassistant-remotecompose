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
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteDp
import androidx.compose.remote.creation.compose.state.RemotePaint
import androidx.compose.remote.creation.compose.state.RemoteState
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
import ee.schimke.composeai.rcplayer.edit.namedVariables
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.rc.androidXExperimentalWrap
import ee.schimke.ha.rc.components.ProvideExpressionNames

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

  /** The container's fill, which the watch writes from the card's state ([ContainerTint]). */
  const val CONTAINER_COLOR = "wear.container.color"

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
        // Expressions named in composition order, so a card drawn by its converter or by a card
        // design wrapping it names them alike.
        ProvideExpressionNames { NamedContainer(content) }
      }
      .bytes

  /** The container colour androidx's `WearWidgetContainer` fills for an empty brush. */
  private val SURFACE_CONTAINER_LOW = Color(39, 36, 48)

  @Composable
  private fun NamedContainer(content: @Composable () -> Unit) {
    val horizontal = named(HORIZONTAL_PADDING, DEFAULT_PADDING_DP)
    val vertical = named(VERTICAL_PADDING, DEFAULT_PADDING_DP)
    val corner = named(CORNER_RADIUS, DEFAULT_CORNER_DP)
    val fill =
      RemoteColor.createNamedRemoteColor(
        CONTAINER_COLOR,
        SURFACE_CONTAINER_LOW,
        RemoteState.Domain.User,
      )
    RemoteBox(
      modifier =
        RemoteModifier.fillMaxSize().drawWithContent {
          drawRoundRect(
            RemotePaint { color = fill },
            cornerRadius = RemoteOffset(corner.toPx(), corner.toPx()),
          )
          drawContent()
        }
    ) {
      RemoteBox(modifier = RemoteModifier.fillMaxSize().padding(horizontal, vertical)) { content() }
    }
  }

  /**
   * Whether [document] leaves its container frame to the watch: it declares one of these named
   * values, as a template [capture]s and a design exported with the widget container's `hostFrame:
   * device` (compose-ai-tools' `HostFramedWearWidgetDocument`) do. False for one that can't be
   * read.
   */
  fun namesFrame(document: ByteArray): Boolean = runCatching {
    RcDocumentCodec.decode(document, RcWireLimits()).namedVariables.any {
      it.name.removePrefix(USER_PREFIX) in FRAME_NAMES
    }
  }
    .getOrDefault(false)

  private const val USER_PREFIX = "USER:"
  private val FRAME_NAMES = setOf(HORIZONTAL_PADDING, VERTICAL_PADDING, CORNER_RADIUS)

  private fun named(name: String, defaultDp: Float) =
    RemoteDp.createNamedRemoteDp(name, defaultDp, RemoteState.Domain.User)

  /**
   * [template] in [frame], for the Wear app's own player: the container values [instantiate] fills
   * for a widget, without the widget header or the Wear widget profile's rewrites, so the app's
   * card is the widget's layout. Its bindings stay, for the app to push live values. Null when it
   * can't be read.
   */
  fun forApp(template: ByteArray, frame: WearWidgetFrame, containerArgb: Int? = null): ByteArray? =
    runCatching {
      RcDocumentCodec.encode(
        RcDocumentCodec.decode(template, RcWireLimits())
          .hardcodeNamedValues(
            mapOf(
              HORIZONTAL_PADDING to RcHardcodedValue.FloatValue(frame.horizontalPaddingDp),
              VERTICAL_PADDING to RcHardcodedValue.FloatValue(frame.verticalPaddingDp),
              CORNER_RADIUS to RcHardcodedValue.FloatValue(frame.cornerRadiusDp),
              CONTAINER_COLOR to
                RcHardcodedValue.Color(containerArgb ?: SURFACE_CONTAINER_LOW.toArgb()),
            )
          )
      )
    }
    .getOrNull()

  /**
   * [template] as the document for a widget with [params] on this watch, or null when it can't be
   * made playable as a Wear widget (the caller then captures through Glance Wear itself).
   * [containerArgb] fills the container, the card's state tint ([ContainerTint]); null keeps the
   * default surface.
   */
  suspend fun instantiate(
    context: Context,
    template: ByteArray,
    params: WearWidgetParams,
    isPreview: Boolean = false,
    containerArgb: Int? = null,
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
                CONTAINER_COLOR to
                  RcHardcodedValue.Color(containerArgb ?: SURFACE_CONTAINER_LOW.toArgb()),
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
