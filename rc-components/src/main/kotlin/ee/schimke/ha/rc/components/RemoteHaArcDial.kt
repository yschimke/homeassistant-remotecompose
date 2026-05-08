@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import android.graphics.Paint as AndroidPaint
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
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
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.shapes.RemoteCircleShape
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.asRemotePaint
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
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
 * The arc starts at 135° (top-left), sweeps clockwise 270° to 45°
 * (top-right). The fill represents [HaArcDialData.valueFraction]; when
 * [HaArcDialData.targetFraction] is set, a small marker dot is drawn
 * on the arc at that fraction (rendered as a near-zero-sweep arc with
 * a round stroke cap, which avoids capture-time trig on RemoteFloat).
 */
@Composable
@RemoteComposable
fun RemoteHaArcDial(
    data: HaArcDialData,
    modifier: RemoteModifier = RemoteModifier,
) {
    val theme = haTheme()
    val click = data.tapAction.toRemoteAction()
        ?.let { RemoteModifier.clickable(it) } ?: RemoteModifier

    RemoteBox(
        modifier = modifier
            .then(click)
            .fillMaxWidth()
            .clip(RemoteRoundedCornerShape(12.rdp))
            .background(theme.cardBackground.rc)
            .border(1.rdp, theme.divider.rc, RemoteRoundedCornerShape(12.rdp))
            .padding(horizontal = 14.rdp, vertical = 12.rdp),
    ) {
        RemoteColumn(
            modifier = RemoteModifier.fillMaxWidth(),
            horizontalAlignment = RemoteAlignment.CenterHorizontally,
            verticalArrangement = RemoteArrangement.spacedBy(4.rdp),
        ) {
            RemoteText(
                text = data.name.rs,
                color = theme.primaryText.rc,
                fontSize = 14.rsp,
                fontWeight = FontWeight.Medium,
                style = RemoteTextStyle.Default,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            DialBody(data, theme)
            if (data.showSteppers) {
                Steppers(data.accent, data.incrementAction, data.decrementAction, theme)
            }
        }
    }
}

@Composable
private fun DialBody(data: HaArcDialData, theme: HaTheme) {
    RemoteBox(
        modifier = RemoteModifier.size(180.rdp),
        contentAlignment = RemoteAlignment.Center,
    ) {
        ArcCanvas(data, theme)
        RemoteColumn(
            horizontalAlignment = RemoteAlignment.CenterHorizontally,
            verticalArrangement = RemoteArrangement.spacedBy(2.rdp),
        ) {
            if (data.centerIcon != null) {
                RemoteIcon(
                    imageVector = data.centerIcon,
                    contentDescription = data.name.rs,
                    modifier = RemoteModifier.size(40.rdp),
                    tint = data.accent.rc,
                )
            }
            ModeChip(data.modeChip, data.accent)
            RemoteText(
                text =
                    LiveValues.attribute(data.entityId, data.centerLabelAttribute, data.centerLabel),
                color = theme.primaryText.rc,
                fontSize = 26.rsp,
                fontWeight = FontWeight.SemiBold,
                style = RemoteTextStyle.Default,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (data.supportingLabel != null && data.supportingLabelAttribute != null) {
                RemoteText(
                    text =
                        LiveValues.attribute(
                            data.entityId,
                            data.supportingLabelAttribute,
                            data.supportingLabel,
                        ),
                    color = data.accent.rc,
                    fontSize = 12.rsp,
                    style = RemoteTextStyle.Default,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
@RemoteComposable
private fun ModeChip(chip: HaModeChip?, accent: Color) {
    if (chip == null) return
    when (chip) {
        is HaModeChip.Static ->
            ChipText(LiveValues.attribute(chip.entityId, chip.attribute, chip.initial), accent)
        is HaModeChip.Toggle -> {
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
private fun ChipText(text: RemoteString, accent: Color) {
    RemoteText(
        text = text,
        color = accent.rc,
        fontSize = 12.rsp,
        fontWeight = FontWeight.Medium,
        style = RemoteTextStyle.Default,
        maxLines = 1,
    )
}

@Composable
private fun ArcCanvas(data: HaArcDialData, theme: HaTheme) {
    // Live host binding for the value & (optional) target fraction —
    // wrapped in `animateRemoteFloat` so any state push from the host
    // tweens the sweep/marker over [DialAnimationSeconds] instead of
    // snapping. Constant when entityId is null (preview).
    val v = data.valueFraction.coerceIn(0f, 1f)
    val rawValueFraction = LiveValues.namedFloat(data.entityId, "value_fraction", v)
    val animatedValueFraction = animateRemoteFloat(rawValueFraction, DialAnimationSeconds)
    val animatedSweep = animatedValueFraction * 270f.rf

    val target = data.targetFraction
    val rawTargetFraction = if (target != null) {
        LiveValues.namedFloat(data.entityId, "target_fraction", target.coerceIn(0f, 1f))
    } else null
    val animatedTargetFraction = rawTargetFraction
        ?.let { animateRemoteFloat(it, DialAnimationSeconds) }
    val animatedMarkerAngle = animatedTargetFraction?.let { 135f.rf + it * 270f.rf }

    RemoteCanvas(modifier = RemoteModifier.size(180.rdp)) {
        val w = width
        val h = height
        val stroke = 12f.rf
        val pad = stroke / 2f.rf + 4f.rf
        val topLeft = RemoteOffset(pad, pad)
        val arcSize = RemoteSize(w - pad * 2f.rf, h - pad * 2f.rf)

        val track = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeWidth = 12f
            strokeCap = AndroidPaint.Cap.ROUND
            color = theme.divider.toArgb()
        }.asRemotePaint()
        drawArc(track, 135f.rf, 270f.rf, false, topLeft, arcSize)

        val fill = AndroidPaint().apply {
            isAntiAlias = true
            style = AndroidPaint.Style.STROKE
            strokeWidth = 12f
            strokeCap = AndroidPaint.Cap.ROUND
            color = data.accent.toArgb()
        }.asRemotePaint()
        drawArc(fill, 135f.rf, animatedSweep, false, topLeft, arcSize)

        if (animatedMarkerAngle != null) {
            val marker = AndroidPaint().apply {
                isAntiAlias = true
                style = AndroidPaint.Style.STROKE
                strokeWidth = 14f
                strokeCap = AndroidPaint.Cap.ROUND
                color = theme.primaryText.toArgb()
            }.asRemotePaint()
            drawArc(marker, animatedMarkerAngle, 0.1f.rf, false, topLeft, arcSize)
        }
    }
}

private const val DialAnimationSeconds = 0.45f

@Composable
private fun Steppers(
    accent: Color,
    incrementAction: HaAction,
    decrementAction: HaAction,
    theme: HaTheme,
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
    accent: Color,
    theme: HaTheme,
) {
    val click = action.toRemoteAction()
        ?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
    RemoteBox(
        modifier = RemoteModifier.then(click)
            .size(36.rdp)
            .clip(RemoteCircleShape)
            .border(1.rdp, theme.divider.rc, RemoteCircleShape),
        contentAlignment = RemoteAlignment.Center,
    ) {
        RemoteIcon(
            imageVector = icon,
            contentDescription = "stepper".rs,
            modifier = RemoteModifier.size(20.rdp),
            tint = accent.rc,
        )
    }
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255f).toInt(),
    (red * 255f).toInt(),
    (green * 255f).toInt(),
    (blue * 255f).toInt(),
)
