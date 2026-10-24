package ee.schimke.terrazzo.wear.widget

import android.content.Context
import androidx.glance.wear.core.WearWidgetParams
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardRegistry
import ee.schimke.ha.rc.converterFor

/**
 * A Wear widget container's frame: its size, padding and corner radius, in dp. The Wear app draws
 * each card in the frame its widget gets, so a card looks the same in the app as it does once added
 * as a widget.
 *
 * The values come from the watch's own widget host: [remember] keeps the last params it handed a
 * widget of each container, and [forContainer] reads them back. Until a widget has been added there
 * are none, and the defaults are Glance Wear's own preview values for a round watch.
 */
internal data class WearWidgetFrame(
  val container: String,
  val widthDp: Float,
  val heightDp: Float,
  val horizontalPaddingDp: Float,
  val verticalPaddingDp: Float,
  val cornerRadiusDp: Float,
) {
  companion object {
    private const val PREFS = "wear_widget_frames"

    val DEFAULT_SMALL = WearWidgetFrame(DesignKey.WEAR_SMALL, 200f, 60f, 8f, 8f, 26f)
    val DEFAULT_LARGE = WearWidgetFrame(DesignKey.WEAR_LARGE, 200f, 112f, 8f, 8f, 26f)

    /** This watch's frame for [container] ([DesignKey.WEAR_SMALL] or [DesignKey.WEAR_LARGE]). */
    fun forContainer(context: Context, container: String): WearWidgetFrame {
      val default = if (container == DesignKey.WEAR_SMALL) DEFAULT_SMALL else DEFAULT_LARGE
      val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      if (!prefs.contains("$container.width")) return default
      return WearWidgetFrame(
        container,
        prefs.getFloat("$container.width", default.widthDp),
        prefs.getFloat("$container.height", default.heightDp),
        prefs.getFloat("$container.horizontal_padding", default.horizontalPaddingDp),
        prefs.getFloat("$container.vertical_padding", default.verticalPaddingDp),
        prefs.getFloat("$container.corner_radius", default.cornerRadiusDp),
      )
    }

    /** Keeps the frame the widget host gave [params], for the app's cards of that container. */
    fun remember(context: Context, params: WearWidgetParams) {
      val container = containerOf(params.containerType) ?: return
      context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putFloat("$container.width", params.widthDp)
        .putFloat("$container.height", params.heightDp)
        .putFloat("$container.horizontal_padding", params.horizontalPaddingDp)
        .putFloat("$container.vertical_padding", params.verticalPaddingDp)
        .putFloat("$container.corner_radius", params.cornerRadiusDp)
        .apply()
    }
  }
}

/**
 * The widget container [card] takes in the Wear app: Small for a card whose data fits only the
 * small container ([wearCardDataTier]) or whose natural height is a single row, Large otherwise.
 */
internal fun wearAppContainer(
  card: CardConfig,
  snapshot: HaSnapshot,
  registry: CardRegistry,
): String {
  if (!wearCardDataTier(card.type).supportsLarge) return DesignKey.WEAR_SMALL
  val natural = registry.converterFor(card.type)?.naturalHeightDp(card, snapshot)
  return if (natural != null && natural <= SMALL_MAX_NATURAL_HEIGHT_DP) DesignKey.WEAR_SMALL
  else DesignKey.WEAR_LARGE
}

/**
 * Tallest natural height that still reads as one row in the small container: the same cut the
 * cards' own Wear ladders make between their Small and Large tiers (`WEAR_SMALL_MAX_HEIGHT_DP` in
 * `:rc-converter`).
 */
private const val SMALL_MAX_NATURAL_HEIGHT_DP = 80
