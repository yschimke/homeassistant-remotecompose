@file:Suppress("RestrictedApi")

package ee.schimke.ha.cards

import androidx.compose.remote.creation.compose.capture.toRemoteImageVector
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.shapes.RemoteCircleShape
import androidx.compose.remote.creation.compose.state.RemoteBoolean
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.remote.material3.RemoteIcon
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaButtonData
import ee.schimke.ha.rc.components.HaEntitiesData
import ee.schimke.ha.rc.components.HaEntityRowData
import ee.schimke.ha.rc.components.HaGaugeData
import ee.schimke.ha.rc.components.HaGlanceCellData
import ee.schimke.ha.rc.components.HaGlanceData
import ee.schimke.ha.rc.components.HaHeadingData
import ee.schimke.ha.rc.components.HaHeadingStyle
import ee.schimke.ha.rc.components.HaTileData
import ee.schimke.ha.rc.components.HaToggleAccent
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.ProvideCardChrome
import ee.schimke.ha.rc.components.RemoteHaButton
import ee.schimke.ha.rc.components.RemoteHaEntities
import ee.schimke.ha.rc.components.RemoteHaEntityRow
import ee.schimke.ha.rc.components.RemoteHaGauge
import ee.schimke.ha.rc.components.RemoteHaGlance
import ee.schimke.ha.rc.components.RemoteHaHeading
import ee.schimke.ha.rc.components.RemoteHaMetric
import ee.schimke.ha.rc.components.RemoteHaTile
import ee.schimke.ha.rc.components.RemoteHaToggleButton
import ee.schimke.ha.rc.components.RemoteHaToggleSwitch
import ee.schimke.ha.rc.components.cardChrome
import ee.schimke.ha.rc.components.currentRemoteHaTheme

// ---------------------------------------------------------------------------------------------
// The `ha-cards` palette's Home Assistant components.
//
// Each one is a `@RemoteComposable` over a Tier-1 `RemoteHa*` component from `:rc-components`,
// reshaped so that every argument is a value a UI-builder design can hold and the record-driven
// Remote Compose emitter can write: `String`, `Int` / `Float`, `Boolean`, and `RemoteColor` (a
// design's `#AARRGGBB` literal, written `Color(0x…).rc`). The `RemoteHa*` data classes carry
// `ImageVector`s, `RemoteString`s and `HaAction`s, none of which a design can spell, so the
// wrappers build them:
//
// - **Icons** are named by key ([HaCardIcons]).
// - **Live values.** A non-blank `entityId` turns the wrapper's initial values into the same named
//   bindings the dashboard converters emit (`<entityId>.state`, `<entityId>.is_on`, …), so a design
//   exported from here keeps up with Home Assistant the way a converted card does. Blank, the
//   values are constants: a static mock.
// - **Taps.** A toggleable entity toggles; any other entity opens its more-info dialog. No entity,
//   no action.
//
// The same reshaping the typed catalog's `Catalog*` wrappers do one tier up
// (ui-builder-catalog/…/HomeAssistantCatalog.kt), here at the Remote tier, so a design's export is
// Remote Compose source that drops into a Wear widget, a launcher widget or a dashboard card.
// ---------------------------------------------------------------------------------------------

/** The accents a design starts from: HA's light-on amber and its inactive grey. */
object HaCardColors {
  val Active: RemoteColor = Color(0xFFFFC107).rc
  val Inactive: RemoteColor = Color(0xFF8F8F8F).rc
}

private fun String.entityOrNull(): String? = trim().takeIf { it.isNotEmpty() }

private fun accent(
  toggleable: Boolean,
  isOn: Boolean,
  activeColor: RemoteColor,
  inactiveColor: RemoteColor,
) =
  HaToggleAccent(
    activeAccent = activeColor,
    inactiveAccent = inactiveColor,
    initiallyOn = isOn,
    toggleable = toggleable,
  )

private fun tapAction(entityId: String?, toggleable: Boolean): HaAction =
  when {
    entityId == null -> HaAction.None
    toggleable -> HaAction.Toggle(entityId)
    else -> HaAction.MoreInfo(entityId)
  }

/** `12.0` reads `12`, `21.5` stays `21.5`: how HA shows a gauge's reading. */
private fun Float.reading(): String =
  if (this == toInt().toFloat()) toInt().toString() else toString()

/**
 * The surface a dashboard card sits on: the HA theme's card background and divider, rounded, with
 * the 12dp inset the `RemoteHa*` cards use. The `ha-cards/dashboard-card` root exports as this.
 *
 * The `RemoteHa*` cards inside it draw no chrome of their own, as in HA's stack-in-card: a tile in
 * a dashboard card is one card, not a card in a card.
 */
@Composable
@RemoteComposable
fun HaCardSurface(
  modifier: RemoteModifier = RemoteModifier,
  content: @Composable @RemoteComposable () -> Unit,
) {
  val theme = currentRemoteHaTheme()
  RemoteBox(
    modifier =
      modifier.fillMaxWidth().then(cardChrome(theme.cardBackground, theme.divider)).padding(12.rdp)
  ) {
    ProvideCardChrome(enabled = false, content = content)
  }
}

/** HA `tile` card: an icon (in a chip when toggleable) beside the entity's name and state. */
@Composable
@RemoteComposable
fun HaCardTile(
  name: String,
  state: String,
  modifier: RemoteModifier = RemoteModifier,
  entityId: String = "",
  icon: String = "light",
  toggleable: Boolean = false,
  isOn: Boolean = false,
  activeColor: RemoteColor = HaCardColors.Active,
  inactiveColor: RemoteColor = HaCardColors.Inactive,
) {
  val entity = entityId.entityOrNull()
  RemoteHaTile(
    HaTileData(
      entityId = entity,
      name = name,
      state = LiveValues.state(entity, state),
      icon = HaCardIcons.vector(icon),
      accent = accent(toggleable, isOn, activeColor, inactiveColor),
      tapAction = tapAction(entity, toggleable),
    ),
    modifier,
  )
}

/** One row of an HA `entities` card, or the whole `entity` card: icon, name and state. */
@Composable
@RemoteComposable
fun HaCardEntityRow(
  name: String,
  state: String,
  modifier: RemoteModifier = RemoteModifier,
  entityId: String = "",
  icon: String = "light",
  toggleable: Boolean = false,
  isOn: Boolean = false,
  activeColor: RemoteColor = HaCardColors.Active,
  inactiveColor: RemoteColor = HaCardColors.Inactive,
) {
  val entity = entityId.entityOrNull()
  RemoteHaEntityRow(
    HaEntityRowData(
      entityId = entity,
      name = name,
      state = LiveValues.state(entity, state),
      icon = HaCardIcons.vector(icon),
      accent = accent(toggleable, isOn, activeColor, inactiveColor),
      tapAction = tapAction(entity, toggleable),
    ),
    modifier,
  )
}

/**
 * HA `entities` card, simple form: an optional title over up to three rows. A row with a blank name
 * is left out, as is a blank title. For more rows, or rows bound to entities, stack
 * `ha-cards/entity-row`s in a card surface.
 */
@Composable
@RemoteComposable
fun HaCardEntities(
  title: String,
  modifier: RemoteModifier = RemoteModifier,
  row1Name: String = "",
  row1State: String = "",
  row1Icon: String = "light",
  row2Name: String = "",
  row2State: String = "",
  row2Icon: String = "light",
  row3Name: String = "",
  row3State: String = "",
  row3Icon: String = "light",
) {
  val rows =
    listOf(
        Triple(row1Name, row1State, row1Icon),
        Triple(row2Name, row2State, row2Icon),
        Triple(row3Name, row3State, row3Icon),
      )
      .filter { it.first.isNotBlank() }
      .map { (name, state, icon) ->
        HaEntityRowData(
          entityId = null,
          name = name,
          state = state.rs,
          icon = HaCardIcons.vector(icon),
          accent = accent(false, false, HaCardColors.Active, HaCardColors.Inactive),
        )
      }
  RemoteHaEntities(HaEntitiesData(title = title.ifBlank { null }, rows = rows), modifier)
}

/** HA `gauge` card: a half-circle dial for a reading between [min] and [max]. */
@Composable
@RemoteComposable
fun HaCardGauge(
  name: String,
  value: Float,
  modifier: RemoteModifier = RemoteModifier,
  min: Float = 0f,
  max: Float = 100f,
  unit: String = "",
  entityId: String = "",
) {
  val entity = entityId.entityOrNull()
  RemoteHaGauge(
    HaGaugeData(
      entityId = entity,
      name = name,
      valueText = LiveValues.state(entity, value.reading()),
      unit = unit.ifBlank { null },
      value = LiveValues.numericState(entity, value),
      min = min.toDouble(),
      max = max.toDouble(),
      tapAction = tapAction(entity, toggleable = false),
    ),
    modifier,
  )
}

/**
 * HA `glance` card: an optional title over up to three icon cells. Blank-named cells are left out.
 */
@Composable
@RemoteComposable
fun HaCardGlance(
  title: String,
  modifier: RemoteModifier = RemoteModifier,
  cell1Name: String = "",
  cell1State: String = "",
  cell1Icon: String = "light",
  cell2Name: String = "",
  cell2State: String = "",
  cell2Icon: String = "light",
  cell3Name: String = "",
  cell3State: String = "",
  cell3Icon: String = "light",
) {
  val cells =
    listOf(
        Triple(cell1Name, cell1State, cell1Icon),
        Triple(cell2Name, cell2State, cell2Icon),
        Triple(cell3Name, cell3State, cell3Icon),
      )
      .filter { it.first.isNotBlank() }
      .map { (name, state, icon) ->
        HaGlanceCellData(
          entityId = null,
          name = name,
          state = state.rs,
          icon = HaCardIcons.vector(icon),
          accent = accent(false, false, HaCardColors.Active, HaCardColors.Inactive),
        )
      }
  RemoteHaGlance(HaGlanceData(title = title.ifBlank { null }, cells = cells), modifier)
}

/** HA `heading` card: a section title, or a smaller subtitle. */
@Composable
@RemoteComposable
fun HaCardHeading(
  title: String,
  modifier: RemoteModifier = RemoteModifier,
  subtitle: Boolean = false,
) {
  RemoteHaHeading(
    HaHeadingData(
      title = title,
      style = if (subtitle) HaHeadingStyle.Subtitle else HaHeadingStyle.Title,
    ),
    modifier,
  )
}

/**
 * HA `button` card: a large icon with the name under it. Toggleable, the accent follows the
 * entity's live on/off state and a tap toggles it.
 */
@Composable
@RemoteComposable
fun HaCardButton(
  name: String,
  modifier: RemoteModifier = RemoteModifier,
  entityId: String = "",
  icon: String = "light",
  showName: Boolean = true,
  toggleable: Boolean = false,
  isOn: Boolean = false,
  activeColor: RemoteColor = HaCardColors.Active,
  inactiveColor: RemoteColor = HaCardColors.Inactive,
) {
  val entity = entityId.entityOrNull()
  val data =
    HaButtonData(
      entityId = entity,
      name = name,
      icon = HaCardIcons.vector(icon),
      accent = accent(toggleable, isOn, activeColor, inactiveColor),
      showName = showName,
      tapAction = tapAction(entity, toggleable),
    )
  if (toggleable) RemoteHaToggleButton(data, modifier) else RemoteHaButton(data, modifier)
}

/**
 * The HA toggle switch an entities row ends in. Bound to an entity, it follows `<entityId>.is_on`
 * and a tap toggles the entity; unbound, it is fixed at [isOn].
 */
@Composable
@RemoteComposable
fun HaCardToggle(
  isOn: Boolean,
  modifier: RemoteModifier = RemoteModifier,
  entityId: String = "",
  activeColor: RemoteColor = HaCardColors.Active,
  inactiveColor: RemoteColor = HaCardColors.Inactive,
) {
  val entity = entityId.entityOrNull()
  RemoteHaToggleSwitch(
    isOn = LiveValues.isOn(entity, isOn) ?: RemoteBoolean(isOn),
    activeAccent = activeColor,
    inactiveAccent = inactiveColor,
    modifier = modifier,
    tapAction = tapAction(entity, toggleable = true),
  )
}

/** An HA entity icon by key ([HaCardIcons]), tinted. */
@Composable
@RemoteComposable
fun HaCardIcon(
  icon: String,
  modifier: RemoteModifier = RemoteModifier,
  color: RemoteColor = HaCardColors.Active,
  sizeDp: Int = 24,
) {
  RemoteIcon(
    imageVector = HaCardIcons.vector(icon).toRemoteImageVector(),
    contentDescription = icon.rs,
    modifier = modifier.size(sizeDp.rdp),
    tint = color,
  )
}

/** A sensor value with a compact label and unit, bound to an entity when supplied. */
@Composable
@RemoteComposable
fun HaCardMetric(
  label: String,
  value: String,
  modifier: RemoteModifier = RemoteModifier,
  unit: String = "",
  entityId: String = "",
  valueColor: RemoteColor? = null,
) {
  RemoteHaMetric(label, value, unit, entityId.entityOrNull(), valueColor, modifier)
}

/** A compact live status label for a row, card header or widget summary. */
@Composable
@RemoteComposable
fun HaCardStatusChip(
  status: String,
  modifier: RemoteModifier = RemoteModifier,
  entityId: String = "",
  isOn: Boolean = false,
  activeColor: RemoteColor = HaCardColors.Active,
  inactiveColor: RemoteColor = HaCardColors.Inactive,
) {
  val entity = entityId.entityOrNull()
  val color =
    (LiveValues.isOn(entity, isOn) ?: RemoteBoolean(isOn)).select(activeColor, inactiveColor)
  RemoteRow(
    modifier = modifier.padding(horizontal = 8.rdp, vertical = 4.rdp),
    verticalAlignment = RemoteAlignment.CenterVertically,
  ) {
    RemoteBox(modifier = RemoteModifier.size(8.rdp).clip(RemoteCircleShape).background(color))
    RemoteText(
      text = LiveValues.state(entity, status),
      modifier = RemoteModifier.padding(start = 6.rdp),
      color = color,
      fontSize = 12.rsp,
      style = RemoteTextStyle.Default,
    )
  }
}
