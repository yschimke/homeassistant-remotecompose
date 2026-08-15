@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.action.combinedAction
import androidx.compose.remote.creation.compose.action.valueChange
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteStateLayout
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.border
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxHeight
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.shapes.RemoteCircleShape
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rememberMutableRemoteInt
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.ri
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.remote.material3.RemoteIcon
import java.text.DecimalFormat

/**
 * `alarm-panel` card — title, status badge, ARM action buttons, and the numeric keypad (matches
 * HA's reference). Launcher widgets with a known code length accumulate digits in document-local
 * mutable state and emit one [HaAction.AlarmPin] at completion. Other players and unknown-length
 * codes retain the host-buffered [HaAction.AlarmKey] path.
 */
@Composable
@RemoteComposable
fun RemoteHaAlarmPanel(
  data: HaAlarmPanelData,
  modifier: RemoteModifier = RemoteModifier,
  fillHeight: Boolean = false,
) {
  val theme = haTheme()
  val statusByKey = data.statuses.associateBy { it.stateKey }
  val initialStatus = statusByKey[data.initialStateInt] ?: data.statuses.first()
  // Keypad accent stays tied to the initial state — keypad chrome is
  // permanent at capture time, the live binding only swaps the status
  // chrome above.
  val keypadAccent = initialStatus.accent
  RemoteBox(
    modifier =
      modifier
        .fillMaxWidth()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 14.rdp, vertical = 12.rdp)
  ) {
    // On a taller cell than the keypad needs (Fixed mode, large
    // launcher widget) the status + buttons stay at the top and the
    // keypad takes the remaining height, spreading its rows to fill it
    // rather than the whole block gluing to the top over a blank bottom
    // half (Principle 7/8).
    RemoteColumn(
      modifier = if (fillHeight) RemoteModifier.fillMaxSize() else RemoteModifier.fillMaxWidth(),
      verticalArrangement = RemoteArrangement.spacedBy(10.rdp),
    ) {
      StatusRow(data, statusByKey, initialStatus, theme)

      // ARM AWAY / ARM HOME / DISARM buttons.
      if (data.actions.isNotEmpty()) {
        RemoteRow(
          modifier = RemoteModifier.fillMaxWidth(),
          horizontalArrangement =
            RemoteArrangement.spacedBy(8.rdp, RemoteAlignment.CenterHorizontally),
        ) {
          data.actions.forEach { action -> ActionPill(action, theme) }
        }
      }

      if (data.showKeypad) {
        Keypad(
          data.entityId,
          data.codeLength,
          keypadAccent,
          theme,
          modifier = if (fillHeight) RemoteModifier.weight(1f).fillMaxHeight() else RemoteModifier,
          fill = fillHeight,
        )
      }
    }
  }
}

/**
 * Wide-thin Fixed-mode alarm variant — the state shield on the left, panel name + live status label
 * on the right, no ARM buttons and no keypad. Targets short/narrow widget cells (Wear S/L, the
 * smaller launcher chip) where the full keypad won't fit. Keeps the card's identity (shield +
 * state, P1) and its disambiguating name (P2) and stays live: the same `RemoteStateLayout` over the
 * state-int keys swaps the shield colour + label at playback. The keypad (P5) returns once the cell
 * is tall/wide enough for the full card.
 */
@Composable
@RemoteComposable
fun RemoteHaAlarmPanelWide(data: HaAlarmPanelData, modifier: RemoteModifier = RemoteModifier) {
  val theme = haTheme()
  val statusByKey = data.statuses.associateBy { it.stateKey }
  val initialStatus = statusByKey[data.initialStateInt] ?: data.statuses.first()
  val keys = data.statuses.map { it.stateKey }.toIntArray()
  val stateInt = LiveValues.intState(data.entityId, data.initialStateInt)
  RemoteBox(
    modifier =
      modifier
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 12.rdp, vertical = 8.rdp)
  ) {
    RemoteStateLayout(stateInt, *keys, modifier = RemoteModifier.fillMaxSize()) { key ->
      val status = statusByKey[key] ?: initialStatus
      RemoteRow(
        modifier = RemoteModifier.fillMaxSize(),
        verticalAlignment = RemoteAlignment.CenterVertically,
        horizontalArrangement = RemoteArrangement.spacedBy(10.rdp),
      ) {
        RemoteBox(
          modifier =
            RemoteModifier.size(40.rdp)
              .clip(RemoteCircleShape)
              .border(2.rdp, status.accent.rc, RemoteCircleShape),
          contentAlignment = RemoteAlignment.Center,
        ) {
          RemoteIcon(
            imageVector = status.icon,
            contentDescription = status.label.rs,
            modifier = RemoteModifier.size(22.rdp),
            tint = status.accent.rc,
          )
        }
        RemoteColumn(
          modifier = RemoteModifier.weight(1f),
          verticalArrangement = RemoteArrangement.Center,
        ) {
          RemoteText(
            text = data.title.rs,
            color = theme.primaryText,
            fontSize = 14.rsp,
            fontWeight = FontWeight.Medium,
            style = RemoteTextStyle.Default,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          RemoteText(
            text = status.label.rs,
            color = status.accent.rc,
            fontSize = 12.rsp,
            style = RemoteTextStyle.Default,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

@Composable
@RemoteComposable
private fun StatusRow(
  data: HaAlarmPanelData,
  statusByKey: Map<Int, HaAlarmStatus>,
  initialStatus: HaAlarmStatus,
  theme: RemoteHaTheme,
) {
  val keys = data.statuses.map { it.stateKey }.toIntArray()
  val stateInt = LiveValues.intState(data.entityId, data.initialStateInt)
  RemoteStateLayout(stateInt, *keys) { key ->
    val status = statusByKey[key] ?: initialStatus
    RemoteRow(
      modifier = RemoteModifier.fillMaxWidth(),
      verticalAlignment = RemoteAlignment.CenterVertically,
      horizontalArrangement = RemoteArrangement.SpaceBetween,
    ) {
      RemoteColumn {
        RemoteText(
          text = data.title.rs,
          color = theme.primaryText,
          fontSize = 16.rsp,
          fontWeight = FontWeight.Medium,
          style = RemoteTextStyle.Default,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        RemoteText(
          text = status.label.rs,
          color = status.accent.rc,
          fontSize = 12.rsp,
          style = RemoteTextStyle.Default,
          maxLines = 1,
        )
      }
      RemoteBox(
        modifier =
          RemoteModifier.size(40.rdp)
            .clip(RemoteCircleShape)
            .border(2.rdp, status.accent.rc, RemoteCircleShape),
        contentAlignment = RemoteAlignment.Center,
      ) {
        RemoteIcon(
          imageVector = status.icon,
          contentDescription = status.label.rs,
          modifier = RemoteModifier.size(22.rdp),
          tint = status.accent.rc,
        )
      }
    }
  }
}

@Composable
private fun ActionPill(action: HaAlarmAction, theme: RemoteHaTheme) {
  val click =
    action.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
  val accent = action.accent.rc
  RemoteBox(
    modifier =
      RemoteModifier.then(click)
        .clip(RemoteRoundedCornerShape(6.rdp))
        .background(accent.copy(alpha = accent.alpha * 0.0f.rf))
        .border(1.rdp, accent, RemoteRoundedCornerShape(6.rdp))
        .padding(horizontal = 14.rdp, vertical = 8.rdp),
    contentAlignment = RemoteAlignment.Center,
  ) {
    RemoteText(
      text = action.label.rs,
      color = accent,
      fontSize = 12.rsp,
      fontWeight = FontWeight.Medium,
      style = RemoteTextStyle.Default,
      maxLines = 1,
    )
  }
}

@Composable
private fun Keypad(
  entityId: String?,
  codeLength: Int?,
  accent: androidx.compose.ui.graphics.Color,
  theme: RemoteHaTheme,
  modifier: RemoteModifier = RemoteModifier,
  fill: Boolean = false,
) {
  // Int expressions are supported by AndroidX ValueChangeAction; dynamic string mutation is not.
  // Keep this bounded so decimal accumulation cannot overflow. Unknown/long codes use the existing
  // per-key host coordinator, which also supplies the idle-timeout behavior.
  val localLength = codeLength?.takeIf { isWidgetActionCapture() && it in 1..9 }
  val pin = rememberMutableRemoteInt(0)
  val digitCount = rememberMutableRemoteInt(0)

  if (localLength != null && entityId != null) {
    RemoteStateLayout(digitCount, *IntArray(localLength) { it }) { count ->
      KeypadContent(
        entityId = entityId,
        accent = accent,
        theme = theme,
        modifier = modifier,
        fill = fill,
        pin = pin,
        digitCount = digitCount,
        enteredDigits = count,
        codeLength = localLength,
      )
    }
  } else {
    KeypadContent(entityId, accent, theme, modifier, fill)
  }
}

@Composable
@RemoteComposable
private fun KeypadContent(
  entityId: String?,
  accent: androidx.compose.ui.graphics.Color,
  theme: RemoteHaTheme,
  modifier: RemoteModifier = RemoteModifier,
  fill: Boolean = false,
  pin: androidx.compose.remote.creation.compose.state.MutableRemoteInt? = null,
  digitCount: androidx.compose.remote.creation.compose.state.MutableRemoteInt? = null,
  enteredDigits: Int = 0,
  codeLength: Int? = null,
) {
  RemoteColumn(
    modifier = modifier.fillMaxWidth().padding(top = 6.rdp),
    verticalArrangement =
      if (fill) RemoteArrangement.SpaceEvenly else RemoteArrangement.spacedBy(6.rdp),
    horizontalAlignment = RemoteAlignment.CenterHorizontally,
  ) {
    listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEach { row ->
      RemoteRow(horizontalArrangement = RemoteArrangement.spacedBy(8.rdp)) {
        row.forEach {
          KeypadKey(entityId, it, it, accent, theme, pin, digitCount, enteredDigits, codeLength)
        }
      }
    }
    RemoteRow(horizontalArrangement = RemoteArrangement.spacedBy(8.rdp)) {
      RemoteBox(modifier = RemoteModifier.size(48.rdp))
      KeypadKey(entityId, "0", "0", accent, theme, pin, digitCount, enteredDigits, codeLength)
      KeypadKey(
        entityId,
        "⌫",
        "backspace",
        accent,
        theme,
        pin,
        digitCount,
        enteredDigits,
        codeLength,
      )
    }
  }
}

@Composable
private fun KeypadKey(
  entityId: String?,
  label: String,
  key: String,
  accent: androidx.compose.ui.graphics.Color,
  theme: RemoteHaTheme,
  pin: androidx.compose.remote.creation.compose.state.MutableRemoteInt? = null,
  digitCount: androidx.compose.remote.creation.compose.state.MutableRemoteInt? = null,
  enteredDigits: Int = 0,
  codeLength: Int? = null,
) {
  val localAction =
    if (entityId != null && pin != null && digitCount != null && codeLength != null) {
      when {
        key == "backspace" && enteredDigits > 0 ->
          combinedAction(
            valueChange(pin, pin / 10),
            valueChange(digitCount, (enteredDigits - 1).ri),
          )
        key == "backspace" -> combinedAction(valueChange(pin, 0.ri), valueChange(digitCount, 0.ri))
        key.length == 1 && key[0].isDigit() -> {
          val completedPin = pin * 10 + key.toInt()
          if (enteredDigits == codeLength - 1) {
            val submit =
              widgetAlarmPinAction(
                entityId,
                completedPin.toRemoteString(DecimalFormat("0".repeat(codeLength))),
              )
            submit?.let {
              combinedAction(it, valueChange(pin, 0.ri), valueChange(digitCount, 0.ri))
            }
          } else {
            combinedAction(
              valueChange(pin, completedPin),
              valueChange(digitCount, (enteredDigits + 1).ri),
            )
          }
        }
        else -> null
      }
    } else {
      null
    }
  val action = localAction ?: entityId?.let { HaAction.AlarmKey(it, key).toRemoteAction() }
  val click = action?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
  RemoteBox(
    modifier =
      RemoteModifier.then(click)
        .size(48.rdp)
        .clip(RemoteRoundedCornerShape(6.rdp))
        .border(1.rdp, accent.rc, RemoteRoundedCornerShape(6.rdp)),
    contentAlignment = RemoteAlignment.Center,
  ) {
    RemoteText(
      text = label.rs,
      color = accent.rc,
      fontSize = 16.rsp,
      fontWeight = FontWeight.Medium,
      style = RemoteTextStyle.Default,
      maxLines = 1,
    )
  }
}
