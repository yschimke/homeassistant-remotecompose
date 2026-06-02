@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.core.operations.layout.animation.AnimationSpec
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.animationSpec

/**
 * Pin a `RemoteStateLayout` branch's enter/exit animation to zero duration so the swap between
 * branches is immediate.
 *
 * The default `RemoteStateLayout` transition is a 300 ms FADE_IN/FADE_OUT cross-fade. Two failure
 * modes ride on that window (the alpha #309 overlay bug):
 *
 * * the in-process preview player captures a frame mid-fade, leaving the outgoing branch
 *   half-visible behind the incoming one (the "ghosting"); and
 * * for branches whose root layout-manager has a non-null `mAnimateMeasure`,
 *   `checkEndOfTransition()` never clears `inTransition`, so the overlay paints on every frame and
 *   the previous branch's contents bleed through permanently.
 *
 * Zeroing both the motion and visibility durations removes the cross-fade window and the
 * branch-root measure animation, so only the selected branch is ever drawn. Apply it to the
 * immediate child of each `RemoteStateLayout` branch.
 *
 * NB: we deliberately do *not* gate the branch with `alpha(select(…))` — the derived float doesn't
 * reliably materialise in the current alpha (#224), which blanks the *selected* branch too.
 *
 * Shared by [androidx.compose.remote] Fixed-mode size breakpoints (`RemoteSizeBreakpoint`) and the
 * arc-dial mode chip's on/off toggle.
 */
fun RemoteModifier.immediateSwap(): RemoteModifier =
  animationSpec(
    -1,
    /* motionDuration = */ 0f,
    /* motionEasingType = */ 1,
    /* visibilityDuration = */ 0f,
    /* visibilityEasingType = */ 1,
    AnimationSpec.ANIMATION.FADE_IN,
    AnimationSpec.ANIMATION.FADE_OUT,
    true,
  )
