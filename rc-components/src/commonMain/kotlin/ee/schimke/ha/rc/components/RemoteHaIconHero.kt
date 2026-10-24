@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.state.RemoteTextUnit
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/**
 * The sizes a hero draws at. A hero is the top of the adaptive ladder: a cell with room to spare
 * shows the same identity, name and state as the full tier, set larger, rather than the full tier
 * at its own size in the middle of a blank cell.
 *
 * @property iconDp the icon's box.
 * @property name the name's type size.
 * @property state the state's type size: it is the value, so it grows the most.
 */
data class HaHeroScale(val iconDp: Int, val name: RemoteTextUnit, val state: RemoteTextUnit) {
  companion object {
    /** The value on the largest heroes: a size above [HaType.Display], for a cell 260 dp across. */
    private val HeroDisplay = 44.rsp

    /**
     * The scale for a wide hero row [widthDp] across, null below two launcher columns: a 48 dp icon
     * beside a display value from three columns, a 36 dp one beside a small value in two.
     */
    fun forRow(widthDp: Int): HaHeroScale? =
      when {
        widthDp >= 200 -> HaHeroScale(iconDp = 48, name = HaType.Title, state = HaType.Display)
        widthDp >= MinWidthDp ->
          HaHeroScale(iconDp = 36, name = HaType.Label, state = HaType.ValueSmall)
        else -> null
      }

    /** The narrowest and shortest cell a hero looks composed in: two launcher columns, two rows. */
    const val MinWidthDp = 120
    const val MinHeightDp = 150

    /**
     * The scale for a [widthDp] × [heightDp] cell, null for one smaller than [MinWidthDp] ×
     * [MinHeightDp]. Steps by the cell's short side, so a tall narrow cell stays as modest as a
     * square one of its width.
     */
    fun forCell(widthDp: Int, heightDp: Int): HaHeroScale? {
      if (widthDp < MinWidthDp || heightDp < MinHeightDp) return null
      return when (minOf(widthDp, heightDp)) {
        in 0 until 200 -> HaHeroScale(iconDp = 56, name = HaType.Title, state = HaType.Value)
        in 200 until 260 -> HaHeroScale(iconDp = 80, name = HaType.Headline, state = HaType.Display)
        else -> HaHeroScale(iconDp = 112, name = HaType.Headline, state = HeroDisplay)
      }
    }
  }
}

/**
 * The hero tier of an icon-and-state card (tile, entity, button): the tinted icon at
 * [HaHeroScale.iconDp], the name under it and the state, all centred in the cell it fills.
 *
 * Its state is bound ([entityLook] live), so use it only where capture picked this tier alone (a
 * launcher widget, captured per size), as [RemoteHaIconChip]'s `bindState`. [showState] false
 * leaves the state out, for a button that does not show one.
 */
@Composable
@RemoteComposable
fun RemoteHaIconHero(
  data: HaTileData,
  scale: HaHeroScale,
  modifier: RemoteModifier = RemoteModifier,
  showState: Boolean = true,
) {
  val theme = haTheme()
  val clickable =
    data.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
  val look = entityLook(data.entityId, data.accent, live = true)

  RemoteColumn(
    modifier = modifier.then(clickable).fillMaxSize().padding(HaSpace.L.rdp),
    verticalArrangement = RemoteArrangement.Center,
    horizontalAlignment = RemoteAlignment.CenterHorizontally,
  ) {
    RemoteBox(
      modifier =
        RemoteModifier.size(scale.iconDp.rdp)
          .then(
            if (data.tapAction != HaAction.None) consoleKeyChrome(look.accent) else RemoteModifier
          ),
      contentAlignment = RemoteAlignment.Center,
    ) {
      HaStateIcon(
        icon = data.icon,
        accent = data.accent,
        look = look,
        tint = look.accent,
        contentDescription = entityName(data.entityId, data.name),
        modifier = RemoteModifier.size(scale.iconDp.rdp),
      )
    }
    RemoteBox(modifier = RemoteModifier.padding(top = HaSpace.M.rdp)) {
      RemoteText(
        text = entityName(data.entityId, data.name),
        color = look.dim(theme.primaryText),
        fontSize = scale.name,
        fontWeight = FontWeight.Medium,
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    if (showState) {
      RemoteText(
        text = data.state,
        color = look.dim(theme.secondaryText),
        fontSize = scale.state,
        fontWeight = haValueWeight(),
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/**
 * The hero tier for a wide, short cell — a Wear Large slot, a launcher widget one row tall and
 * three or more columns wide: the tinted icon at [iconDp] beside the name over the state at
 * [stateSize], the row centred in the cell it fills. A cell like that holds a big value beside its
 * identity, not a stack of them.
 *
 * Bound like [RemoteHaIconHero]: use it only where this tier is the one captured, or inside a
 * single size gate.
 */
@Composable
@RemoteComposable
fun RemoteHaWideHero(
  data: HaTileData,
  scale: HaHeroScale,
  modifier: RemoteModifier = RemoteModifier,
  showState: Boolean = true,
  live: Boolean = true,
) {
  val iconDp = scale.iconDp
  val nameSize = scale.name
  val stateSize = scale.state
  val theme = haTheme()
  val clickable =
    data.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
  val look = entityLook(data.entityId, data.accent, live = live)
  RemoteRow(
    modifier = modifier.then(clickable).fillMaxSize().padding(horizontal = HaSpace.M.rdp),
    verticalAlignment = RemoteAlignment.CenterVertically,
  ) {
    RemoteBox(
      modifier =
        RemoteModifier.size(iconDp.rdp)
          .then(
            if (data.tapAction != HaAction.None) consoleKeyChrome(look.accent) else RemoteModifier
          ),
      contentAlignment = RemoteAlignment.Center,
    ) {
      HaStateIcon(
        icon = data.icon,
        accent = data.accent,
        look = look,
        tint = look.accent,
        contentDescription = entityName(data.entityId, data.name),
        modifier = RemoteModifier.size((iconDp * 2 / 3).rdp),
      )
    }
    RemoteColumn(modifier = RemoteModifier.weight(1f).padding(start = HaSpace.M.rdp)) {
      RemoteText(
        text = entityName(data.entityId, data.name),
        // With no state under it, the name is the row's main text.
        color = look.dim(if (showState) theme.secondaryText else theme.primaryText),
        fontSize = nameSize,
        fontWeight = FontWeight.Medium,
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (showState) {
        RemoteText(
          text = data.state,
          color = look.dim(theme.primaryText),
          fontSize = stateSize,
          fontWeight = haValueWeight(),
          style = haTextStyle(),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}
