@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.layout.RemoteSize
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.border
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.drawWithContent
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.shaders.RemoteBrush
import androidx.compose.remote.creation.compose.shaders.verticalGradient
import androidx.compose.remote.creation.compose.shapes.RemoteCircleShape
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.asRemoteDp
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ee.schimke.rc.m3.RcAmbient
import ee.schimke.rc.m3.RcM3SwitchColors
import ee.schimke.rc.m3.RemoteM3SwitchByProgress
import ee.schimke.rc.m3.rcAmbient

// ---------------------------------------------------------------------------------------------
// Controls as slots: the on / off switch and the key mount. A card asks for them by role —
// RemoteHaToggleSwitch, haKeyChrome — and never by family. Each resolves, in order, to
//
//   1. the theme's own control (HaUberTheme.components: a kit's override), else
//   2. its bespoke family's control, from the one table below ([familySwitch], [familyKey]) —
//      the only place a family names its controls, else
//   3. the shared control drawn from the theme's tokens: [TokenSwitch] (shape corners, square or
//      round knob, material, ink) and [GrammarKey] (outline, stroke weight, glow).
//
// A kit starts from HaComponentSlots.Grammar, which maps both slots to the shared controls, and
// may pick a bespoke family's control instead: Aurora borrows Expressive's switch.
// See docs/architecture/theme-kits.md.
// ---------------------------------------------------------------------------------------------

/** The switch the active theme draws: its own, its family's, or [TokenSwitch]. */
@Composable
internal fun switchRenderer(): HaSwitchRenderer {
  val theme = currentHaUberTheme().theme
  return theme.components.switch ?: familySwitch(theme.visuals) ?: TokenSwitch
}

/** The key mount the active theme draws: its own, its family's, or none. */
@Composable
internal fun keyRenderer(): HaKeyRenderer? {
  val theme = currentHaUberTheme().theme
  return theme.components.key ?: familyKey(theme.visuals)
}

/**
 * A bespoke family's own switch, or null when the family draws [TokenSwitch]. The one place a
 * family names its switch; cards never ask.
 */
internal fun familySwitch(family: HaVisualFamily): HaSwitchRenderer? =
  when {
    family == HaVisualFamily.Expressive -> FamilyControls.ExpressiveSwitch
    family == HaVisualFamily.Ikea -> FamilyControls.IkeaSwitch
    family == HaVisualFamily.Console -> FamilyControls.ConsoleSwitch
    family.isCommunity -> FamilyControls.Community
    else -> null
  }

/** A bespoke family's own key mount, or null for a flat family whose keys have none. */
internal fun familyKey(family: HaVisualFamily): HaKeyRenderer? =
  when (family) {
    HaVisualFamily.Console -> FamilyControls.ConsoleKey
    HaVisualFamily.Ikea -> FamilyControls.IkeaKey
    else -> null
  }

/**
 * The mount for a key or button face under the active theme — a machined key with a pilot lamp
 * (Console), a moulded paddle (IKEA), a kit's outlined rim — or no-op on a flat theme. Applied
 * inside the existing target, so its action, semantics and hit area remain with the caller. A bound
 * [accent] keeps live state or selection visible on the rim; [selected] is a mode key's state.
 */
@Composable
internal fun haKeyChrome(accent: RemoteColor? = null, selected: Boolean? = null): RemoteModifier =
  keyRenderer()?.chrome(accent, selected) ?: RemoteModifier

/** The switch's track and knob, in dp. */
internal val SwitchTrackWidth = 36.rdp
internal val SwitchTrackHeight = 22.rdp
private val KnobSize = 16.rdp
private val AmbientOutline = 2.rdp
private const val KnobInsetDp = 3f
private val KnobInset = KnobInsetDp.toInt().rdp
// Knob travels from KnobInset to (TrackWidth - KnobSize - KnobInset) = 36 - 16 - 3 = 17dp, so the
// slide distance is 17 - 3 = 14dp.
private const val KnobTravelDp = 36f - 16f - 2f * KnobInsetDp // = 14f

/**
 * The switch every theme without a switch of its own draws, from its tokens: the track's corner and
 * a square or round knob from the shape, a machined cap on painted metal, and the ink of a
 * monochrome theme. Knob position: derived `start` padding — `(KnobInsetDp + progress *
 * KnobTravelDp).asRemoteDp()`; see [RemoteHaToggleSwitchByProgress] for why not `offset` or
 * `toRemoteDp`.
 */
internal object TokenSwitch : HaSwitchRenderer {
  @Composable
  override fun Switch(spec: HaSwitchSpec) {
    // The uber theme's control shape and ink: an LCD switch is a square-knobbed block printed in
    // its ink, a Trinitron one a hairline-cornered slider.
    val uber = currentHaUberTheme()
    val ink = uber.ink
    val shape = uber.theme.shape
    val metal = shape.material == HaSurfaceMaterial.PaintedMetal
    val trackColor: RemoteColor =
      when {
        ink != null -> tween(ink.copy(alpha = 0.35f).rc, ink.rc, spec.colorProgress)
        // Painted metal: off is the bare recessed slot, on lights it in the accent.
        metal -> tween(ConsoleSlot.rc, spec.activeAccent, spec.colorProgress)
        else -> tween(spec.inactiveAccent, spec.activeAccent, spec.colorProgress)
      }
    // Ambient (a low-power display): the track's outline in place of its fill, and the knob lit
    // in the track's colour, so the switch still reads on black with few pixels lit.
    // No fade: a background and a border, which the Compose player doesn't redraw per frame.
    val ambient = rcAmbient(fade = false)
    val outlined = ambient !== RcAmbient.Never
    val knobColor =
      (if (ink != null) haTheme().cardBackground else Color.White.rc).let {
        if (outlined) ambient.color(it, trackColor) else it
      }
    // A machined cap, lit from above, in place of a flat knob.
    val knobFill =
      if (metal) RemoteModifier.background(RemoteBrush.verticalGradient(ConsoleMetal.map { it.rc }))
      else RemoteModifier.background(knobColor)
    val startPaddingDp = (KnobInsetDp.rf + spec.progress * KnobTravelDp.rf).asRemoteDp()
    val trackShape = RemoteRoundedCornerShape(shape.controlCornerDp.rdp)

    RemoteBox(
      modifier =
        spec.modifier
          .size(width = SwitchTrackWidth, height = SwitchTrackHeight)
          .clip(trackShape)
          .background(
            if (outlined) trackColor.copy(alpha = trackColor.alpha * ambient.fade) else trackColor
          )
          .then(
            if (outlined)
              RemoteModifier.border(
                AmbientOutline,
                trackColor.copy(alpha = trackColor.alpha * ambient.fraction),
                trackShape,
              )
            else RemoteModifier
          )
          .then(spec.clickable)
    ) {
      RemoteBox(
        modifier =
          RemoteModifier.padding(start = startPaddingDp, top = KnobInset)
            .size(KnobSize)
            .clip(
              if (shape.squareKnob) RemoteRoundedCornerShape((shape.controlCornerDp / 2).rdp)
              else RemoteCircleShape
            )
            .then(knobFill)
      )
    }
  }
}

/** The bespoke families' controls, moved here unchanged from the cards that used to branch. */
internal object FamilyControls {
  /** Material 3 Expressive's own switch: the thumb grows as it turns on and springs across. */
  val ExpressiveSwitch =
    object : HaSwitchRenderer {
      @Composable
      override fun Switch(spec: HaSwitchSpec) {
        RemoteM3SwitchByProgress(
          position = spec.progress,
          colorProgress = spec.colorProgress,
          colors =
            RcM3SwitchColors(
              checkedTrack = spec.activeAccent,
              checkedThumb = Color.White.rc,
              uncheckedTrack = haTheme().cardBackground,
              uncheckedThumb = spec.inactiveAccent,
              uncheckedBorder = spec.inactiveAccent,
            ),
          modifier = spec.sized(),
        )
      }
    }

  /** IKEA's two-part paddle, its indicator moving between the halves. */
  val IkeaSwitch =
    object : HaSwitchRenderer {
      @Composable
      override fun Switch(spec: HaSwitchSpec) {
        IkeaControlFace(
          tween(spec.inactiveAccent, spec.activeAccent, spec.progress),
          spec.progress,
          spec.sized(),
        )
      }
    }

  /** Console's rocker with a separate pilot lamp, off in the bare slot. */
  val ConsoleSwitch =
    object : HaSwitchRenderer {
      @Composable
      override fun Switch(spec: HaSwitchSpec) {
        ConsoleControlFace(
          tween(ConsoleSlot.rc, spec.activeAccent, spec.colorProgress),
          spec.sized(),
          spec.progress,
        )
      }
    }

  /** The community families' switches: each family's scale as the track, with its own thumb. */
  val Community =
    object : HaSwitchRenderer {
      @Composable
      override fun Switch(spec: HaSwitchSpec) {
        CommunitySwitch(spec.progress, spec.activeAccent, spec.inactiveAccent, spec.sized())
      }
    }

  /** Console's machined key in a recessed slot, with an optional rim and pilot lamp. */
  val ConsoleKey =
    object : HaKeyRenderer {
      @Composable
      override fun chrome(accent: RemoteColor?, selected: Boolean?): RemoteModifier =
        RemoteModifier.drawWithContent {
          val edge = width.min(height) * 0.045f.rf
          val depth = edge + 1f.rf
          fillRound(ConsoleSlot.rc, 0f.rf, 0f.rf, width, height, 3f.rf)
          drawRoundRect(
            metalPaint(0f.rf, height),
            RemoteOffset(0f.rf, 0f.rf),
            RemoteSize(width, height - depth),
            RemoteOffset(3f.rf, 3f.rf),
          )
          drawRoundRect(
            consoleCapPaint(0f.rf, height),
            RemoteOffset(edge, edge),
            RemoteSize(width - edge * 2f.rf, height - depth - edge * 2f.rf),
            RemoteOffset(2f.rf, 2f.rf),
          )
          if (accent != null)
            drawRoundRect(
              strokePaint(1f, accent),
              RemoteOffset(edge, edge),
              RemoteSize(width - edge * 2f.rf, height - depth - edge * 2f.rf),
              RemoteOffset(2f.rf, 2f.rf),
            )
          if (selected != null) {
            val lamp = RemoteOffset(width - 7f.rf, 6f.rf)
            drawCircle(fillPaint(ConsoleSlotLip.rc), 3f.rf, lamp)
            drawCircle(
              fillPaint(if (selected) accent ?: Color(0xFFF2A93B).rc else ConsoleSlot.rc),
              2f.rf,
              lamp,
            )
          }
          drawContent()
        }
    }

  /** IKEA's moulded remote key. */
  val IkeaKey =
    object : HaKeyRenderer {
      @Composable
      override fun chrome(accent: RemoteColor?, selected: Boolean?): RemoteModifier =
        ikeaKeyChrome(accent)
    }
}

/** The switch's own size, then the caller's click: what a face that is the target needs. */
private fun HaSwitchSpec.sized(): RemoteModifier =
  modifier.size(width = SwitchTrackWidth, height = SwitchTrackHeight).then(clickable)

/**
 * A key mount drawn from the grammar: nothing on a filled grammar (a flat key is the card's own
 * surface), a rim at the grammar's stroke weight on an outlined one — in the live accent when it
 * has one — and, for a selected mode key, a dot in the accent, with a halo on a glowing grammar. A
 * kit's keys; bespoke flat families keep keys with no mount.
 */
internal object GrammarKey : HaKeyRenderer {
  @Composable
  override fun chrome(accent: RemoteColor?, selected: Boolean?): RemoteModifier {
    val grammar = haGrammar()
    val theme = haTheme()
    if (!grammar.outline && selected != true) return RemoteModifier
    val rimColor = accent ?: theme.divider
    val dotColor = accent ?: theme.linkText
    val rim = (1.5f * grammar.stroke.scale).coerceAtLeast(1f)
    val corner = currentHaUberTheme().theme.shape.controlCornerDp.toFloat()
    return RemoteModifier.drawWithContent {
      if (grammar.outline) {
        drawRoundRect(
          strokePaint(rim, rimColor, round = false),
          RemoteOffset((rim / 2f).rf, (rim / 2f).rf),
          RemoteSize(width - rim.rf, height - rim.rf),
          RemoteOffset(corner.rf, corner.rf),
        )
      }
      if (selected == true) {
        val at = RemoteOffset(width - 6f.rf, 6f.rf)
        if (grammar.glow) drawCircle(fillPaint(dotColor.copy(alpha = 0.3f.rf)), 5f.rf, at)
        drawCircle(fillPaint(dotColor), 2.5f.rf, at)
      }
      drawContent()
    }
  }
}
