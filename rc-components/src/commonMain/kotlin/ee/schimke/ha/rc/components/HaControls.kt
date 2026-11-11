@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import ee.schimke.composeai.rccreation.compose.capture.toRemoteImageVector
import ee.schimke.composeai.rccreation.compose.layout.RemoteAlignment
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.layout.RemoteOffset
import ee.schimke.composeai.rccreation.compose.layout.RemoteSize
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.background
import ee.schimke.composeai.rccreation.compose.modifier.border
import ee.schimke.composeai.rccreation.compose.modifier.clip
import ee.schimke.composeai.rccreation.compose.modifier.drawWithContent
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.modifier.size
import ee.schimke.composeai.rccreation.compose.shaders.RemoteBrush
import ee.schimke.composeai.rccreation.compose.shaders.verticalGradient
import ee.schimke.composeai.rccreation.compose.shapes.RemoteCircleShape
import ee.schimke.composeai.rccreation.compose.shapes.RemoteRoundedCornerShape
import ee.schimke.composeai.rccreation.compose.state.RemoteColor
import ee.schimke.composeai.rccreation.compose.state.RemoteString
import ee.schimke.composeai.rccreation.compose.state.asRemoteDp
import ee.schimke.composeai.rccreation.compose.state.rc
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.state.rf
import ee.schimke.composeai.rccreation.compose.state.lerp
import ee.schimke.composeai.rccreation.foundation.icon.RemoteBasicIcon
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import ee.schimke.rc.m3.RcAmbient
import ee.schimke.rc.m3.RcM3SwitchColors
import ee.schimke.rc.m3.RemoteM3ShapeMorph
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
        ink != null -> lerp(ink.copy(alpha = 0.35f).rc, ink.rc, spec.colorProgress)
        // Painted metal: off is the bare recessed slot, on lights it in the accent.
        metal -> lerp(ConsoleSlot.rc, spec.activeAccent, spec.colorProgress)
        else -> lerp(spec.inactiveAccent, spec.activeAccent, spec.colorProgress)
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
            if (outlined) trackColor.copy(alpha = trackColor.alpha * ambient.fade) else trackColor,
          )
          .then(
            if (outlined)
              RemoteModifier.border(
                AmbientOutline,
                trackColor.copy(alpha = trackColor.alpha * ambient.fraction),
                trackShape,
              )
            else RemoteModifier,
          )
          .then(spec.clickable),
    ) {
      RemoteBox(
        modifier =
          RemoteModifier.padding(start = startPaddingDp, top = KnobInset)
            .size(KnobSize)
            .clip(
              if (shape.squareKnob) RemoteRoundedCornerShape((shape.controlCornerDp / 2).rdp)
              else RemoteCircleShape,
            )
            .then(knobFill),
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
          lerp(spec.inactiveAccent, spec.activeAccent, spec.progress),
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
          lerp(ConsoleSlot.rc, spec.activeAccent, spec.colorProgress),
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

  /** Console's thermostat body; its other dials are the knob in a lamp ring. */
  val ConsoleDialLayout =
    object : HaDialLayoutRenderer {
      override fun body(centerLabelAttribute: String?): HaDialBody =
        if (centerLabelAttribute == "current_temperature_label") HaDialBody.ConsoleThermostat
        else HaDialBody.Ring

      override val sideSteppers: Boolean
        get() = false
    }

  /** IKEA's light remote for a light's brightness; the ring otherwise. */
  val IkeaDialLayout =
    object : HaDialLayoutRenderer {
      override fun body(centerLabelAttribute: String?): HaDialBody =
        if (centerLabelAttribute == "brightness_pct") HaDialBody.IkeaLight else HaDialBody.Ring

      override val sideSteppers: Boolean
        get() = false
    }

  /** Cupertino's filled bar, its steppers beside it. */
  val CupertinoDialLayout =
    object : HaDialLayoutRenderer {
      override fun body(centerLabelAttribute: String?): HaDialBody = HaDialBody.CupertinoBar

      override val sideSteppers: Boolean
        get() = true
    }

  /** The ring with its steppers beside it (Bubble, LCARS, Aeon). */
  val SideStepperDialLayout =
    object : HaDialLayoutRenderer {
      override fun body(centerLabelAttribute: String?): HaDialBody = HaDialBody.Ring

      override val sideSteppers: Boolean
        get() = true
    }

  /** Console's transport keys: a key face with a pilot lamp, the icon in the face's ink. */
  val ConsoleTransportKey =
    object : HaTransportKeyRenderer {
      @Composable
      override fun Key(spec: HaTransportSpec) {
        ConsoleControlFace(haInk(spec.accent), RemoteModifier.size(spec.sizeDp.rdp))
        RemoteBasicIcon(
          imageVector = spec.icon.toRemoteImageVector(),
          contentDescription = null,
          modifier = RemoteModifier.padding(end = (spec.sizeDp * 0.28f).rdp).size(spec.iconDp.rdp),
          tint = haTheme().primaryText,
        )
      }

      @Composable
      override fun Glyph(icon: ImageVector, accent: Color, contentDescription: RemoteString) {
        RemoteBox(
          modifier = RemoteModifier.size(32.rdp),
          contentAlignment = RemoteAlignment.Center,
        ) {
          ConsoleControlFace(haInk(accent), RemoteModifier.size(32.rdp))
          RemoteBasicIcon(
            imageVector = icon.toRemoteImageVector(),
            contentDescription = contentDescription,
            modifier = RemoteModifier.padding(end = 9.rdp).size(18.rdp),
            tint = haTheme().primaryText,
          )
        }
      }
    }

  /**
   * Material 3 Expressive's shape morph: a round chip that squares off when its entity is on and
   * squares further while pressed, both moving by the style's springs.
   */
  val ExpressiveIconChip =
    object : HaIconChipRenderer {
      override val pressable: Boolean
        get() = true

      @Composable
      override fun Chip(spec: HaIconChipSpec, icon: @Composable (onFace: Boolean) -> Unit) {
        val selected =
          spec.look.isOn?.let { animateRemoteFloat(it.select(1f.rf, 0f.rf), haMotion().spatial) }
            ?: 0f.rf
        RemoteM3ShapeMorph(
          selected = selected,
          pressed = spec.pressed,
          color = spec.accent.copy(alpha = spec.accent.alpha * 0.2f.rf),
          modifier = RemoteModifier.size(spec.chipDp.rdp),
        ) {
          icon(false)
        }
      }
    }

  /** Console's key face, lit by the entity's state, the icon on it. */
  val ConsoleIconChip =
    object : HaIconChipRenderer {
      @Composable
      override fun Chip(spec: HaIconChipSpec, icon: @Composable (onFace: Boolean) -> Unit) {
        RemoteBox(
          modifier =
            RemoteModifier.size(spec.chipDp.rdp)
              .clip(haControlShape(20, round = true))
              .then(RemoteModifier),
          contentAlignment = RemoteAlignment.Center,
        ) {
          ConsoleControlFace(
            spec.look.isOn?.select(spec.accent, ConsoleSlot.rc) ?: spec.accent,
            RemoteModifier.size(spec.chipDp.rdp),
            spec.look.isOn?.select(1f.rf, 0f.rf),
          )
          icon(true)
        }
      }
    }

  /** IKEA's moulded key behind the icon. */
  val IkeaIconChip =
    object : HaIconChipRenderer {
      @Composable
      override fun Chip(spec: HaIconChipSpec, icon: @Composable (onFace: Boolean) -> Unit) {
        RemoteBox(
          modifier =
            RemoteModifier.size(spec.chipDp.rdp)
              .clip(haControlShape(20, round = true))
              .then(ikeaKeyChrome(spec.accent)),
          contentAlignment = RemoteAlignment.Center,
        ) {
          icon(false)
        }
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
 * has one, except in a launcher widget — and, for a selected mode key, a dot in the accent, with a
 * halo on a glowing grammar. A kit's keys; bespoke flat families keep keys with no mount.
 */
internal object GrammarKey : HaKeyRenderer {
  @Composable
  override fun chrome(accent: RemoteColor?, selected: Boolean?): RemoteModifier {
    val grammar = haGrammar()
    val theme = haTheme()
    if (!grammar.outline && selected != true) return RemoteModifier
    // A launcher widget is re-baked on every change and its platform player can't fold a live
    // colour expression (a tween of the entity's accents), so its keys take the theme's constants.
    val live = accent?.takeUnless { isWidgetActionCapture() }
    val rimColor = live ?: theme.divider
    val dotColor = live ?: theme.linkText
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

// ---- Control dial, transport keys and icon chips --------------------------------------------
//
// Same order as the switch and key: the theme's own (a kit's slot), else its bespoke family's
// (the one table below), else the shared one from the theme's tokens. The bespoke families' own
// are moved here unchanged from the cards that used to branch on them.

/** The theme's own dial ring, or null for the family's (Classic's, Console's knob, Dials.kt). */
@Composable
internal fun themedDialRenderer(): HaDialRenderer? = currentHaUberTheme().theme.components.dial

/** The dial layout the active theme uses: its own, its family's, or [StandardDialLayout]. */
@Composable
internal fun dialLayout(): HaDialLayoutRenderer {
  val theme = currentHaUberTheme().theme
  return theme.components.dialLayout ?: familyDialLayout(theme.visuals) ?: StandardDialLayout
}

/** The transport keys the active theme draws: its own, its family's, or [TokenTransportKey]. */
@Composable
internal fun transportKeyRenderer(): HaTransportKeyRenderer {
  val theme = currentHaUberTheme().theme
  return theme.components.transportKey ?: familyTransportKey(theme.visuals) ?: TokenTransportKey
}

/** The icon chip the active theme draws: its own, its family's, or [TokenIconChip]. */
@Composable
internal fun iconChipRenderer(): HaIconChipRenderer {
  val theme = currentHaUberTheme().theme
  return theme.components.iconChip ?: familyIconChip(theme.visuals) ?: TokenIconChip
}

/** A bespoke family's own dial layout, or null for the standard ring with steppers under it. */
internal fun familyDialLayout(family: HaVisualFamily): HaDialLayoutRenderer? =
  when (family) {
    HaVisualFamily.Console -> FamilyControls.ConsoleDialLayout
    HaVisualFamily.Ikea -> FamilyControls.IkeaDialLayout
    HaVisualFamily.Cupertino -> FamilyControls.CupertinoDialLayout
    HaVisualFamily.Bubble,
    HaVisualFamily.Lcars,
    HaVisualFamily.Aeon -> FamilyControls.SideStepperDialLayout
    else -> null
  }

/** A bespoke family's own transport keys, or null for [TokenTransportKey]. */
internal fun familyTransportKey(family: HaVisualFamily): HaTransportKeyRenderer? =
  if (family == HaVisualFamily.Console) FamilyControls.ConsoleTransportKey else null

/** A bespoke family's own icon chip, or null for [TokenIconChip]. */
internal fun familyIconChip(family: HaVisualFamily): HaIconChipRenderer? =
  when (family) {
    HaVisualFamily.Expressive -> FamilyControls.ExpressiveIconChip
    HaVisualFamily.Console -> FamilyControls.ConsoleIconChip
    HaVisualFamily.Ikea -> FamilyControls.IkeaIconChip
    else -> null
  }

/** The ring for every card, steppers under it. */
internal object StandardDialLayout : HaDialLayoutRenderer {
  override fun body(centerLabelAttribute: String?): HaDialBody = HaDialBody.Ring

  override val sideSteppers: Boolean
    get() = false
}

/**
 * Transport keys from the theme's tokens: the icon in the marking colour, mounted on the theme's
 * key ([haKeyChrome]) when it has one — IKEA's moulded key, a kit's outlined rim — and bare
 * otherwise.
 */
internal object TokenTransportKey : HaTransportKeyRenderer {
  @Composable
  override fun Key(spec: HaTransportSpec) {
    if (keyRenderer() != null) {
      RemoteBox(RemoteModifier.size(spec.sizeDp.rdp).then(haKeyChrome(haInk(spec.accent))))
    }
    RemoteBasicIcon(
      imageVector = spec.icon.toRemoteImageVector(),
      contentDescription = null,
      modifier = RemoteModifier.size(spec.iconDp.rdp),
      tint = haInk(spec.accent),
    )
  }

  @Composable
  override fun Glyph(icon: ImageVector, accent: Color, contentDescription: RemoteString) {
    RemoteBox(modifier = RemoteModifier.size(22.rdp), contentAlignment = RemoteAlignment.Center) {
      RemoteBasicIcon(
        imageVector = icon.toRemoteImageVector(),
        contentDescription = contentDescription,
        modifier = RemoteModifier.size(22.rdp),
        tint = haInk(accent),
      )
    }
  }
}

/**
 * A button's icon chip from the theme's tokens: a round, tinted face in the theme's control shape,
 * with the theme's key mount over it when it has one (a kit's outlined rim).
 */
internal object TokenIconChip : HaIconChipRenderer {
  @Composable
  override fun Chip(spec: HaIconChipSpec, icon: @Composable (onFace: Boolean) -> Unit) {
    val face =
      RemoteModifier.size(spec.chipDp.rdp)
        .clip(haControlShape(20, round = true))
        .then(RemoteModifier.background(spec.accent.copy(alpha = spec.accent.alpha * 0.2f.rf)))
    RemoteBox(
      modifier = if (keyRenderer() != null) face.then(haKeyChrome(spec.accent)) else face,
      contentAlignment = RemoteAlignment.Center,
    ) {
      icon(false)
    }
  }
}
