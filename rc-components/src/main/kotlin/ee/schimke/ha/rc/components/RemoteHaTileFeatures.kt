@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.remote.creation.compose.capture.toRemoteImageVector
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.contentDescription
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.semantics
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.remote.material3.RemoteIcon

/**
 * Height of one tile feature control: HA's `--feature-height` (42px, `hui-card-features`).
 *
 * An inline feature keeps the icon's height instead ([TileInlineFeatureHeightDp], HA's
 * `--ha-space-9`).
 */
const val TileFeatureHeightDp: Int = 42

/** Space above each feature row: HA's `--ha-card-feature-gap` (12px). */
const val TileFeatureGapDp: Int = 12

/** An inline feature's height, beside the name and state (HA `--ha-space-9`, 36px). */
const val TileInlineFeatureHeightDp: Int = 36

/**
 * The height of a [RemoteHaTile] with [featureRows] rows of features below its name and state: the
 * two-line tile ([TileTwoLineMinHeightDp]) plus a gap and a control per row.
 */
fun tileNaturalHeightDp(featureRows: Int): Int =
  TileTwoLineMinHeightDp + featureRows.coerceAtLeast(0) * (TileFeatureGapDp + TileFeatureHeightDp)

/**
 * Rows of features below the tile's name and state, after HA's `computeCardFeatureRows`: one per
 * feature at the bottom; with [HaTileFeaturePosition.Inline] the first feature sits beside the name
 * and the rest share rows two at a time.
 */
fun tileFeatureRows(featureCount: Int, position: HaTileFeaturePosition): Int =
  when (position) {
    HaTileFeaturePosition.Bottom -> featureCount
    HaTileFeaturePosition.Inline -> ((featureCount - 1).coerceAtLeast(0) + 1) / 2
  }

/** Diameter of the badge disc over the tile icon's corner (HA `ha-tile-badge`, 16px). */
private const val BadgeDp = 16

/**
 * The tile's icon slot with [badge] over its bottom-right corner. Overlaid boxes rather than an
 * `offset`, which alpha010 doesn't play reliably (see `RemoteHaEntityRow`).
 */
@Composable
@RemoteComposable
internal fun TileIconWithBadge(
  badge: HaTileBadge,
  iconSlotDp: Int,
  icon: @Composable @RemoteComposable () -> Unit,
) {
  val theme = haTheme()
  RemoteBox(modifier = RemoteModifier.size((iconSlotDp + 4).rdp)) {
    RemoteBox(
      modifier = RemoteModifier.fillMaxSize(),
      contentAlignment = RemoteAlignment.TopStart,
    ) {
      icon()
    }
    RemoteBox(
      modifier = RemoteModifier.fillMaxSize(),
      contentAlignment = RemoteAlignment.BottomEnd,
    ) {
      RemoteBox(
        modifier =
          RemoteModifier.size(BadgeDp.rdp)
            .clip(haControlShape(BadgeDp / 2, round = true))
            .background(badge.color?.let { haInk(it) } ?: theme.secondaryText),
        contentAlignment = RemoteAlignment.Center,
      ) {
        RemoteIcon(
          imageVector = badge.icon.toRemoteImageVector(),
          contentDescription = badge.description.rs,
          modifier = RemoteModifier.size(11.rdp),
          tint = theme.cardBackground,
        )
      }
    }
  }
}

/**
 * The tile's features under its name and state. [inline] is the one beside the name (drawn by the
 * caller); this draws the rest, one per row at the bottom or two to a row when the first is inline.
 */
@Composable
@RemoteComposable
internal fun TileFeaturesBelow(data: HaTileData, accent: RemoteColor) {
  val below =
    when (data.featuresPosition) {
      HaTileFeaturePosition.Bottom -> data.features
      HaTileFeaturePosition.Inline -> data.features.drop(1)
    }
  if (below.isEmpty()) return
  val perRow = if (data.featuresPosition == HaTileFeaturePosition.Inline) 2 else 1
  below.chunked(perRow).forEach { row ->
    RemoteRow(
      modifier = RemoteModifier.fillMaxWidth().padding(top = TileFeatureGapDp.rdp),
      horizontalArrangement = RemoteArrangement.spacedBy(TileFeatureGapDp.rdp),
    ) {
      row.forEach { feature ->
        RemoteBox(modifier = RemoteModifier.weight(1f)) {
          TileFeature(feature, accent, TileFeatureHeightDp, data.name)
        }
      }
    }
  }
}

/**
 * One feature control, [heightDp] tall, filling its width. Each control's spoken label starts with
 * [subject], the tile's name: a dashboard of thermostats otherwise reads "Heat" and "Target
 * temperature +" once per tile, with nothing to tell them apart.
 */
@Composable
@RemoteComposable
internal fun TileFeature(
  feature: HaTileFeature,
  accent: RemoteColor,
  heightDp: Int,
  subject: String,
) {
  when (feature) {
    // The steppers keep the primary ink, as HA's number buttons do; the accent marks a selection.
    is HaTileFeature.NumberButtons -> NumberButtons(feature, heightDp, subject)
    is HaTileFeature.ModeSelect -> ModeSelect(feature, accent, heightDp, subject)
  }
}

/** A feature control's spoken label: the tile's [subject], then what the control does. */
private fun spoken(subject: String, label: String): String = "$subject, $label"

/** HA's control track: the disabled colour at 20%, here the theme's secondary text, softened. */
@Composable
private fun featureTrack(): RemoteColor {
  val secondary = haTheme().secondaryText
  return secondary.copy(alpha = secondary.alpha * 0.18f.rf)
}

/** HA's `--feature-border-radius` (12px). */
private const val FeatureCornerDp = 12

/**
 * HA's `ha-control-number-buttons`: − and + at the ends of a rounded track, the value between. A
 * step that leaves the entity's range carries no action and draws its button faded.
 */
@Composable
private fun NumberButtons(feature: HaTileFeature.NumberButtons, heightDp: Int, subject: String) {
  val theme = haTheme()
  RemoteRow(
    modifier =
      RemoteModifier.fillMaxWidth()
        .height(heightDp.rdp)
        .clip(haControlShape(FeatureCornerDp))
        .background(featureTrack()),
    verticalAlignment = RemoteAlignment.CenterVertically,
  ) {
    StepButton(
      Icons.Filled.Remove,
      feature.decrement,
      spoken(subject, "${feature.description} −"),
      heightDp,
    )
    RemoteBox(modifier = RemoteModifier.weight(1f), contentAlignment = RemoteAlignment.Center) {
      RemoteText(
        text = feature.valueLabel.rs,
        color = theme.primaryText,
        fontSize = HaType.Body,
        fontWeight = FontWeight.Medium,
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    StepButton(
      Icons.Filled.Add,
      feature.increment,
      spoken(subject, "${feature.description} +"),
      heightDp,
    )
  }
}

@Composable
private fun StepButton(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  action: HaAction,
  description: String,
  heightDp: Int,
) {
  val theme = haTheme()
  val click = action.toRemoteAction()?.let { RemoteModifier.clickable(it) }
  RemoteBox(
    modifier =
      RemoteModifier.then(click ?: RemoteModifier)
        .size(heightDp.rdp)
        .then(consoleKeyChrome(if (click != null) theme.primaryText else theme.secondaryText)),
    contentAlignment = RemoteAlignment.Center,
  ) {
    RemoteIcon(
      imageVector = icon.toRemoteImageVector(),
      contentDescription = description.rs,
      modifier = RemoteModifier.size(20.rdp),
      tint =
        if (click != null) theme.primaryText
        else theme.secondaryText.copy(alpha = theme.secondaryText.alpha * 0.5f.rf),
    )
  }
}

/**
 * HA's `ha-control-select`: one segment per option on a rounded track, the current option filled
 * with the tile's [accent] and its content in the card colour.
 */
@Composable
private fun ModeSelect(
  feature: HaTileFeature.ModeSelect,
  accent: RemoteColor,
  heightDp: Int,
  subject: String,
) {
  val theme = haTheme()
  RemoteRow(
    modifier =
      RemoteModifier.fillMaxWidth()
        .height(heightDp.rdp)
        .clip(haControlShape(FeatureCornerDp))
        .background(featureTrack()),
    verticalAlignment = RemoteAlignment.CenterVertically,
  ) {
    feature.options.forEach { option ->
      val click = option.action.toRemoteAction()?.let { RemoteModifier.clickable(it) }
      val segment =
        RemoteModifier.weight(1f).then(click ?: RemoteModifier).height(heightDp.rdp).let {
          if (option.selected) it.clip(haControlShape(FeatureCornerDp)).background(accent) else it
        }
      val ink = if (option.selected) Color.White.rc else theme.primaryText
      RemoteColumn(
        modifier =
          segment.then(
            consoleKeyChrome(if (option.selected) accent else null, selected = option.selected)
          ),
        verticalArrangement = RemoteArrangement.Center,
        horizontalAlignment = RemoteAlignment.CenterHorizontally,
      ) {
        val icon = option.icon
        if (feature.showIcons && icon != null) {
          RemoteIcon(
            imageVector = icon.toRemoteImageVector(),
            contentDescription = spoken(subject, option.label).rs,
            modifier = RemoteModifier.size(20.rdp),
            tint = ink,
          )
        } else {
          RemoteText(
            text = option.label.rs,
            modifier =
              RemoteModifier.semantics { contentDescription = spoken(subject, option.label).rs },
            color = ink,
            fontSize = HaType.Label,
            fontWeight = FontWeight.Medium,
            style = haTextStyle(),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}
