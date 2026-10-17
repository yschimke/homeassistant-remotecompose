@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import android.graphics.Paint as AndroidPaint
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Print
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
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.RemoteTextUnit
import androidx.compose.remote.creation.compose.state.asRemotePaint
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.remote.material3.RemoteIcon

/**
 * `custom:ha-bambulab-print_status-card` — printer hero tile. While printing: a progress ring with
 * the percentage and stage, and the job's lines (time left, layer, temperatures). Otherwise: the
 * printer, what it is doing (idle, finished, failed, offline) and its temperatures — never an empty
 * ring reading `0 %` over `Layer 0 / 0`.
 *
 * Printing, [wide] (a dashboard column, a wide launcher cell): the job's lines take the right half.
 *
 * ```
 *   ┌─────────────────────────────────────────────┐
 *   │  P2S                                        │
 *   │   ╭──╮   34 %              1h 22m left      │
 *   │   │  │   Inspecting first  Layer 12 / 240   │
 *   │   ╰──╯                     Nozzle 220 °C    │
 *   │                            Bed 60 °C        │
 *   └─────────────────────────────────────────────┘
 * ```
 *
 * Printing, narrow: the same content as a column. Not printing, wide (narrow moves the temperatures
 * under the state):
 * ```
 *   ┌─────────────────────────────────────────────┐
 *   │  (⎙)  A1 mini                 Nozzle 23 °C  │
 *   │       Idle                    Bed 22 °C     │
 *   └─────────────────────────────────────────────┘
 * ```
 *
 * Which layout draws is structure, decided at capture from [HaBambuPrintStatusData.phase] (the card
 * re-encodes when it changes; a design template keys on it). Every text is a named binding, and the
 * ring's sweep is a named float in a design template.
 */
@Composable
@RemoteComposable
fun RemoteHaBambuPrintStatus(
  data: HaBambuPrintStatusData,
  modifier: RemoteModifier = RemoteModifier,
  wide: Boolean = true,
) {
  val theme = haTheme()
  RemoteBox(
    modifier =
      modifier
        .fillMaxWidth()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 14.rdp, vertical = 12.rdp)
  ) {
    if (data.phase == HaBambuPrintPhase.Printing) {
      PrintingLayout(data, wide, theme)
    } else {
      StandbyLayout(data, wide, theme)
    }
  }
}

@Composable
private fun PrintingLayout(data: HaBambuPrintStatusData, wide: Boolean, theme: RemoteHaTheme) {
  val twoColumns =
    wide &&
      (data.remainingLine != null ||
        data.layerLine != null ||
        data.nozzleLine != null ||
        data.bedLine != null)
  RemoteColumn(
    modifier = RemoteModifier.fillMaxWidth(),
    verticalArrangement = RemoteArrangement.spacedBy(8.rdp),
  ) {
    PrinterName(data, theme, HaType.Caption)
    RemoteRow(
      modifier = RemoteModifier.fillMaxWidth(),
      verticalAlignment = RemoteAlignment.CenterVertically,
    ) {
      RemoteRow(
        modifier = if (twoColumns) RemoteModifier.weight(1f) else RemoteModifier,
        verticalAlignment = RemoteAlignment.CenterVertically,
      ) {
        ProgressRing(data.progressEntityId, data.progressFraction, data.accent, theme)
        RemoteColumn(
          modifier = RemoteModifier.padding(start = 14.rdp),
          verticalArrangement = RemoteArrangement.spacedBy(2.rdp),
        ) {
          RemoteText(
            text =
              LiveValues.attribute(data.progressEntityId, "progress_label", data.progressLabel),
            color = theme.primaryText,
            fontSize = HaType.Value,
            fontWeight = haValueWeight(),
            style = haTextStyle(),
            maxLines = 1,
          )
          StageText(data, HaType.Body, theme.secondaryText)
        }
      }
      if (twoColumns) {
        // Earn the canvas: the job's lines take the right half rather than stacking under the
        // ring and leaving it empty.
        RemoteColumn(
          modifier = RemoteModifier.weight(1f).padding(start = 12.rdp),
          verticalArrangement = RemoteArrangement.spacedBy(2.rdp),
        ) {
          RemainingText(data, HaType.Body, theme.primaryText)
          LayerText(data, theme)
          NozzleText(data, theme)
          BedText(data, theme)
        }
      }
    }
    if (!twoColumns) {
      RemainingText(data, HaType.Label, theme.secondaryText)
      LayerText(data, theme)
      TemperatureRow(data, theme)
    }
  }
}

/** Idle, finished, failed or offline: the printer, its state, and its temperatures. */
@Composable
private fun StandbyLayout(data: HaBambuPrintStatusData, wide: Boolean, theme: RemoteHaTheme) {
  val hasTemps = data.nozzleLine != null || data.bedLine != null
  RemoteColumn(
    modifier = RemoteModifier.fillMaxWidth(),
    verticalArrangement = RemoteArrangement.spacedBy(10.rdp),
  ) {
    RemoteRow(
      modifier = RemoteModifier.fillMaxWidth(),
      verticalAlignment = RemoteAlignment.CenterVertically,
    ) {
      PhaseGlyph(data, theme)
      RemoteColumn(
        modifier = RemoteModifier.weight(1f).padding(start = 12.rdp),
        verticalArrangement = RemoteArrangement.spacedBy(2.rdp),
      ) {
        PrinterName(data, theme, HaType.Body)
        StageText(data, HaType.ValueSmall, theme.primaryText, haValueWeight())
      }
      if (wide && hasTemps) {
        RemoteColumn(
          modifier = RemoteModifier.padding(start = 12.rdp),
          verticalArrangement = RemoteArrangement.spacedBy(2.rdp),
          horizontalAlignment = RemoteAlignment.End,
        ) {
          NozzleText(data, theme)
          BedText(data, theme)
        }
      }
    }
    if (!wide) TemperatureRow(data, theme)
  }
}

@Composable
private fun PrinterName(data: HaBambuPrintStatusData, theme: RemoteHaTheme, size: RemoteTextUnit) {
  RemoteText(
    text = cardText("printer", data.printerName),
    color = theme.secondaryText,
    fontSize = size,
    style = haTextStyle(),
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

@Composable
private fun StageText(
  data: HaBambuPrintStatusData,
  size: RemoteTextUnit,
  color: RemoteColor,
  weight: FontWeight? = null,
) {
  RemoteText(
    text = LiveValues.attribute(data.stageEntityId, "stage_label", data.stage),
    color = color,
    fontSize = size,
    fontWeight = weight,
    style = haTextStyle(),
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

@Composable
private fun RemainingText(data: HaBambuPrintStatusData, size: RemoteTextUnit, color: RemoteColor) {
  val line = data.remainingLine ?: return
  RemoteText(
    text = LiveValues.attribute(data.remainingEntityId, "remaining_line", line),
    color = color,
    fontSize = size,
    style = haTextStyle(),
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

@Composable
private fun LayerText(data: HaBambuPrintStatusData, theme: RemoteHaTheme) {
  val line = data.layerLine ?: return
  LabelLine(LiveValues.attribute(data.layerEntityId, "layer_line", line), theme)
}

@Composable
private fun NozzleText(data: HaBambuPrintStatusData, theme: RemoteHaTheme) {
  val line = data.nozzleLine ?: return
  LabelLine(LiveValues.attribute(data.nozzleEntityId, "nozzle_line", line), theme)
}

@Composable
private fun BedText(data: HaBambuPrintStatusData, theme: RemoteHaTheme) {
  val line = data.bedLine ?: return
  LabelLine(LiveValues.attribute(data.bedEntityId, "bed_line", line), theme)
}

@Composable
private fun LabelLine(text: RemoteString, theme: RemoteHaTheme) {
  RemoteText(
    text = text,
    color = theme.secondaryText,
    fontSize = HaType.Label,
    style = haTextStyle(),
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

@Composable
private fun TemperatureRow(data: HaBambuPrintStatusData, theme: RemoteHaTheme) {
  if (data.nozzleLine == null && data.bedLine == null) return
  RemoteRow(
    modifier = RemoteModifier.fillMaxWidth(),
    horizontalArrangement = RemoteArrangement.spacedBy(16.rdp),
  ) {
    NozzleText(data, theme)
    BedText(data, theme)
  }
}

/**
 * The not-printing identity: a finished print keeps its full ring, with a check in it; an idle,
 * failed or offline printer gets a tinted chip with its state's icon.
 */
@Composable
private fun PhaseGlyph(data: HaBambuPrintStatusData, theme: RemoteHaTheme) {
  if (data.phase == HaBambuPrintPhase.Finished) {
    RemoteBox(modifier = RemoteModifier.size(44.rdp), contentAlignment = RemoteAlignment.Center) {
      ProgressRing(null, 1f, data.accent, theme, sizeDp = 44, strokePx = 6f)
      RemoteIcon(
        imageVector = Icons.Filled.Check.toRemoteImageVector(),
        contentDescription = data.stage.rs,
        modifier = RemoteModifier.size(22.rdp),
        tint = data.accent.rc,
      )
    }
    return
  }
  val icon =
    when (data.phase) {
      HaBambuPrintPhase.Offline -> Icons.Filled.CloudOff
      HaBambuPrintPhase.Failed -> Icons.Filled.ErrorOutline
      else -> Icons.Filled.Print
    }
  val tint: RemoteColor =
    when (data.phase) {
      HaBambuPrintPhase.Offline -> theme.secondaryText
      HaBambuPrintPhase.Failed -> FailedRed.rc
      else -> data.accent.rc
    }
  RemoteBox(
    modifier =
      RemoteModifier.size(44.rdp)
        .clip(haControlShape(22, round = true))
        .background(tint.copy(alpha = tint.alpha * 0.18f.rf)),
    contentAlignment = RemoteAlignment.Center,
  ) {
    RemoteIcon(
      imageVector = icon.toRemoteImageVector(),
      contentDescription = data.stage.rs,
      modifier = RemoteModifier.size(24.rdp),
      tint = tint,
    )
  }
}

private val FailedRed = Color(0xFFDB4437)

@Composable
private fun ProgressRing(
  entityId: String?,
  fraction: Float,
  accent: Color,
  theme: RemoteHaTheme,
  sizeDp: Int = 64,
  strokePx: Float = 8f,
) {
  val sweep = (fraction.coerceIn(0f, 1f) * 360f)
  // `<entityId>.progress_fraction`: constant in a live capture, bound in a design template. Whether
  // the arc exists at all stays structure (a template keys on it).
  val sweepRf = entityFloat(entityId, "progress_fraction", fraction.coerceIn(0f, 1f)) * 360f.rf
  RemoteBox(modifier = RemoteModifier.size(sizeDp.rdp)) {
    RemoteCanvas(modifier = RemoteModifier.size(sizeDp.rdp)) {
      val w = width
      val h = height
      val stroke = strokePx.rf
      val pad = stroke / 2f.rf + 1f.rf
      val topLeft = RemoteOffset(pad, pad)
      val arcSize = RemoteSize(w - pad * 2f.rf, h - pad * 2f.rf)

      val track =
        AndroidPaint()
          .apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeWidth = strokePx
            strokeCap = AndroidPaint.Cap.ROUND
          }
          .asRemotePaint()
          .apply { color = theme.divider }
      drawArc(track, 0f.rf, 360f.rf, false, topLeft, arcSize)

      if (sweep > 0.5f) {
        val progress =
          AndroidPaint()
            .apply {
              isAntiAlias = true
              style = AndroidPaint.Style.STROKE
              strokeWidth = strokePx
              strokeCap = AndroidPaint.Cap.ROUND
              color = accent.toArgb()
            }
            .asRemotePaint()
        // Start at 12 o'clock (Android draws 0° at 3 o'clock).
        drawArc(progress, (-90f).rf, sweepRf, false, topLeft, arcSize)
      }
    }
  }
}

private fun Color.toArgb(): Int =
  android.graphics.Color.argb(
    (alpha * 255f).toInt(),
    (red * 255f).toInt(),
    (green * 255f).toInt(),
    (blue * 255f).toInt(),
  )
