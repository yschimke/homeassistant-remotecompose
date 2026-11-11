@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import ee.schimke.composeai.rccreation.compose.text.RemoteTextOverflow
import ee.schimke.composeai.rccreation.compose.action.RemoteAction
import ee.schimke.composeai.rccreation.compose.layout.RemoteAlignment
import ee.schimke.composeai.rccreation.compose.layout.RemoteArrangement
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.layout.RemoteRow
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.clickable
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxWidth
import ee.schimke.composeai.rccreation.compose.modifier.height
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.modifier.size
import ee.schimke.composeai.rccreation.compose.modifier.visibility
import ee.schimke.composeai.rccreation.compose.modifier.width
import ee.schimke.composeai.rccreation.compose.state.RemoteBoolean
import ee.schimke.composeai.rccreation.compose.state.RemoteColor
import ee.schimke.composeai.rccreation.compose.state.RemoteFloat
import ee.schimke.composeai.rccreation.compose.state.rc
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.state.rf
import ee.schimke.composeai.rccreation.compose.state.ri
import ee.schimke.composeai.rccreation.compose.state.lerp
import androidx.compose.runtime.Composable

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
      modifier = RemoteModifier.weight(1f.rf).padding(end = 8.rdp),
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
          overflow = RemoteTextOverflow.Ellipsis,
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
          overflow = RemoteTextOverflow.Ellipsis,
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

// The switch is a slot: its track, knob and the families' faces are in HaControls.kt.

/**
 * Pill-shaped toggle switch — animates between off / on.
 *
 * 1. [isOn] is the live `RemoteBoolean` for the entity (typically `<entityId>.is_on` from
 *    [LiveValues.isOn]). The host pushes by name; the document re-renders without re-encoding.
 * 2. The boolean drives a `select`-derived `RemoteFloat` progress in `[0, 1]`, animated on the card
 *    style's `spatialFast` motion token ([haMotion]) so successive flips move instead of snapping.
 * 3. The track color tweens between [inactiveAccent] and [activeAccent] via the alpha010 [tween]
 *    color helper, on its own `effectsFast` progress where the style has one: a spring that
 *    overshoots may move the knob but never the colour.
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
  val motion = haMotion()
  val progress: RemoteFloat = animateRemoteFloat(target, motion.spatialFast)
  // One expression when both tokens agree (every tween style), so their documents don't change.
  // Painted metal's pilot lamp is a filament: it warms on the fast effect and cools on the slow
  // one.
  val colorProgress: RemoteFloat =
    when {
      currentHaUberTheme().theme.shape.material == HaSurfaceMaterial.PaintedMetal ->
        animateAttackRelease(target, motion.effectsFast, motion.effectsSlow)
      motion.effectsFast == motion.spatialFast -> progress
      else -> animateRemoteFloat(target, motion.effectsFast)
    }

  val host: RemoteAction? = tapAction.toRemoteAction()
  val clickable = if (host != null) RemoteModifier.clickable(host) else RemoteModifier

  RemoteHaToggleSwitchByProgress(
    progress = progress,
    activeAccent = activeAccent,
    inactiveAccent = inactiveAccent,
    modifier = modifier,
    clickable = clickable,
    colorProgress = colorProgress,
  )
}

/**
 * Progress-driven variant — deterministic layout for any [RemoteFloat] `progress` in `[0, 1]`.
 * [colorProgress] mixes the colours, where a caller animates them apart from the knob. Exposed so
 * previews can freeze the switch at 0 / 0.5 / 1, and so callers (driving an externally animated
 * source like a host-bound RemoteBoolean) can supply their own progress directly.
 *
 * Track color: `lerp(inactiveAccent, activeAccent, progress)`. Knob position: derived `start`
 * padding — `(KnobInsetDp + progress * KnobTravelDp).asRemoteDp()` ([TokenSwitch]). Use
 * `asRemoteDp` (the documented public converter — see [RemoteCompose docs][rc]) and not the
 * `toRemoteDp()` cousin: the latter divides the input by display density (it expects px input and
 * returns dp), so feeding it a dp-scaled float silently scales the travel down by `1 / density`
 * (~38 % at 2.625) and the knob barely moves. `asRemoteDp` wraps the float as-dp directly.
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
  colorProgress: RemoteFloat = progress,
) {
  switchRenderer()
    .Switch(
      HaSwitchSpec(
        progress = progress,
        colorProgress = colorProgress,
        activeAccent = activeAccent,
        inactiveAccent = inactiveAccent,
        modifier = modifier,
        clickable = clickable,
      ),
    )
}
