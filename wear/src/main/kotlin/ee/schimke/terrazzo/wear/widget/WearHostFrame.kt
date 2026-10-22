package ee.schimke.terrazzo.wear.widget

import androidx.glance.wear.core.WearWidgetParams

/**
 * The host frame a Wear widget design can leave to the watch: the named floats compose-ai-tools'
 * `HostFramedWearWidgetDocument` (`wear-preview-runtime`, its `HostFrame`) declares in place of the
 * padding and corner radius Glance Wear's own container bakes in. A design server design exported
 * with the container's `hostFrame: device` declares them, defaulting to the host it was captured
 * for; the watch writes in its own host's — a Pixel Watch squircle, a Samsung round host — before
 * it plays the design. A design without them keeps the frame it was captured with.
 *
 * The names are that class's, restated until this module depends on a `wear-preview-runtime` that
 * ships it.
 */
internal object WearHostFrame {
  const val HORIZONTAL_PADDING: String = "wear.widget.horizontalPaddingDp"
  const val VERTICAL_PADDING: String = "wear.widget.verticalPaddingDp"
  const val CORNER_RADIUS: String = "wear.widget.cornerRadiusDp"

  /** [params]' host frame, by name, in dp. */
  fun valuesOf(params: WearWidgetParams): Map<String, Float> =
    mapOf(
      HORIZONTAL_PADDING to params.horizontalPaddingDp,
      VERTICAL_PADDING to params.verticalPaddingDp,
      CORNER_RADIUS to params.cornerRadiusDp,
    )
}
