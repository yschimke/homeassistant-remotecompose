@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import ee.schimke.composeai.rccreation.compose.text.RemoteFontWeight
import ee.schimke.composeai.rccreation.compose.text.RemoteTextAlign
import ee.schimke.composeai.rccreation.compose.text.RemoteTextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import ee.schimke.composeai.rccreation.compose.capture.toRemoteImageVector
import ee.schimke.composeai.rccreation.compose.layout.RemoteAlignment
import ee.schimke.composeai.rccreation.compose.layout.RemoteArrangement
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.layout.RemoteColumn
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.layout.RemoteRow
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.background
import ee.schimke.composeai.rccreation.compose.modifier.clickable
import ee.schimke.composeai.rccreation.compose.modifier.clip
import ee.schimke.composeai.rccreation.compose.modifier.contentDescription
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxSize
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxWidth
import ee.schimke.composeai.rccreation.compose.modifier.height
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.modifier.semantics
import ee.schimke.composeai.rccreation.compose.modifier.size
import ee.schimke.composeai.rccreation.compose.state.RemoteColor
import ee.schimke.composeai.rccreation.compose.state.rc
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.state.rf
import ee.schimke.composeai.rccreation.compose.state.rs
import ee.schimke.composeai.rccreation.foundation.icon.RemoteBasicIcon
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
 * and the rest share rows two at a time. A stepper ([HaTileFeature.NumberButtons]) keeps a row to
 * itself: at half a tile its two square buttons leave its value no room. So does a slider
 * ([HaTileFeature.Slider]): half a tile is too short a throw to set a value by.
 */
fun tileFeatureRows(
  features: List<HaTileFeature>,
  position: HaTileFeaturePosition,
): List<List<HaTileFeature>> =
  when (position) {
    HaTileFeaturePosition.Bottom -> features.map { listOf(it) }
    HaTileFeaturePosition.Inline ->
      buildList {
        var pair: HaTileFeature? = null
        for (feature in features.drop(1)) {
          when {
            feature is HaTileFeature.NumberButtons || feature is HaTileFeature.Slider ->
              add(listOf(feature))
            pair == null -> pair = feature
            else -> {
              add(listOf(pair, feature))
              pair = null
            }
          }
        }
        pair?.let { add(listOf(it)) }
      }
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
        RemoteBasicIcon(
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
 * caller); this draws the rest in [tileFeatureRows]: one per row at the bottom, or two to a row (a
 * stepper alone) when the first is inline.
 */
@Composable
@RemoteComposable
internal fun TileFeaturesBelow(data: HaTileData, accent: RemoteColor) {
  tileFeatureRows(data.features, data.featuresPosition).forEach { row ->
    // The gap is each but the last control's end padding, not `spacedBy`: the player shares a
    // row's weights out of its full width, so a spaced row of weighted controls overflows by the
    // gap.
    RemoteRow(modifier = RemoteModifier.fillMaxWidth().padding(top = TileFeatureGapDp.rdp)) {
      row.forEachIndexed { index, feature ->
        val gap = if (index < row.lastIndex) TileFeatureGapDp else 0
        RemoteBox(modifier = RemoteModifier.weight(1f.rf).padding(end = gap.rdp)) {
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
    is HaTileFeature.Slider -> RemoteHaSlider(feature.spec, accent, heightDp, subject)
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
    RemoteBox(modifier = RemoteModifier.weight(1f.rf), contentAlignment = RemoteAlignment.Center) {
      RemoteText(
        text = feature.valueLabel.rs,
        color = theme.primaryText,
        fontSize = HaType.Body,
        fontWeight = RemoteFontWeight.Medium,
        style = haTextStyle(),
        maxLines = 1,
        overflow = RemoteTextOverflow.Ellipsis,
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
        .then(haKeyChrome(if (click != null) theme.primaryText else theme.secondaryText)),
    contentAlignment = RemoteAlignment.Center,
  ) {
    RemoteBasicIcon(
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
        RemoteModifier.weight(1f.rf).then(click ?: RemoteModifier).height(heightDp.rdp).let {
          if (option.selected) it.clip(haControlShape(FeatureCornerDp)).background(accent) else it
        }
      val ink = if (option.selected) Color.White.rc else theme.primaryText
      RemoteColumn(
        modifier =
          segment.then(
            haKeyChrome(if (option.selected) accent else null, selected = option.selected),
          ),
        verticalArrangement = RemoteArrangement.Center,
        horizontalAlignment = RemoteAlignment.CenterHorizontally,
      ) {
        val icon = option.icon
        if (feature.showIcons && icon != null) {
          RemoteBasicIcon(
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
            fontWeight = RemoteFontWeight.Medium,
            style = haTextStyle(),
            textAlign = RemoteTextAlign.Center,
            maxLines = 1,
            overflow = RemoteTextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

/**
 * A tile's Wear form when it has a slider: the compact tile (icon, name, state) over its first
 * slider, at the inline feature height. A Wear container is too short for the full tile's feature
 * row ([tileNaturalHeightDp]), and the other features are taps a watch reaches full screen; the
 * slider is the control worth keeping in the card. Null [slider] draws the compact tile alone.
 */
@Composable
@RemoteComposable
fun RemoteHaTileCompactWithSlider(data: HaTileData, modifier: RemoteModifier = RemoteModifier) {
  val slider = data.features.firstOrNull { it is HaTileFeature.Slider } as? HaTileFeature.Slider
  val compact = data.copy(features = emptyList())
  if (slider == null) {
    RemoteHaTileCompact(compact, modifier)
    return
  }
  val accent = entityLook(data.entityId, data.accent, live = false).accent
  RemoteColumn(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = RemoteArrangement.spacedBy(HaSpace.S.rdp),
  ) {
    RemoteHaTileCompact(compact, RemoteModifier.fillMaxWidth())
    RemoteBox(modifier = RemoteModifier.fillMaxWidth().padding(horizontal = HaSpace.M.rdp)) {
      RemoteHaSlider(slider.spec, accent, TileInlineFeatureHeightDp, data.name)
    }
  }
}
