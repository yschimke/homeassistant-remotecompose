@file:Suppress("RestrictedApi")

package ee.schimke.ha.cards

import androidx.compose.ui.graphics.Color
import ee.schimke.composeai.rccreation.compose.layout.RemoteAlignment
import ee.schimke.composeai.rccreation.compose.layout.RemoteArrangement
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.layout.RemoteColumn
import ee.schimke.composeai.rccreation.compose.layout.RemoteRow
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.modifier.background
import ee.schimke.composeai.rccreation.compose.modifier.clip
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.shapes.RemoteRoundedCornerShape
import ee.schimke.composeai.rccreation.compose.state.RemoteColor
import ee.schimke.composeai.rccreation.compose.state.RemoteState
import ee.schimke.composeai.rccreation.compose.state.RemoteString
import ee.schimke.composeai.rccreation.compose.state.rc
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.state.rs
import ee.schimke.composeai.rccreation.compose.state.rsp
import ee.schimke.composeai.rccreation.compose.text.RemoteFontWeight
import ee.schimke.composeai.rccreation.compose.text.RemoteTextOverflow
import ee.schimke.ha.rc.components.currentRemoteHaTheme
import ee.schimke.ha.rc.design.CardDesignComponent
import ee.schimke.ha.rc.design.CardDesignNode

/**
 * The rest of the `ha-cards` palette a UI Builder design places, drawn in this process so a design
 * built from it (the device packs, docs/design/device-packs.md) renders here as it does in UI
 * Builder's own native lane: the frame's collapsible rows and columns, Material text and buttons,
 * and the measurement and status components. [CardDesignDocument] adds them to its components.
 *
 * Two are this host's own drawing of a Wear Remote Material 3 call the policy records
 * (`RemoteText`, `RemoteButton`), which Android alone links: the same layout, in the HA theme, not
 * the same pixels. Collapsible layouts draw as plain ones, so content that doesn't fit is clipped
 * rather than dropped. A `state` property draws its variable's initial value, and text bound to one
 * reads it as a named value, so a host's push updates it.
 */
internal object HaCardDesignLayouts {
  private fun CardDesignNode.color(name: String): RemoteColor? = argb(name)?.let { Color(it).rc }

  /** [name]'s text: the named value its state variable is, else its literal. */
  private fun CardDesignNode.text(name: String): RemoteString {
    val initial = string(name)
    return stateOf(name)?.let {
      RemoteString.createNamedRemoteString(it, initial, RemoteState.Domain.User)
    } ?: initial.rs
  }

  private fun verticalArrangement(node: CardDesignNode) =
    when (node.string("verticalArrangement")) {
      "center" -> RemoteArrangement.Center
      "bottom" -> RemoteArrangement.Bottom
      "spaceBetween" -> RemoteArrangement.SpaceBetween
      "spaceAround" -> RemoteArrangement.SpaceAround
      "spaceEvenly" -> RemoteArrangement.SpaceEvenly
      else -> RemoteArrangement.spacedBy(node.float("verticalSpacingDp").rdp)
    }

  private fun horizontalArrangement(node: CardDesignNode) =
    when (node.string("horizontalArrangement")) {
      "center" -> RemoteArrangement.Center
      "end" -> RemoteArrangement.End
      "spaceBetween" -> RemoteArrangement.SpaceBetween
      "spaceAround" -> RemoteArrangement.SpaceAround
      "spaceEvenly" -> RemoteArrangement.SpaceEvenly
      else -> RemoteArrangement.spacedBy(node.float("horizontalSpacingDp").rdp)
    }

  private val column: CardDesignComponent = { node, modifier ->
    RemoteColumn(
      modifier = modifier,
      verticalArrangement = verticalArrangement(node),
      horizontalAlignment =
        when (node.string("horizontalAlignment")) {
          "center" -> RemoteAlignment.CenterHorizontally
          "end" -> RemoteAlignment.End
          else -> RemoteAlignment.Start
        },
    ) {
      node.Children()
    }
  }

  private val row: CardDesignComponent = { node, modifier ->
    RemoteRow(
      modifier = modifier,
      horizontalArrangement = horizontalArrangement(node),
      verticalAlignment =
        when (node.string("verticalAlignment")) {
          "top" -> RemoteAlignment.Top
          "bottom" -> RemoteAlignment.Bottom
          else -> RemoteAlignment.CenterVertically
        },
    ) {
      node.Children()
    }
  }

  val components: Map<String, CardDesignComponent> =
    mapOf(
      "layout/column" to column,
      "layout/collapsible-column" to column,
      "layout/row" to row,
      "layout/collapsible-row" to row,
      "ha-cards/remote-text" to
        { node, modifier ->
          RemoteText(
            text = node.text("text"),
            modifier = modifier,
            color = node.color("color") ?: currentRemoteHaTheme().primaryText,
            fontSize = node.float("fontSize", 14f).rsp,
            fontWeight =
              when (node.string("fontWeight")) {
                "bold" -> RemoteFontWeight.Bold
                "medium" -> RemoteFontWeight.Medium
                else -> RemoteFontWeight.Normal
              },
            maxLines = node.int("maxLines", Int.MAX_VALUE),
            overflow = RemoteTextOverflow.Ellipsis,
          )
        },
      "ha-cards/remote-button" to
        { node, modifier ->
          // A filled button: the theme's accent behind its content (RemoteButton's filled variant).
          RemoteBox(
            modifier =
              modifier
                .clip(RemoteRoundedCornerShape(20.rdp))
                .background(currentRemoteHaTheme().placeholderBackground)
                .padding(horizontal = 16.rdp, vertical = 8.rdp),
            contentAlignment = RemoteAlignment.Center,
          ) {
            node.Children()
          }
        },
      "ha-cards/status-chip" to
        { node, modifier ->
          HaCardStatusChip(
            node.string("status"),
            modifier,
            entityId = node.string("entityId"),
            isOn = node.bool("isOn"),
            activeColor = node.color("activeColor") ?: HaCardColors.Active,
            inactiveColor = node.color("inactiveColor") ?: HaCardColors.Inactive,
          )
        },
      "ha-cards/thermostat" to
        { node, modifier ->
          // A `state` unit and range follow the thermostat's own (`climate.s0.unit`, `.min_temp`).
          BoundThermostat(
            node.string("name"),
            node.float("current"),
            modifier,
            target = node.float("target", -1f),
            min = node.float("min", 7f),
            max = node.float("max", 35f),
            unit = node.string("unit", "°C"),
            mode = node.string("mode", "heat"),
            entityId = node.string("entityId"),
            unitBinding = node.stateOf("unit"),
            minBinding = node.stateOf("min"),
            maxBinding = node.stateOf("max"),
          )
        },
      "ha-cards/thermometer" to
        { node, modifier ->
          HaCardThermometer(
            node.string("name"),
            node.float("value"),
            modifier,
            unit = node.string("unit", "°C"),
            entityId = node.string("entityId"),
            presentation = node.string("presentation", HaCardPresentationDefault),
            min = node.float("min"),
            max = node.float("max"),
            attribute = node.string("attribute"),
          )
        },
      "ha-cards/measurement" to
        { node, modifier ->
          HaCardMeasurement(
            node.string("name"),
            node.float("value"),
            modifier,
            unit = node.string("unit"),
            entityId = node.string("entityId"),
            kind = node.string("kind", "auto"),
            presentation = node.string("presentation", HaCardPresentationDefault),
            min = node.float("min"),
            max = node.float("max"),
            attribute = node.string("attribute"),
          )
        },
      "ha-cards/arc-meter" to
        { node, modifier ->
          HaCardArcMeter(
            node.float("value"),
            modifier,
            min = node.float("min", 0f),
            max = node.float("max", 100f),
            entityId = node.string("entityId"),
            attribute = node.string("attribute"),
          )
        },
      "ha-cards/garage-door" to
        { node, modifier ->
          HaCardGarageDoor(
            modifier,
            entityId = node.string("entityId"),
            position = node.float("position", 0f),
            stateInt = node.int("stateInt", 0),
            widthDp = node.int("widthDp", 240),
            heightDp = node.int("heightDp", 200),
          )
        },
      "ha-cards/wall-thermostat" to
        { node, modifier ->
          BoundWallThermostat(
            modifier,
            entityId = node.string("entityId"),
            current = node.float("current", 19.5f),
            target = node.float("target", 21f),
            mode = node.string("mode", "Heat"),
            unit = node.string("unit", "°C"),
            unitBinding = node.stateOf("unit"),
          )
        },
      "ha-cards/bar-meter" to
        { node, modifier ->
          HaCardBarMeter(
            node.float("value"),
            modifier,
            min = node.float("min", 0f),
            max = node.float("max", 100f),
            entityId = node.string("entityId"),
            vertical = node.bool("vertical"),
            attribute = node.string("attribute"),
          )
        },
    )
}
