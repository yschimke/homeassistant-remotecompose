@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/**
 * HA `entities` card — stacked list of [RemoteHaEntityRow]s with an optional title header. Matches
 * `hui-entities-card.ts` visually.
 *
 * [dense] tightens each row's vertical padding (6dp to 4dp) for a fixed widget canvas, where the
 * list has a set height to fit its rows into rather than growing to them.
 */
@Composable
@RemoteComposable
fun RemoteHaEntities(
  data: HaEntitiesData,
  modifier: RemoteModifier = RemoteModifier,
  dense: Boolean = false,
) {
  val theme = haTheme()
  RemoteBox(
    modifier =
      modifier
        .fillMaxWidth()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 12.rdp, vertical = 10.rdp)
  ) {
    RemoteColumn(horizontalAlignment = RemoteAlignment.Start) {
      if (data.title != null) {
        RemoteText(
          text = cardTitle(data.title),
          color = theme.primaryText,
          fontSize = adaptiveTitleSize(data.title),
          fontWeight = FontWeight.Medium,
          style = haTextStyle(),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        RemoteBox(modifier = RemoteModifier.padding(top = 4.rdp))
      }
      data.rows.forEach { row ->
        RemoteHaEntityRow(
          row,
          verticalPaddingDp = if (dense) DenseRowPaddingDp else 6,
          minHeightDp = if (dense) 0 else EntityRowMinHeightDp,
        )
      }
    }
  }
}

private const val DenseRowPaddingDp = 4

/**
 * How [RemoteHaEntitiesFitted] sizes its rows to a fixed widget canvas. Each style has a row height
 * it needs ([rowMinHeightDp]) so a converter can work out how many rows a canvas holds.
 */
enum class HaEntitiesFit(
  /** The smallest height one row of this style needs, its vertical padding included. */
  val rowMinHeightDp: Int,
  /** The height the title line and its gap take above the rows. */
  val titleHeightDp: Int,
  /** The card's own vertical padding, top and bottom together. */
  val verticalPaddingDp: Int,
) {
  /**
   * A Wear slot: an 18dp icon, the name and the state on one line each a [HaType.Label] or
   * [HaType.Body] step, no switch. A small slot (~44dp of content) holds two of these rows.
   */
  Compact(rowMinHeightDp = 20, titleHeightDp = 22, verticalPaddingDp = 4),
  /**
   * A roomy launcher widget: a 28dp icon, a [HaType.BodyLarge] name and the value a step up at
   * [HaType.ValueSmall], so the extra canvas makes the readings bigger instead of blank.
   */
  Expanded(rowMinHeightDp = 36, titleHeightDp = 28, verticalPaddingDp = 20),
}

/**
 * The entities list for a fixed widget canvas: the title (when [HaEntitiesData.title] is set) over
 * [HaEntitiesData.rows], which share the rest of the canvas equally and centre in their share, so
 * the list fills the cell instead of pinning to the top over a blank band (Principle 8). The caller
 * passes only the rows that fit; see [HaEntitiesFit.rowMinHeightDp].
 *
 * Gaps are padding, not `spacedBy`: a RemoteCompose row does not subtract arrangement gaps from its
 * weighted children.
 *
 * The rows' accents and switches follow [looks]: the live `<entity>.is_on` bindings from
 * [entityLooks], which a widget bake fills with the card's values. A caller sharing a state layout
 * with sibling tiers creates them once, outside the layout, and hands the same [HaEntityLooks] to
 * every tier (or to the one tier that draws rows): two registrations of one named `RemoteBoolean`
 * blank the other branch in alpha010 (#224). Without [looks], the rows resolve both from the
 * encode-time state, like [RemoteHaIconChip] — right only for a capture of the real entities, never
 * for a design template. The state text is always a live binding.
 *
 * [largeText] steps a [HaEntitiesFit.Compact] row's text up from [HaType.Label] to [HaType.Body],
 * for a large Wear slot.
 */
@Composable
@RemoteComposable
fun RemoteHaEntitiesFitted(
  data: HaEntitiesData,
  fit: HaEntitiesFit,
  modifier: RemoteModifier = RemoteModifier,
  largeText: Boolean = false,
  looks: HaEntityLooks? = null,
) {
  val theme = haTheme()
  RemoteBox(
    modifier =
      modifier
        .fillMaxSize()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 12.rdp, vertical = (fit.verticalPaddingDp / 2).rdp)
  ) {
    RemoteColumn(modifier = RemoteModifier.fillMaxSize()) {
      if (data.title != null) {
        RemoteText(
          text = cardTitle(data.title),
          color = theme.primaryText,
          fontSize =
            when (fit) {
              HaEntitiesFit.Compact -> HaType.Body
              HaEntitiesFit.Expanded -> adaptiveTitleSize(data.title)
            },
          fontWeight = FontWeight.Medium,
          style = haTextStyle(),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      data.rows.forEachIndexed { i, row ->
        FittedEntityRow(
          row,
          looks?.looks?.getOrNull(i) ?: entityLook(row.entityId, row.accent, live = false),
          fit,
          largeText,
          RemoteModifier.fillMaxWidth().weight(1f),
        )
      }
    }
  }
}

/**
 * One row of [RemoteHaEntitiesFitted]: `[icon] name ...... state`, centred in its weighted share.
 * The name takes what the trailing value leaves and ellipsises, so neighbouring labels never run
 * together.
 */
@Composable
@RemoteComposable
private fun FittedEntityRow(
  data: HaEntityRowData,
  look: HaEntityLook,
  fit: HaEntitiesFit,
  largeText: Boolean,
  modifier: RemoteModifier,
) {
  val theme = haTheme()
  val clickable =
    data.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
  val expanded = fit == HaEntitiesFit.Expanded
  val nameSize =
    when {
      expanded -> HaType.BodyLarge
      largeText -> HaType.Body
      else -> HaType.Label
    }
  RemoteRow(
    modifier = modifier.then(clickable),
    verticalAlignment = RemoteAlignment.CenterVertically,
  ) {
    HaStateIcon(
      icon = data.icon,
      accent = data.accent,
      look = look,
      tint = look.accent,
      // Decorative: the name is drawn beside it.
      contentDescription = null,
      modifier = RemoteModifier.size((if (expanded) 28 else 18).rdp),
    )
    RemoteBox(
      modifier =
        RemoteModifier.weight(1f).padding(start = (if (expanded) 12 else 8).rdp, end = 8.rdp)
    ) {
      RemoteText(
        text = entityName(data.entityId, data.name),
        color = look.dim(theme.primaryText),
        fontSize = nameSize,
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    val stateText: @Composable @RemoteComposable (RemoteModifier) -> Unit = { stateModifier ->
      RemoteText(
        text = data.state,
        modifier = stateModifier,
        color = look.dim(theme.primaryText),
        fontSize = if (expanded) HaType.ValueSmall else nameSize,
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    if (!expanded) {
      stateText(RemoteModifier)
    } else if (look.isOn == null && data.toggle && data.accent.toggleable) {
      // The switch at its encode-time position: a widget is re-baked when the entity changes. An
      // entity unavailable at encode time shows its state text, as HA's toggle row does.
      if (look.staticallyAvailable) {
        RemoteHaToggleSwitchByProgress(
          progress = (if (data.accent.initiallyOn) 1f else 0f).rf,
          activeAccent = look.activeAccent,
          inactiveAccent = look.inactiveAccent,
          clickable = clickable,
        )
      } else {
        stateText(RemoteModifier)
      }
    } else {
      TrailingSwitchOrState(
        look = look,
        toggle = data.toggle,
        switch = { isOn, switchModifier ->
          RemoteHaToggleSwitch(
            isOn = isOn,
            activeAccent = look.activeAccent,
            inactiveAccent = look.inactiveAccent,
            modifier = switchModifier,
            tapAction = data.tapAction,
          )
        },
        state = stateText,
      )
    }
  }
}

/**
 * The live look (`<entity>.is_on` accent and switch) of each of [rows], for
 * [RemoteHaEntitiesFitted]. Create it once per document and share it across sibling size tiers.
 */
class HaEntityLooks internal constructor(internal val looks: List<HaEntityLook>)

/** [HaEntityLooks] for [rows], registering each toggleable entity's `<entity>.is_on` once. */
@Composable
fun entityLooks(rows: List<HaEntityRowData>): HaEntityLooks =
  HaEntityLooks(rows.map { entityLook(it.entityId, it.accent) })
