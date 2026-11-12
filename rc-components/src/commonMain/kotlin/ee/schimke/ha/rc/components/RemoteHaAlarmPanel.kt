@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import ee.schimke.composeai.rccreation.compose.action.combinedAction
import ee.schimke.composeai.rccreation.compose.action.valueChangeAction
import ee.schimke.composeai.rccreation.compose.capture.toRemoteImageVector
import ee.schimke.composeai.rccreation.compose.layout.RemoteAlignment
import ee.schimke.composeai.rccreation.compose.layout.RemoteArrangement
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.layout.RemoteColumn
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.layout.RemoteRow
import ee.schimke.composeai.rccreation.compose.layout.RemoteStateLayout
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.background
import ee.schimke.composeai.rccreation.compose.modifier.border
import ee.schimke.composeai.rccreation.compose.modifier.clickable
import ee.schimke.composeai.rccreation.compose.modifier.clip
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxHeight
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxSize
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxWidth
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.modifier.size
import ee.schimke.composeai.rccreation.compose.state.MutableRemoteInt
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.state.rf
import ee.schimke.composeai.rccreation.compose.state.ri
import ee.schimke.composeai.rccreation.compose.state.rs
import ee.schimke.composeai.rccreation.compose.text.RemoteFontWeight
import ee.schimke.composeai.rccreation.compose.text.RemoteTextOverflow
import ee.schimke.composeai.rccreation.foundation.icon.RemoteBasicIcon

/**
 * `alarm-panel` card — title, status badge, ARM action buttons, and the numeric keypad (matches
 * HA's reference). Launcher widgets with a known code length accumulate digits in document-local
 * mutable state and emit one [HaAction.AlarmPin] at completion. Other players and unknown-length
 * codes retain the host-buffered [HaAction.AlarmKey] path.
 *
 * [narrow] is the form for a launcher widget narrower than the full card's button row but tall
 * enough to be more than the Wide chip (a `3×3` or `3×4` cell): the ARM buttons two to a row with
 * tighter padding, tighter gaps and 44 dp keys, and [showKeypad] (off where the cell is too short
 * for it) decides whether the keypad follows them. The buttons still act without it: an arm that
 * needs a code asks for one ([HaAction.AlarmIntent]). [actionsPerRow] `1` stacks them, for a cell
 * two launcher columns wide, too narrow for two side by side.
 */
@Composable
@RemoteComposable
fun RemoteHaAlarmPanel(
  data: HaAlarmPanelData,
  modifier: RemoteModifier = RemoteModifier,
  fillHeight: Boolean = false,
  narrow: Boolean = false,
  showKeypad: Boolean = data.showKeypad,
  actionsPerRow: Int = 2,
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
        .padding(horizontal = 14.rdp, vertical = 12.rdp),
  ) {
    // On a taller cell than the keypad needs (Fixed mode, large
    // launcher widget) the status + buttons stay at the top and the
    // keypad takes the remaining height, spreading its rows to fill it
    // rather than the whole block gluing to the top over a blank bottom
    // half (Principle 7/8).
    RemoteColumn(
      modifier = if (fillHeight) RemoteModifier.fillMaxSize() else RemoteModifier.fillMaxWidth(),
      verticalArrangement = RemoteArrangement.spacedBy((if (narrow) 6 else 10).rdp),
    ) {
      StatusRow(data, statusByKey, initialStatus, theme)

      // ARM AWAY / ARM HOME / DISARM buttons.
      if (narrow && data.actions.isNotEmpty()) {
        data.actions.chunked(actionsPerRow).forEach { row ->
          RemoteRow(
            modifier = RemoteModifier.fillMaxWidth(),
            horizontalArrangement =
              RemoteArrangement.spacedBy(8.rdp, RemoteAlignment.CenterHorizontally),
          ) {
            row.forEach { action -> ActionPill(action, theme, horizontalPaddingDp = 6) }
          }
        }
      } else if (data.actions.isNotEmpty()) {
        RemoteRow(
          modifier = RemoteModifier.fillMaxWidth(),
          horizontalArrangement =
            RemoteArrangement.spacedBy(8.rdp, RemoteAlignment.CenterHorizontally),
        ) {
          data.actions.forEach { action -> ActionPill(action, theme) }
        }
      }

      if (data.showKeypad && showKeypad) {
        Keypad(
          data.entityId,
          data.codeLength,
          keypadAccent,
          theme,
          modifier =
            if (fillHeight) RemoteModifier.weight(1f.rf).fillMaxHeight() else RemoteModifier,
          fill = fillHeight,
          keyDp = if (narrow) NarrowKeyDp else KeyDp,
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
        // 2dp, not more: the Wear small slot's content box is ~44dp, and the name (BodyLarge)
        // over the status (Label) is ~39dp of it (lines are ~1.33× their size). The row centres
        // itself on taller cells.
        .padding(horizontal = 12.rdp, vertical = 2.rdp),
  ) {
    RemoteStateLayout(stateInt, *keys, modifier = RemoteModifier.fillMaxSize()) { key ->
      val status = statusByKey[key] ?: initialStatus
      RemoteRow(
        modifier = RemoteModifier.fillMaxSize(),
        verticalAlignment = RemoteAlignment.CenterVertically,
      ) {
        // The gap is the text column's padding: spacedBy isn't taken out of a weighted share.
        RemoteBox(
          modifier =
            RemoteModifier.size(40.rdp)
              .clip(haControlShape(20, round = true))
              .border(2.rdp, haInk(status.accent), haControlShape(20, round = true)),
          contentAlignment = RemoteAlignment.Center,
        ) {
          RemoteBasicIcon(
            imageVector = status.icon.toRemoteImageVector(),
            contentDescription = status.label.rs,
            modifier = RemoteModifier.size(22.rdp),
            tint = haInk(status.accent),
          )
        }
        RemoteColumn(
          modifier = RemoteModifier.weight(1f.rf).padding(start = 10.rdp),
          verticalArrangement = RemoteArrangement.Center,
        ) {
          RemoteText(
            text = entityName(data.entityId, data.title),
            color = theme.primaryText,
            fontSize = HaType.BodyLarge,
            fontWeight = RemoteFontWeight.Medium,
            style = haTextStyle(),
            maxLines = 1,
            overflow = RemoteTextOverflow.Ellipsis,
          )
          RemoteText(
            text = status.label.rs,
            color = haInk(status.accent),
            fontSize = HaType.Label,
            style = haTextStyle(),
            maxLines = 1,
            overflow = RemoteTextOverflow.Ellipsis,
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
          text = entityName(data.entityId, data.title),
          color = theme.primaryText,
          fontSize = HaType.Title,
          fontWeight = RemoteFontWeight.Medium,
          style = haTextStyle(),
          maxLines = 1,
          overflow = RemoteTextOverflow.Ellipsis,
        )
        RemoteText(
          text = status.label.rs,
          color = haInk(status.accent),
          fontSize = HaType.Label,
          style = haTextStyle(),
          maxLines = 1,
        )
      }
      RemoteBox(
        modifier =
          RemoteModifier.size(40.rdp)
            .clip(haControlShape(20, round = true))
            .border(2.rdp, haInk(status.accent), haControlShape(20, round = true)),
        contentAlignment = RemoteAlignment.Center,
      ) {
        RemoteBasicIcon(
          imageVector = status.icon.toRemoteImageVector(),
          contentDescription = status.label.rs,
          modifier = RemoteModifier.size(22.rdp),
          tint = haInk(status.accent),
        )
      }
    }
  }
}

@Composable
private fun ActionPill(
  action: HaAlarmAction,
  theme: RemoteHaTheme,
  modifier: RemoteModifier = RemoteModifier,
  horizontalPaddingDp: Int = 14,
) {
  val click =
    action.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
  val accent = haInk(action.accent)
  RemoteBox(
    modifier =
      modifier
        .then(click)
        .clip(haControlShape(6))
        .background(accent.copy(alpha = accent.alpha * 0.0f.rf))
        .border(1.rdp, accent, haControlShape(6))
        .then(haKeyChrome(accent))
        .padding(horizontal = horizontalPaddingDp.rdp, vertical = 8.rdp),
    contentAlignment = RemoteAlignment.Center,
  ) {
    RemoteText(
      text = action.label.rs,
      color = if (haLayouts().keyFaces) theme.primaryText else accent,
      fontSize = HaType.Label,
      fontWeight = RemoteFontWeight.Medium,
      style = haTextStyle(),
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
  keyDp: Int = KeyDp,
) {
  // Int expressions are supported by AndroidX ValueChangeAction; dynamic string mutation is not.
  // Keep this bounded so decimal accumulation cannot overflow. Unknown/long codes use the existing
  // per-key host coordinator, which also supplies the idle-timeout behavior.
  val localLength = codeLength?.takeIf { isWidgetActionCapture() && it in 1..9 }
  val pin = remember { MutableRemoteInt(0) }
  val digitCount = remember { MutableRemoteInt(0) }

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
        keyDp = keyDp,
      )
    }
  } else {
    KeypadContent(entityId, accent, theme, modifier, fill, keyDp = keyDp)
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
  pin: ee.schimke.composeai.rccreation.compose.state.MutableRemoteInt? = null,
  digitCount: ee.schimke.composeai.rccreation.compose.state.MutableRemoteInt? = null,
  enteredDigits: Int = 0,
  codeLength: Int? = null,
  keyDp: Int = KeyDp,
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
          KeypadKey(
            entityId,
            it,
            it,
            accent,
            theme,
            pin,
            digitCount,
            enteredDigits,
            codeLength,
            keyDp,
          )
        }
      }
    }
    RemoteRow(horizontalArrangement = RemoteArrangement.spacedBy(8.rdp)) {
      RemoteBox(modifier = RemoteModifier.size(keyDp.rdp))
      KeypadKey(
        entityId,
        "0",
        "0",
        accent,
        theme,
        pin,
        digitCount,
        enteredDigits,
        codeLength,
        keyDp,
      )
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
        keyDp,
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
  pin: ee.schimke.composeai.rccreation.compose.state.MutableRemoteInt? = null,
  digitCount: ee.schimke.composeai.rccreation.compose.state.MutableRemoteInt? = null,
  enteredDigits: Int = 0,
  codeLength: Int? = null,
  keyDp: Int = KeyDp,
) {
  val localAction =
    if (entityId != null && pin != null && digitCount != null && codeLength != null) {
      when {
        key == "backspace" && enteredDigits > 0 ->
          combinedAction(
            valueChangeAction(pin, pin / 10),
            valueChangeAction(digitCount, (enteredDigits - 1).ri),
          )
        key == "backspace" ->
          combinedAction(valueChangeAction(pin, 0.ri), valueChangeAction(digitCount, 0.ri))
        key.length == 1 && key[0].isDigit() -> {
          val completedPin = pin * 10 + key.toInt()
          if (enteredDigits == codeLength - 1) {
            val submit =
              widgetAlarmPinAction(
                entityId,
                completedPin.toPaddedRemoteString(codeLength),
              )
            submit?.let {
              combinedAction(it, valueChangeAction(pin, 0.ri), valueChangeAction(digitCount, 0.ri))
            }
          } else {
            combinedAction(
              valueChangeAction(pin, completedPin),
              valueChangeAction(digitCount, (enteredDigits + 1).ri),
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
        .size(keyDp.rdp)
        .clip(haControlShape(6))
        .border(1.rdp, haInk(accent), haControlShape(6))
        .then(haKeyChrome(haInk(accent))),
    contentAlignment = RemoteAlignment.Center,
  ) {
    RemoteText(
      text = label.rs,
      color = if (haLayouts().keyFaces) theme.primaryText else haInk(accent),
      fontSize = HaType.Title,
      fontWeight = RemoteFontWeight.Medium,
      style = haTextStyle(),
      maxLines = 1,
    )
  }
}

/** A keypad key: a 48 dp touch target. */
private const val KeyDp = 48

/** The narrow panel's key, a little smaller so the keypad fits under the buttons in a `3×4`. */
private const val NarrowKeyDp = 44
