@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.remote.creation.compose.action.Action
import androidx.compose.remote.creation.compose.action.valueChange
import androidx.compose.remote.creation.compose.capture.toRemoteImageVector
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteCanvas
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.layout.RemoteSize
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.border
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.onTouchDown
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.modifier.visibility
import androidx.compose.remote.creation.compose.state.MutableRemoteInt
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteInt
import androidx.compose.remote.creation.compose.state.RemotePaint
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.max
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rememberMutableRemoteInt
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.ri
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.toRemoteDp
import androidx.compose.remote.foundation.icon.RemoteBasicIcon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign

/**
 * How a card's controls (its buttons, toggles, steppers and sliders) take a touch on the surface it
 * is drawn for. The host picks one per capture with [ProvideHaControlLockMode]:
 *
 * - [Off]: controls act on a touch. The app's dashboard, which has its own read-only lock (the top
 *   bar's chip), and the watch's full-screen card, which is opened deliberately.
 * - [Locked]: controls start locked, drawn muted with a lock on the card. A touch on one shows the
 *   "Slide to unlock" strip instead of acting; sliding its knob across unlocks every control on the
 *   card until it is drawn again. A card with a slider on a launcher widget, brushed by every swipe
 *   across the home screen, or in the watch's card list.
 * - [ReadOnly]: sliders only show their value, without a touch expression; a host that can't run a
 *   drag (a Wear widget, whose every tap opens the app instead). Taps are left as they are.
 *
 * The lock is inside the document: it needs no host round trip, so it works the same in every
 * player. It is a guard against brushes, not a permission: the host still checks every action
 * ([HighRiskActions] and the read-only mode keep their own rules).
 */
enum class HaControlLockMode {
  Off,
  Locked,
  ReadOnly,
}

/** The capture's control lock mode: see [HaControlLockMode]. */
val LocalHaControlLockMode = staticCompositionLocalOf { HaControlLockMode.Off }

/** Capture [content] with [mode] for its controls. */
@Composable
fun ProvideHaControlLockMode(mode: HaControlLockMode, content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalHaControlLockMode provides mode, content = content)
}

/**
 * One card's lock, from [RemoteHaControlLockFrame].
 *
 * [unlocked] is 1 once the strip's knob has been slid across, else 0: a control multiplies its drag
 * by it and picks its colours, visibilities and payload with it. [touched] tells the frame a locked
 * control was touched, so it shows the strip.
 */
class HaControlLock
internal constructor(val unlocked: RemoteFloat, private val hint: MutableRemoteInt?) {
  /** A visibility that shows while locked: a control's lock glyph. */
  val lockedVisibility: RemoteInt = if (hint == null) Visible else isUnlocked.select(Gone, Visible)

  /** A visibility that shows once unlocked. */
  val unlockedVisibility: RemoteInt = if (hint == null) Gone else isUnlocked.select(Visible, Gone)

  /** The unlock strip's visibility: after a locked touch, until unlocked. */
  internal val stripVisibility: RemoteInt = hint?.let { isUnlocked.select(Gone, it) } ?: Gone

  /**
   * Whether a control on the card follows this lock: set by each one as it composes, read by the
   * frame after the card, so a card with nothing to act on carries no badge.
   */
  internal var hasControls: Boolean = false

  /** Whether [unlocked] can change: false for a read-only capture, which never unlocks. */
  val canUnlock: Boolean
    get() = hint != null

  /** The action that shows the unlock strip; null when there is nothing to unlock. */
  val touched: Action?
    get() = hint?.let {
      hasControls = true
      valueChange(it, 1.ri)
    }

  /** [unlocked] as a condition. */
  val isUnlocked: androidx.compose.remote.creation.compose.state.RemoteBoolean
    get() = unlocked.isGreaterThanOrEqualTo(0.5f.rf)

  /**
   * [ifUnlocked] while unlocked, else [ifLocked]: a colour that follows the lock. A read-only
   * capture isn't locked, only not draggable: it keeps [ifUnlocked].
   */
  fun color(ifUnlocked: RemoteColor, ifLocked: RemoteColor): RemoteColor =
    if (!canUnlock) ifUnlocked else isUnlocked.select(ifUnlocked, ifLocked)

  /** [ifUnlocked] while unlocked, else [ifLocked]: an action's payload that follows the lock. */
  internal fun select(ifUnlocked: RemoteString, ifLocked: RemoteString): RemoteString =
    if (!canUnlock) ifLocked else isUnlocked.select(ifUnlocked, ifLocked)
}

private val LocalHaControlLock = staticCompositionLocalOf<HaControlLock?> { null }

/**
 * The lock a control in this capture follows: null when controls act on a touch
 * ([HaControlLockMode.Off], or a platform with no drag controls to unlock with); always locked for
 * [HaControlLockMode.ReadOnly].
 */
@Composable
fun haControlLock(): HaControlLock? =
  when (LocalHaControlLockMode.current) {
    HaControlLockMode.Off -> null
    HaControlLockMode.ReadOnly -> ReadOnlyLock
    HaControlLockMode.Locked -> LocalHaControlLock.current
  }

private val ReadOnlyLock by lazy { HaControlLock(0f.rf, hint = null) }

/**
 * A card drawn for [HaControlLockMode.Locked]: [content] with its controls locked, a lock badge in
 * the top corner (open once unlocked), and, after a locked control is touched, the "Slide to
 * unlock" strip across the bottom.
 *
 * The badge and the strip are drawn after [content], as its siblings: call this in a box that fills
 * the card (a widget's surface), so they overlay it without wrapping it in a layout of their own.
 * In any other mode, or where the player can't drag ([touchControlsSupported]), this is [content]
 * alone. A card none of whose parts acts on a touch (a sensor) gets no badge or strip: each control
 * marks the lock as it composes ([HaControlLock.touched]), before the frame draws them.
 */
@Composable
@RemoteComposable
fun RemoteHaControlLockFrame(content: @Composable @RemoteComposable () -> Unit) {
  if (LocalHaControlLockMode.current != HaControlLockMode.Locked || !touchControlsSupported) {
    content()
    return
  }
  val knobSlot = ReservedId("unlock-knob")
  val knob = reservedFloat(knobSlot)
  // Unlocked from the moment the knob reaches the end: it is released there or eases there.
  val unlocked = knob.isGreaterThanOrEqualTo(UnlockAt.rf).select(1f.rf, 0f.rf)
  val hint = rememberMutableRemoteInt(0)
  val lock = HaControlLock(unlocked, hint)
  CompositionLocalProvider(LocalHaControlLock provides lock) { content() }
  if (lock.hasControls) {
    LockBadge(lock)
    UnlockStrip(knobSlot, knob, lock, hint)
  }
}

/** Where along its track the knob unlocks: the end, less a hair for the easing's last frame. */
private const val UnlockAt = 0.97f

/** The badge's size and its inset from the card's corner. */
private const val BadgeDp = 22
private const val BadgeInsetDp = 6

/** The strip's height: a finger's width, and Android's 48 dp touch target. */
private const val StripDp = MinTouchTargetDp

/** The badge in the card's top corner: a lock while locked, an open lock in the accent after. */
@Composable
@RemoteComposable
private fun LockBadge(lock: HaControlLock) {
  val theme = haTheme()
  val lockedVisible = lock.lockedVisibility
  val openVisible = lock.unlockedVisibility
  RemoteBox(
    modifier = RemoteModifier.fillMaxSize().padding(BadgeInsetDp.rdp),
    contentAlignment = RemoteAlignment.TopEnd,
  ) {
    RemoteBox(
      modifier =
        RemoteModifier.size(BadgeDp.rdp)
          .clip(haControlShape(BadgeDp / 2, round = true))
          .background(theme.cardBackground)
          .border(1.rdp, theme.divider, haControlShape(BadgeDp / 2, round = true)),
      contentAlignment = RemoteAlignment.Center,
    ) {
      RemoteBasicIcon(
        imageVector = Icons.Filled.Lock.toRemoteImageVector(),
        contentDescription = "Controls locked".rs,
        modifier = RemoteModifier.size(13.rdp).visibility(lockedVisible),
        tint = theme.secondaryText,
      )
      RemoteBasicIcon(
        imageVector = Icons.Filled.LockOpen.toRemoteImageVector(),
        contentDescription = "Controls unlocked".rs,
        modifier = RemoteModifier.size(13.rdp).visibility(openVisible),
        tint = haInk(theme.linkText),
      )
    }
  }
}

/**
 * HA-style slide-to-unlock: a pill across the bottom of the card with a round knob at its start and
 * "Slide to unlock" in its track. Dragging the knob moves it (a relative drag: the knob follows the
 * finger wherever the strip was touched); released past halfway it eases to the end and unlocks,
 * short of that it eases back. Shown once a locked control is touched, gone once unlocked.
 */
@Composable
@RemoteComposable
private fun UnlockStrip(
  knobSlot: ReservedId,
  knob: RemoteFloat,
  lock: HaControlLock,
  hint: MutableRemoteInt,
) {
  val theme = haTheme()
  val accent = haInk(theme.linkText)
  val shown = lock.stripVisibility
  RemoteBox(
    modifier = RemoteModifier.fillMaxSize().padding(HaSpace.M.rdp),
    contentAlignment = RemoteAlignment.BottomCenter,
  ) {
    RemoteBox(
      modifier =
        RemoteModifier.fillMaxWidth()
          // Hidden, it is also 0 dp tall: a gone component keeps taking the touches in its
          // bounds, which would hold back the card's own controls under it.
          .height(
            (lock.isUnlocked.select(0f.rf, hint.toRemoteFloat()) * StripDp.toFloat()).toRemoteDp()
          )
          .visibility(shown)
          .clip(haControlShape(StripDp / 2, round = true))
          .background(theme.cardBackground)
          .border(1.rdp, theme.divider, haControlShape(StripDp / 2, round = true)),
      contentAlignment = RemoteAlignment.Center,
    ) {
      RemoteText(
        text = "Slide to unlock".rs,
        color = theme.secondaryText,
        fontSize = HaType.Label,
        fontWeight = FontWeight.Medium,
        style = haTextStyle(),
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = RemoteModifier.padding(start = StripDp.rdp),
      )
      // Last, so it is on top and takes the touch: the text has no touch handler, but the canvas
      // must be the component that takes the touch-down for its drag to run.
      RemoteCanvas(
        modifier =
          RemoteModifier.fillMaxSize()
            // A touch-down handler makes the strip the component that takes the touch.
            .onTouchDown(valueChange(hint, 1.ri))
      ) {
        // Sized from the strip's height, so the knob scales with the capture density.
        val inset = height * 0.09f.rf
        val d = height - inset * 2f.rf
        val travel = max(width - d - inset * 2f.rf, 1f)
        defineTouch(
          knobSlot,
          initial = 0f,
          min = 0f,
          max = 1f,
          unitsPerPx = 1f.rf / travel,
          gate = null,
          stopAtEnds = true,
        )
        val x = inset + knob * travel
        // The trail the knob has covered, in a wash of the accent.
        drawRoundRect(
          RemotePaint { style = PaintingStyle.Fill }
            .apply { color = accent.copy(alpha = 0.22f.rf) },
          RemoteOffset(inset, inset),
          RemoteSize(x + d - inset, d),
          RemoteOffset(d / 2f.rf, d / 2f.rf),
        )
        val r = d / 2f.rf
        val cx = x + r
        val cy = inset + r
        drawCircle(
          RemotePaint { style = PaintingStyle.Fill }.apply { color = accent },
          r,
          RemoteOffset(cx, cy),
        )
        // A chevron in the knob: the way to slide.
        val chevron = RemotePaint {
          style = PaintingStyle.Stroke
          strokeWidth = height * 0.06f.rf
          strokeCap = StrokeCap.Round
        }
          .apply { color = theme.cardBackground }
        val s = r * 0.32f.rf
        drawLine(
          chevron,
          RemoteOffset(cx - s * 0.5f.rf, cy - s),
          RemoteOffset(cx + s * 0.5f.rf, cy),
        )
        drawLine(
          chevron,
          RemoteOffset(cx + s * 0.5f.rf, cy),
          RemoteOffset(cx - s * 0.5f.rf, cy + s),
        )
      }
    }
  }
}

// RemoteCompose component visibility (`Component.Visibility`).
private val Visible = 1.ri
private val Gone = 0.ri
