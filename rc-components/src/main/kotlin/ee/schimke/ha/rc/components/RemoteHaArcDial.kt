@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import android.graphics.Paint as AndroidPaint
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.remote.creation.compose.capture.toRemoteImageVector
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteCanvas
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteSize
import androidx.compose.remote.creation.compose.layout.RemoteStateLayout
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.border
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.drawWithContent
import androidx.compose.remote.creation.compose.modifier.fillMaxHeight
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.modifier.width
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.asRemotePaint
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.ri
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.remote.material3.RemoteIcon

/**
 * 270° arc dial used by thermostat / humidifier / light cards.
 *
 * ```
 *      ╱──────────╲
 *     │  Heating   │
 *     │  27.5 °C   │   ← centre label (or icon for light)
 *     │  ↑ 25 °C   │   ← supporting label (target / brightness)
 *      ╲──────────╱
 *         (-)(+)
 * ```
 *
 * The arc starts at 135° (top-left), sweeps clockwise 270° to 45° (top-right). The fill represents
 * [HaArcDialData.valueFraction]; when [HaArcDialData.targetFraction] is set, a small marker dot is
 * drawn on the arc at that fraction (rendered as a near-zero-sweep arc with a round stroke cap,
 * which avoids capture-time trig on RemoteFloat).
 *
 * This is the full vertical card — used at the app preferred width and for larger launcher widget
 * sizes. Smaller surfaces fall through to [RemoteHaArcDialWide] (arc-left, text-right) which shows
 * the same mode chip, value, and target label in a horizontal layout.
 */
@Composable
@RemoteComposable
fun RemoteHaArcDial(data: HaArcDialData, modifier: RemoteModifier = RemoteModifier) {
  if (isIkeaLight(data)) {
    IkeaLightRemote(data, modifier)
    return
  }
  val theme = haTheme()
  val click =
    data.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier

  RemoteBox(
    modifier =
      modifier
        .then(click)
        .fillMaxWidth()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 14.rdp, vertical = 12.rdp)
  ) {
    RemoteColumn(
      modifier = RemoteModifier.fillMaxWidth(),
      horizontalAlignment = RemoteAlignment.CenterHorizontally,
      verticalArrangement = RemoteArrangement.spacedBy(4.rdp),
    ) {
      RemoteText(
        text = entityName(data.entityId, data.name),
        color = theme.primaryText,
        fontSize = HaType.BodyLarge,
        fontWeight = FontWeight.Medium,
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (
        visualFamily().isCommunity &&
          visualFamily() in
            setOf(
              HaVisualFamily.Bubble,
              HaVisualFamily.Cupertino,
              HaVisualFamily.Lcars,
              HaVisualFamily.Aeon,
            )
      ) {
        RemoteRow(
          RemoteModifier.fillMaxWidth(),
          verticalAlignment = RemoteAlignment.CenterVertically,
        ) {
          RemoteBox(RemoteModifier.weight(1f)) { DialBody(data, theme) }
          if (data.showSteppers) {
            RemoteColumn(RemoteModifier.width(120.rdp).padding(start = 6.rdp)) {
              Steppers(dialAccent(data), data.incrementAction, data.decrementAction, theme)
            }
          }
        }
      } else {
        DialBody(data, theme)
        if (data.showSteppers) {
          Steppers(dialAccent(data), data.incrementAction, data.decrementAction, theme)
        }
      }
    }
  }
}

/**
 * Horizontal arc-left / text-right variant — used for shorter widget cells (Wear L, compact
 * launcher tiles, 1×1 launcher chips) where the full vertical card doesn't fit. Arc canvas is sized
 * off the row height (square); the text column shows mode chip, value, and target stacked so the
 * full readout stays visible even at chip sizes. Steppers are omitted — cells that hit this tier
 * don't have vertical room for them.
 *
 * [compact] is the Wear small slot's form (a ~44dp content box, too short for three lines): the
 * mode over the value, set a step smaller, with tighter padding. The target line drops — the arc
 * still marks the target, so the reading keeps its intent (design principle 3: reflow, then drop
 * the least important part). [dialDp] is the dial's box: a Wear Large slot grows it to fill its
 * height.
 *
 * [showName] puts [HaArcDialData.name] at the top of the text column: without it two dials on one
 * watch read the same (card-information-hierarchy.md). The name is ranked above the mode chip, so
 * by default it takes the chip's line; [showMode] keeps the chip under the name where the column
 * has room for it and the chip says something the value and accent do not (a thermostat's `Idle`
 * while in heat mode, a humidifier's `Off`).
 */
@Composable
@RemoteComposable
fun RemoteHaArcDialWide(
  data: HaArcDialData,
  modifier: RemoteModifier = RemoteModifier,
  compact: Boolean = false,
  dialDp: Int = 56,
  showName: Boolean = false,
  showMode: Boolean = !showName,
) {
  val theme = haTheme()
  val click =
    data.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
  // NB: on tall cells (e.g. 3×3) this row sits high with dead space
  // below — it can't self-centre. A `RemoteRow`/`RemoteColumn` doesn't
  // propagate fill-height through the Fixed-mode size breakpoint, and a
  // `fillMaxSize` `RemoteBox` wrapper with `contentAlignment = Center`
  // is a no-op for a `fillMaxWidth` child in this alpha (the same
  // RemoteStateLayout/fill limitation tracked as known-gap #6 in
  // docs/architecture/adaptive-card-layouts.md). Left as-is until the
  // upstream fix lands.
  RemoteRow(
    modifier =
      modifier
        .then(click)
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 10.rdp, vertical = if (compact) 2.rdp else 8.rdp),
    verticalAlignment = RemoteAlignment.CenterVertically,
    horizontalArrangement = RemoteArrangement.spacedBy(10.rdp),
  ) {
    RemoteBox(
      modifier =
        if (isConsoleThermostat(data) || isIkeaLight(data))
          RemoteModifier.size((if (compact) 32 else dialDp).rdp)
        else RemoteModifier.fillMaxHeight().size(dialDp.rdp),
      contentAlignment = RemoteAlignment.Center,
    ) {
      if (isIkeaLight(data)) {
        IkeaControlFace(
          dialAccent(data),
          LiveValues.isOn(data.entityId, data.showSteppers)?.select(1f.rf, 0f.rf) ?: 0f.rf,
          RemoteModifier.fillMaxSize(),
        )
      } else {
        ArcCanvas(
          data = data,
          theme = theme,
          trackStrokePx = 6f,
          fillStrokePx = 6f,
          markerStrokePx = 8f,
          paddingPx = 3f,
        )
      }
    }
    RemoteColumn(
      modifier = RemoteModifier.weight(1f),
      verticalArrangement = RemoteArrangement.Center,
      horizontalAlignment = RemoteAlignment.Start,
    ) {
      if (showName)
        RemoteText(
          text = entityName(data.entityId, data.name),
          color = theme.primaryText,
          fontSize = if (compact) HaType.Label else HaType.Body,
          fontWeight = FontWeight.Medium,
          style = haTextStyle(),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      if (showMode) ModeChip(data, dialAccent(data))
      RemoteText(
        text = centerText(data),
        color = theme.primaryText,
        fontSize = if (compact) HaType.Title else HaType.ValueSmall,
        fontWeight = haValueWeight(),
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (!compact)
        supportingText(data)?.let { supporting ->
          RemoteText(
            text = supporting,
            color = dialAccent(data),
            fontSize = HaType.Label,
            style = haTextStyle(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
    }
  }
}

/**
 * The dial without its controls, grown to fill a launcher widget too small for the full card's
 * steppers (a `2×2` or `3×3` cell): the name over the dial, the dial taking every spare dp of the
 * cell (it is drawn from the live canvas, so it scales inside the tier with no breakpoint), and the
 * centre readout inside it as on the full card.
 *
 * The parts follow card-information-hierarchy.md: the value and the dial never drop; the name stays
 * unless [showName] is off (a dial inside a multi-entity container); the controls are the first
 * thing given up, and a tap on the card still acts. [compactCenter] is the short form for a dial
 * under about 140 dp: a smaller icon, and the mode chip and target give way before the value would
 * have to shrink or ellipsise.
 *
 * Families with their own body (the console thermostat, Cupertino's bar, the IKEA light) keep the
 * [RemoteHaArcDialWide] row here: their bodies are fixed-size drawings, not a canvas that scales.
 */
@Composable
@RemoteComposable
fun RemoteHaArcDialFill(
  data: HaArcDialData,
  modifier: RemoteModifier = RemoteModifier,
  showName: Boolean = true,
  compactCenter: Boolean = false,
) {
  if (
    isIkeaLight(data) || isConsoleThermostat(data) || visualFamily() == HaVisualFamily.Cupertino
  ) {
    RemoteHaArcDialWide(data, modifier, showName = showName)
    return
  }
  val theme = haTheme()
  val click =
    data.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
  RemoteColumn(
    modifier =
      modifier
        .then(click)
        .fillMaxSize()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 12.rdp, vertical = 10.rdp),
    horizontalAlignment = RemoteAlignment.CenterHorizontally,
    verticalArrangement = RemoteArrangement.spacedBy(2.rdp),
  ) {
    if (showName)
      RemoteText(
        text = entityName(data.entityId, data.name),
        color = theme.primaryText,
        fontSize = HaType.BodyLarge,
        fontWeight = FontWeight.Medium,
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    RemoteBox(
      modifier = RemoteModifier.fillMaxWidth().weight(1f),
      contentAlignment = RemoteAlignment.Center,
    ) {
      ArcCanvas(
        data = data,
        theme = theme,
        trackStrokePx = 10f,
        fillStrokePx = 10f,
        markerStrokePx = 12f,
        paddingPx = 4f,
      )
      RemoteColumn(
        horizontalAlignment = RemoteAlignment.CenterHorizontally,
        verticalArrangement = RemoteArrangement.spacedBy(if (compactCenter) 0.rdp else 2.rdp),
      ) {
        if (data.centerIcon != null) {
          RemoteIcon(
            imageVector = data.centerIcon.toRemoteImageVector(),
            contentDescription = entityName(data.entityId, data.name),
            modifier = RemoteModifier.size((if (compactCenter) 28 else 40).rdp),
            tint = dialAccent(data),
          )
        }
        // A light's chip repeats its value (`On` beside `78%`): in the short form it is the
        // first text to go. A thermostat's mode says what its accent cannot, so it stays.
        if (!compactCenter || data.centerIcon == null) ModeChip(data, dialAccent(data))
        RemoteText(
          text = centerText(data),
          color = theme.primaryText,
          fontSize = if (compactCenter) HaType.Value else HaType.ValueLarge,
          fontWeight = haValueWeight(),
          style = haTextStyle(),
          maxLines = 1,
        )
        if (!compactCenter)
          supportingText(data)?.let { supporting ->
            RemoteText(
              text = supporting,
              color = dialAccent(data),
              fontSize = HaType.Label,
              style = haTextStyle(),
              maxLines = 1,
            )
          }
      }
    }
  }
}

@Composable
private fun DialBody(data: HaArcDialData, theme: RemoteHaTheme) {
  if (isConsoleThermostat(data)) {
    ConsoleThermostatBody(data, theme)
    return
  }
  if (visualFamily() == HaVisualFamily.Cupertino) {
    val fraction =
      animateRemoteFloat(
        data.live?.valueFraction
          ?: LiveValues.namedFloat(
            data.entityId,
            "value_fraction",
            data.valueFraction.coerceIn(0f, 1f),
          ),
        DialAnimationSeconds,
      )
    val rawTarget =
      if (data.live != null) data.live.targetFraction
      else
        data.targetFraction?.let {
          LiveValues.namedFloat(data.entityId, "target_fraction", it.coerceIn(0f, 1f))
        }
    val target = rawTarget?.let { animateRemoteFloat(it, DialAnimationSeconds) }
    val targetInk =
      data.live?.targetVisible?.select(theme.primaryText, Color.Transparent.rc) ?: theme.primaryText
    RemoteColumn(
      RemoteModifier.fillMaxWidth(),
      horizontalAlignment = RemoteAlignment.CenterHorizontally,
    ) {
      ModeChip(data, dialAccent(data))
      RemoteBox(
        RemoteModifier.size(78.rdp, 120.rdp)
          .clip(haControlShape(28))
          .background(theme.placeholderBackground)
          .padding(8.rdp)
      ) {
        RemoteHaBarMeter(fraction, dialAccent(data), RemoteModifier.fillMaxSize(), vertical = true)
        if (target != null)
          RemoteCanvas(RemoteModifier.fillMaxSize()) {
            fillRound(targetInk, 0f.rf, (height - 2f.rf) * (1f.rf - target), width, 2f.rf, 1f.rf)
          }
        if (data.centerIcon != null)
          RemoteBox(RemoteModifier.fillMaxSize(), contentAlignment = RemoteAlignment.BottomCenter) {
            RemoteIcon(
              data.centerIcon.toRemoteImageVector(),
              contentDescription = null,
              modifier = RemoteModifier.size(30.rdp),
              tint = theme.primaryText,
            )
          }
      }
      RemoteText(
        centerText(data),
        color = theme.primaryText,
        fontSize = HaType.ValueLarge,
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      supportingText(data)?.let {
        RemoteText(
          it,
          color = theme.secondaryText,
          fontSize = HaType.Label,
          style = haTextStyle(),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    return
  }
  RemoteBox(modifier = RemoteModifier.size(180.rdp), contentAlignment = RemoteAlignment.Center) {
    ArcCanvas(
      data = data,
      theme = theme,
      trackStrokePx = 12f,
      fillStrokePx = 12f,
      markerStrokePx = 14f,
      paddingPx = 4f,
    )
    RemoteColumn(
      horizontalAlignment = RemoteAlignment.CenterHorizontally,
      verticalArrangement = RemoteArrangement.spacedBy(2.rdp),
    ) {
      if (data.centerIcon != null) {
        RemoteIcon(
          imageVector = data.centerIcon.toRemoteImageVector(),
          contentDescription = entityName(data.entityId, data.name),
          modifier = RemoteModifier.size(40.rdp),
          tint = dialAccent(data),
        )
      }
      ModeChip(data, dialAccent(data))
      RemoteText(
        text = centerText(data),
        color = theme.primaryText,
        fontSize = HaType.ValueLarge,
        fontWeight = haValueWeight(),
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      supportingText(data)?.let { supporting ->
        RemoteText(
          text = supporting,
          color = dialAccent(data),
          fontSize = HaType.Label,
          style = haTextStyle(),
          maxLines = 1,
        )
      }
    }
  }
}

@Composable
private fun isIkeaLight(data: HaArcDialData): Boolean =
  visualFamily() == HaVisualFamily.Ikea && data.centerLabelAttribute == "brightness_pct"

/** A thermostat is a room display beside a set-point control, never a display on a moving cap. */
@Composable
private fun isConsoleThermostat(data: HaArcDialData): Boolean =
  visualFamily() == HaVisualFamily.Console &&
    data.centerLabelAttribute == "current_temperature_label"

@Composable
private fun ConsoleThermostatBody(data: HaArcDialData, theme: RemoteHaTheme) {
  RemoteColumn(
    horizontalAlignment = RemoteAlignment.CenterHorizontally,
    verticalArrangement = RemoteArrangement.spacedBy(8.rdp),
  ) {
    RemoteColumn(
      modifier =
        RemoteModifier.size(180.rdp, 62.rdp).drawWithContent {
          // A fixed monochrome instrument window, recessed behind a metal lip.
          fillRound(ConsoleSlot.rc, 0f.rf, 0f.rf, width, height, 3f.rf)
          fillRound(ConsoleSlotLip.rc, 1f.rf, 1f.rf, width - 2f.rf, height - 2f.rf, 2f.rf)
          fillRect(Color(0xFFADB9A1).rc, 4f.rf, 4f.rf, width - 8f.rf, height - 8f.rf)
          fillRect(Color.Black.copy(alpha = 0.12f).rc, 4f.rf, 4f.rf, width - 8f.rf, 3f.rf)
          drawContent()
        },
      horizontalAlignment = RemoteAlignment.CenterHorizontally,
      verticalArrangement = RemoteArrangement.Center,
    ) {
      RemoteText(
        "TEMPERATURE".rs,
        color = Color(0xFF354033).rc,
        fontSize = HaType.Micro,
        style = haTextStyle(),
      )
      RemoteText(
        text = centerText(data),
        color = Color(0xFF202B22).rc,
        fontSize = HaType.ValueLarge,
        fontWeight = haValueWeight(),
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    RemoteRow(
      verticalAlignment = RemoteAlignment.CenterVertically,
      horizontalArrangement = RemoteArrangement.spacedBy(12.rdp),
    ) {
      RemoteBox(RemoteModifier.size(116.rdp)) { ArcCanvas(data, theme, 6f, 6f, 8f, 3f) }
      RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(4.rdp)) {
        RemoteText(
          "SET POINT".rs,
          color = theme.secondaryText,
          fontSize = HaType.Micro,
          style = haTextStyle(),
        )
        RemoteText(
          supportingText(data) ?: "—".rs,
          color = theme.primaryText,
          fontSize = HaType.ValueSmall,
          style = haTextStyle(),
          maxLines = 1,
        )
        ModeChip(data, dialAccent(data))
      }
    }
  }
}

@Composable
@RemoteComposable
private fun ModeChip(data: HaArcDialData, accent: RemoteColor) {
  data.live?.modeLabel?.let {
    ChipText(it, accent)
    return
  }
  val chip = data.modeChip ?: return
  when (chip) {
    is HaModeChip.Static ->
      ChipText(LiveValues.attribute(chip.entityId, chip.attribute, chip.initial), accent)
    is HaModeChip.Toggle -> {
      // NB: a `RemoteStateLayout` here collapses to its captured
      // branch when nested inside a Fixed-mode size breakpoint
      // (#224), so callers that render through `RenderArcDial`
      // should prefer [HaModeChip.Static]. This live-boolean form
      // is correct only for the un-nested (Wrap) full card.
      val isOn = LiveValues.isOn(chip.entityId, chip.initiallyOn)
      if (isOn != null) {
        RemoteStateLayout(isOn) { on ->
          ChipText((if (on) chip.onLabel else chip.offLabel).rs, accent)
        }
      } else {
        ChipText((if (chip.initiallyOn) chip.onLabel else chip.offLabel).rs, accent)
      }
    }
  }
}

@Composable
@RemoteComposable
private fun ChipText(text: RemoteString, accent: RemoteColor) {
  RemoteText(
    text = text,
    color = accent,
    fontSize = HaType.Label,
    fontWeight = FontWeight.Medium,
    style = haTextStyle(),
    maxLines = 1,
    // A long mode ("Humidifying") in a narrow text column ellipsises rather than being cut.
    overflow = TextOverflow.Ellipsis,
  )
}

/**
 * Common arc rendering. Sizes itself off the box it sits inside via `fillMaxSize`, so callers
 * control the arc footprint by sizing the surrounding container (e.g. 180 dp box in the vertical
 * card vs. 56 dp box in the wide variant). Stroke widths are passed as pixel-space floats — small
 * variants want thinner strokes so the dial doesn't read as a clumsy donut at chip sizes.
 */
@Composable
private fun ArcCanvas(
  data: HaArcDialData,
  theme: RemoteHaTheme,
  trackStrokePx: Float,
  fillStrokePx: Float,
  markerStrokePx: Float,
  paddingPx: Float,
) {
  val v = data.valueFraction.coerceIn(0f, 1f)
  val rawValueFraction =
    data.live?.valueFraction ?: LiveValues.namedFloat(data.entityId, "value_fraction", v)
  val animatedValueFraction = animateRemoteFloat(rawValueFraction, DialAnimationSeconds)
  val animatedSweep: RemoteFloat = animatedValueFraction * 270f.rf

  val target = data.targetFraction
  val rawTargetFraction =
    if (data.live != null) {
      data.live.targetFraction
    } else if (target != null) {
      LiveValues.namedFloat(data.entityId, "target_fraction", target.coerceIn(0f, 1f))
    } else null
  val animatedTargetFraction =
    if (rawTargetFraction != null) animateRemoteFloat(rawTargetFraction, DialAnimationSeconds)
    else null
  val animatedMarkerAngle = animatedTargetFraction?.let { 135f.rf + it * 270f.rf }
  val accent = dialAccent(data)
  val family = visualFamily()
  val look = meterLook(accent, theme.divider)
  val mechanical = isConsoleThermostat(data)

  RemoteCanvas(modifier = RemoteModifier.fillMaxSize()) {
    val w = width
    val h = height
    val pad = (trackStrokePx.coerceAtLeast(fillStrokePx) / 2f) + paddingPx
    val padTwice = (pad * 2f).rf
    val byHeight = h - padTwice
    val byWidth = w - padTwice
    val side = byHeight.min(byWidth)
    val left = (w - side) / 2f.rf
    val top = (h - side) / 2f.rf
    val topLeft = RemoteOffset(left, top)
    val arcSize = RemoteSize(side, side)
    // A skeuomorphic theme turns the dial into a rotary control: a knob inside a lamp ring.
    if (family == HaVisualFamily.Console) {
      consoleDial(
        accent,
        animatedValueFraction,
        animatedTargetFraction,
        left,
        top,
        side,
        data.live?.targetVisible,
        mechanical = mechanical,
      )
      return@RemoteCanvas
    }
    // Every other family but Classic draws its own instrument (Dials.kt).
    if (family != HaVisualFamily.Classic) {
      familyDial(
        family,
        look,
        animatedValueFraction,
        animatedTargetFraction,
        data.live?.targetVisible,
        DialBox(left, top, side),
        trackStrokePx,
      )
      return@RemoteCanvas
    }

    val track =
      AndroidPaint()
        .apply {
          isAntiAlias = true
          style = AndroidPaint.Style.STROKE
          strokeWidth = trackStrokePx
          strokeCap = AndroidPaint.Cap.ROUND
        }
        .asRemotePaint()
        .apply { color = theme.divider }
    drawArc(track, 135f.rf, 270f.rf, false, topLeft, arcSize)

    val fill =
      AndroidPaint()
        .apply {
          isAntiAlias = true
          style = AndroidPaint.Style.STROKE
          strokeWidth = fillStrokePx
          strokeCap = AndroidPaint.Cap.ROUND
        }
        .asRemotePaint()
        .apply { color = accent }
    drawArc(fill, 135f.rf, animatedSweep, false, topLeft, arcSize)

    if (animatedMarkerAngle != null) {
      val marker =
        AndroidPaint()
          .apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeWidth = markerStrokePx
            strokeCap = AndroidPaint.Cap.ROUND
          }
          .asRemotePaint()
          .apply {
            color =
              data.live?.targetVisible?.select(theme.primaryText, Color.Transparent.rc)
                ?: theme.primaryText
          }
      drawArc(marker, animatedMarkerAngle, 0.1f.rf, false, topLeft, arcSize)
    }
  }
}

private const val DialAnimationSeconds = 0.45f

/** The centre readout: the live expression, else `<entityId>.attributes.<centerLabelAttribute>`. */
private fun centerText(data: HaArcDialData): RemoteString =
  data.live?.centerLabel
    ?: LiveValues.attribute(data.entityId, data.centerLabelAttribute, data.centerLabel)

/** The supporting readout (the target), or null when the dial shows none. */
private fun supportingText(data: HaArcDialData): RemoteString? {
  data.live?.let {
    return it.supportingLabel
  }
  val label = data.supportingLabel ?: return null
  val attribute = data.supportingLabelAttribute ?: return null
  return LiveValues.attribute(data.entityId, attribute, label)
}

@Composable
private fun Steppers(
  accent: RemoteColor,
  incrementAction: HaAction,
  decrementAction: HaAction,
  theme: RemoteHaTheme,
) {
  RemoteRow(
    modifier = RemoteModifier.fillMaxWidth().padding(top = 6.rdp),
    horizontalArrangement = RemoteArrangement.spacedBy(24.rdp, RemoteAlignment.CenterHorizontally),
  ) {
    StepperButton(Icons.Filled.Remove, decrementAction, accent, theme)
    StepperButton(Icons.Filled.Add, incrementAction, accent, theme)
  }
}

@Composable
private fun StepperButton(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  action: HaAction,
  accent: RemoteColor,
  theme: RemoteHaTheme,
) {
  val click = action.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
  RemoteBox(
    modifier =
      RemoteModifier.then(click)
        .size(36.rdp)
        .clip(haControlShape(18, round = true))
        .border(1.rdp, theme.divider, haControlShape(18, round = true))
        .then(consoleKeyChrome(accent))
        .then(if (visualFamily() == HaVisualFamily.Ikea) ikeaKeyChrome(accent) else RemoteModifier),
    contentAlignment = RemoteAlignment.Center,
  ) {
    RemoteIcon(
      imageVector = icon.toRemoteImageVector(),
      contentDescription = "stepper".rs,
      modifier = RemoteModifier.size(20.rdp),
      tint = accent,
    )
  }
}

private fun Color.toArgb(): Int =
  android.graphics.Color.argb(
    (alpha * 255f).toInt(),
    (red * 255f).toInt(),
    (green * 255f).toInt(),
    (blue * 255f).toInt(),
  )

/**
 * The dial's accent. A live capture paints [HaArcDialData.accent]. A design template whose card
 * picks its accent from the entity's state ([HaArcDialData.accentChoices]) selects among the
 * choices in-document by `<entityId>.accent_index`, so the state never decides a colour at capture
 * time.
 */
@Composable
private fun dialAccent(data: HaArcDialData): RemoteColor {
  val choices = data.accentChoices
  if (choices.isNullOrEmpty() || LocalTemplateBindings.current == null) return haInk(data.accent)
  val index =
    entityInt(data.entityId, "accent_index", choices.indexOf(data.accent).coerceAtLeast(0))
  var color = choices.last().rc
  for (i in choices.indices.reversed().drop(1)) {
    color = index.isEqualTo(i.ri).select(choices[i].rc, color)
  }
  return haInk(color)
}
