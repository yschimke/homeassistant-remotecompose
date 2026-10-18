@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.action.Action
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.modifier.visibility
import androidx.compose.remote.creation.compose.modifier.width
import androidx.compose.remote.creation.compose.shaders.RemoteBrush
import androidx.compose.remote.creation.compose.shaders.verticalGradient
import androidx.compose.remote.creation.compose.shapes.RemoteCircleShape
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.RemoteBoolean
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.asRemoteDp
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.ri
import androidx.compose.remote.creation.compose.state.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow

/**
 * One row of an Entities card:
 * ```
 *   [icon]  name  ..........  state-or-switch
 * ```
 *
 * Toggleable entities (lights / switches / locks / …) render a [RemoteHaToggleSwitch] in the
 * trailing slot — matches HA's `hui-toggle-entity-row`. Read-only entities show the state text. An
 * unavailable entity is dimmed as a whole, with a neutral icon ([entityLook]).
 */
@Composable
@RemoteComposable
fun RemoteHaEntityRow(
  data: HaEntityRowData,
  modifier: RemoteModifier = RemoteModifier,
  verticalPaddingDp: Int = 6,
  minHeightDp: Int = EntityRowMinHeightDp,
) {
  val theme = haTheme()
  val clickable =
    data.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
  val look = entityLook(data.entityId, data.accent)
  val isOnBinding = look.isOn
  val accent: RemoteColor = look.accent

  RemoteRow(
    // The player's tap area excludes padding, so a touch-target row takes its height from the strut
    // below rather than from padding. The row is the tap target: its switch takes no tap of its own
    // (a 36 × 22dp target for the same action).
    modifier =
      modifier
        .then(clickable)
        .fillMaxWidth()
        .padding(vertical = (if (minHeightDp > 0) 0 else verticalPaddingDp).rdp),
    horizontalArrangement = RemoteArrangement.SpaceBetween,
    verticalAlignment = RemoteAlignment.CenterVertically,
  ) {
    // A zero-width strut holds the row at [minHeightDp]: `heightIn` on the row isn't honoured by
    // the
    // card player.
    if (minHeightDp > 0) RemoteBox(modifier = RemoteModifier.width(0.rdp).height(minHeightDp.rdp))
    // The icon and name take what the trailing state or switch leaves: a long name ellipsizes
    // instead of pushing the value (the part the row is read for) or the switch out of a narrow
    // widget cell.
    RemoteRow(
      modifier = RemoteModifier.weight(1f).padding(end = 8.rdp),
      verticalAlignment = RemoteAlignment.CenterVertically,
    ) {
      HaStateIcon(
        icon = data.icon,
        accent = data.accent,
        look = look,
        tint = accent,
        // The name is drawn beside the icon, so the icon is decorative: described too, a screen
        // reader would read the name twice.
        contentDescription = null,
        modifier = RemoteModifier.size(20.rdp),
      )
      RemoteBox(modifier = RemoteModifier.padding(start = 12.rdp)) {
        RemoteText(
          text = entityName(data.entityId, data.name),
          color = look.dim(theme.primaryText),
          fontSize = HaType.Body,
          style = haTextStyle(),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    TrailingSwitchOrState(
      look = look,
      toggle = data.toggle,
      switch = { isOn, switchModifier ->
        RemoteHaToggleSwitch(
          isOn = isOn,
          activeAccent = look.activeAccent,
          inactiveAccent = look.inactiveAccent,
          modifier = switchModifier,
        )
      },
      state = { stateModifier ->
        // HA's generic entity row draws the state in the primary text colour: it is the value the
        // row is read for, not metadata.
        RemoteText(
          text = data.state,
          modifier = stateModifier,
          color = look.dim(theme.primaryText),
          fontSize = HaType.Body,
          style = haTextStyle(),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      },
    )
  }
}

/**
 * A row's height at least: a full touch target (48dp), which is also HA's row pitch (a 40px row
 * with 8px between rows). A fixed widget canvas, which can't grow, passes 0.
 */
const val EntityRowMinHeightDp: Int = 48

/**
 * A row's trailing slot: [switch] for a [toggle] entity with an on/off state, else [state] (its
 * state text). An unavailable entity shows [state] in place of the switch, as HA's toggle row shows
 * "Unavailable": live off `<id>.is_available` (both are emitted, one visible), or chosen at encode
 * time in a sibling size tier that doesn't register the binding. Widgets, which don't show
 * availability ([LocalEntityAvailability]), keep the dimmed switch: a baked widget comes from a
 * design template whose properties don't include availability, so a direct capture must not differ.
 *
 * Both are emitted straight into the caller's row with a visibility modifier, not inside a box: a
 * wrapping box isn't re-measured when the switch's knob padding animates, so the knob stayed at its
 * encode-time position.
 */
@Composable
@RemoteComposable
internal fun TrailingSwitchOrState(
  look: HaEntityLook,
  toggle: Boolean,
  switch: @Composable @RemoteComposable (isOn: RemoteBoolean, modifier: RemoteModifier) -> Unit,
  state: @Composable @RemoteComposable (modifier: RemoteModifier) -> Unit,
) {
  val isOn = look.isOn
  val isAvailable = look.isAvailable
  when {
    !toggle || isOn == null || !look.staticallyAvailable && isAvailable == null ->
      state(RemoteModifier)
    isAvailable == null -> switch(isOn, RemoteModifier)
    else -> {
      switch(isOn, RemoteModifier.visibility(isAvailable.select(Visible, Gone)))
      state(RemoteModifier.visibility(isAvailable.select(Gone, Visible)))
    }
  }
}

// RemoteCompose component visibility (`Component.Visibility`).
private val Visible = 1.ri
private val Gone = 0.ri

// ——— toggle switch ———

/** Roughly Material 2's switch dimensions. */
private val TrackWidth = 36.rdp
private val TrackHeight = 22.rdp
private val KnobSize = 16.rdp
private const val KnobInsetDp = 3f
private val KnobInset = KnobInsetDp.toInt().rdp

/** Distance the knob travels in dp between off and on. */
private const val KnobTravelDp = 36f - 16f - 2f * KnobInsetDp // = 14f

/** Animation timings. */
private const val ToggleDurationSeconds = 0.20f

/**
 * Pill-shaped toggle switch — animates between off / on.
 *
 * 1. [isOn] is the live `RemoteBoolean` for the entity (typically `<entityId>.is_on` from
 *    [LiveValues.isOn]). The host pushes by name; the document re-renders without re-encoding.
 * 2. The boolean drives a `select`-derived `RemoteFloat` progress in `[0, 1]`, wrapped in
 *    [animateRemoteFloat] so successive flips tween instead of snapping.
 * 3. The track color tweens between [inactiveAccent] and [activeAccent] via the alpha010 [tween]
 *    color helper.
 * 4. The knob position is the same animated `progress` mapped through `toRemoteDp()` and applied as
 *    `Modifier.offset` — avoids the weight()-on-derived-RemoteFloat issue that bit alpha08
 *    (b/504893436).
 *
 * @param tapAction Emitted as a `HostAction` so the runtime can call HA's service. There is no
 *   in-document optimistic flip — the visual only changes once the host writes the new value back
 *   to [isOn]. (We may layer optimistic updates on top later.)
 */
@Composable
@RemoteComposable
fun RemoteHaToggleSwitch(
  isOn: RemoteBoolean,
  activeAccent: RemoteColor,
  inactiveAccent: RemoteColor,
  modifier: RemoteModifier = RemoteModifier,
  tapAction: HaAction = HaAction.None,
) {
  val target: RemoteFloat = isOn.select(1f.rf, 0f.rf)
  val progress: RemoteFloat = animateRemoteFloat(target, ToggleDurationSeconds)

  val host: Action? = tapAction.toRemoteAction()
  val clickable = if (host != null) RemoteModifier.clickable(host) else RemoteModifier

  RemoteHaToggleSwitchByProgress(
    progress = progress,
    activeAccent = activeAccent,
    inactiveAccent = inactiveAccent,
    modifier = modifier,
    clickable = clickable,
  )
}

/**
 * Progress-driven variant — deterministic layout for any [RemoteFloat] `progress` in `[0, 1]`.
 * Exposed so previews can freeze the switch at 0 / 0.5 / 1, and so callers (driving an externally
 * animated source like a host-bound RemoteBoolean) can supply their own progress directly.
 *
 * Track color: `tween(inactiveAccent, activeAccent, progress)`. Knob position: derived `start`
 * padding — `(KnobInsetDp + progress * KnobTravelDp).asRemoteDp()`. Use `asRemoteDp` (the
 * documented public converter — see [RemoteCompose docs][rc]) and not the `toRemoteDp()` cousin:
 * the latter divides the input by display density (it expects px input and returns dp), so feeding
 * it a dp-scaled float silently scales the travel down by `1 / density` (~38 % at 2.625) and the
 * knob barely moves. `asRemoteDp` wraps the float as-dp directly.
 *
 * `Modifier.offset(x: RemoteDp)` is not used here — see commit f1832a9: `offset` with a
 * RemoteFloat-derived RemoteDp visibly collapsed the travel even when scaling was correct, so the
 * position is folded into start padding instead.
 *
 * [rc]:
 * https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/remote/remote-creation-compose/src/main/java/androidx/compose/remote/creation/compose/state/RemoteDp.kt
 */
@Composable
@RemoteComposable
fun RemoteHaToggleSwitchByProgress(
  progress: RemoteFloat,
  activeAccent: RemoteColor,
  inactiveAccent: RemoteColor,
  modifier: RemoteModifier = RemoteModifier,
  clickable: RemoteModifier = RemoteModifier,
) {
  if (visualFamily() == HaVisualFamily.Ikea) {
    IkeaControlFace(
      tween(inactiveAccent, activeAccent, progress),
      progress,
      modifier.size(width = TrackWidth, height = TrackHeight).then(clickable),
    )
    return
  }
  if (visualFamily() == HaVisualFamily.Console) {
    ConsoleControlFace(
      tween(ConsoleSlot.rc, activeAccent, progress),
      modifier.size(width = TrackWidth, height = TrackHeight).then(clickable),
      progress,
    )
    return
  }
  if (visualFamily().isCommunity) {
    CommunitySwitch(
      progress,
      activeAccent,
      inactiveAccent,
      modifier.size(width = TrackWidth, height = TrackHeight).then(clickable),
    )
    return
  }
  // The uber theme's control shape and ink: an LCD switch is a square-knobbed block printed in its
  // ink, a Trinitron one a hairline-cornered slider.
  val uber = currentHaUberTheme()
  val ink = uber.ink
  val shape = uber.theme.shape
  val metal = shape.material == HaSurfaceMaterial.PaintedMetal
  val trackColor: RemoteColor =
    when {
      ink != null -> tween(ink.copy(alpha = 0.35f).rc, ink.rc, progress)
      // Painted metal: off is the bare recessed slot, on lights it in the accent.
      metal -> tween(ConsoleSlot.rc, activeAccent, progress)
      else -> tween(inactiveAccent, activeAccent, progress)
    }
  val knobColor = if (ink != null) haTheme().cardBackground else Color.White.rc
  // A machined cap, lit from above, in place of a flat knob.
  val knobFill =
    if (metal) RemoteModifier.background(RemoteBrush.verticalGradient(ConsoleMetal.map { it.rc }))
    else RemoteModifier.background(knobColor)
  val startPaddingDp = (KnobInsetDp.rf + progress * KnobTravelDp.rf).asRemoteDp()

  RemoteBox(
    modifier =
      modifier
        .size(width = TrackWidth, height = TrackHeight)
        .clip(RemoteRoundedCornerShape(shape.controlCornerDp.rdp))
        .background(trackColor)
        .then(clickable)
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
