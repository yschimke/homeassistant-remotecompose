package ee.schimke.terrazzo.wear.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.pager.VerticalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AnimatedPage
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.VerticalPagerScaffold
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureAction
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureClickIndicator
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureClickIndicatorState
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureDefaults
import androidx.wear.compose.material3.onehandedgesture.OneHandedGesturePageIndicatorState
import androidx.wear.compose.material3.onehandedgesture.OneHandedGesturePriority
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureVerticalPageIndicator
import androidx.wear.compose.material3.onehandedgesture.rememberOneHandedGestureConfiguration
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.CardRegistry
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.cards.shutter.withEnhancedShutter
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import kotlinx.coroutines.launch

/**
 * A card full screen: [cards] as a vertical pager, opened on [initialPage]. Reached by a long press
 * on a card (or a tap it doesn't take, or its more-info tap), and by a tap on its Wear widget.
 *
 * A long press on the card, a tap it doesn't take, or its more-info tap opens its details
 * ([onOpenDetails], [WearCardDetailsScreen]); so does the edge button of a card with no action.
 *
 * Each page draws its card in the Large widget frame ([wearFullScreenContainer]) over an
 * [EdgeButton] for the card's own action ([wearCardPrimaryAction]): "Turn off" for a light. That
 * button is the page's one-handed gesture target, so a double pinch runs the action the button
 * names, with the same feedback as a tap. A page with no action to run takes the gesture as the
 * next page instead. The dismiss gesture (a wrist turn) stays the system Back to the list.
 */
@Composable
internal fun WearCardScreen(
  cards: List<CardSummary>,
  initialPage: Int,
  values: Map<String, EntityValue>,
  modifier: Modifier = Modifier,
  onOpenDetails: (CardSummary) -> Unit = {},
) {
  val pager =
    rememberPagerState(initialPage.coerceIn(0, (cards.size - 1).coerceAtLeast(0))) { cards.size }
  val pageGesture =
    rememberOneHandedGestureConfiguration(
      action = OneHandedGestureAction.Primary,
      priority = OneHandedGesturePriority.Scrollable,
    )
  val pageHint = remember(pageGesture) { OneHandedGesturePageIndicatorState() }
  val scope = rememberCoroutineScope()
  val pageGestureModifier =
    rememberOneHandedGesture(
      gestureConfiguration = pageGesture,
      onGestureLabel = "Next card",
      onGestureAvailable = { scope.launch { pageHint.showIndicator() } },
      onGesture = { OneHandedGestureDefaults.scrollToNextPage(pager) },
    )
  VerticalPagerScaffold(
    pagerState = pager,
    modifier = modifier,
    pageIndicator = { OneHandedGestureVerticalPageIndicator(pageGesture, pageHint, pager) },
  ) {
    VerticalPager(
      state = pager,
      modifier = Modifier.fillMaxSize().then(if (cards.size < 2) Modifier else pageGestureModifier),
    ) { page ->
      AnimatedPage(page, pager) {
        val summary = cards[page]
        WearCardPage(
          summary,
          rememberCardValues(summary.rawJson, values),
          onOpenDetails = { onOpenDetails(summary) },
        )
      }
    }
  }
}

@Composable
private fun WearCardPage(
  summary: CardSummary,
  values: Map<String, EntityValue>,
  onOpenDetails: () -> Unit,
) {
  val registry = remember { defaultRegistry().withEnhancedShutter() }
  val card = remember(summary.rawJson) { decodeWearCard(summary.rawJson) }
  val snapshot = remember(values) { wearSnapshot(values) }
  // The card's more-info tap opens its details, as a long press does.
  val taps = rememberWearCardTaps(summary.rawJson, onMoreInfo = onOpenDetails)
  val action = card?.let { wearCardPrimaryAction(it, snapshot, registry) }
  Box(Modifier.fillMaxSize()) {
    // The card takes everything between the time text and the edge button, scaled to fit.
    Box(
      Modifier.fillMaxSize().padding(top = TIME_TEXT_HEIGHT, bottom = EDGE_BUTTON_HEIGHT),
      contentAlignment = Alignment.Center,
    ) {
      WearRemoteCard(summary, values, onOpen = onOpenDetails, fullScreen = true, taps = taps)
    }
    if (action != null) {
      CardActionButton(
        label = wearCardActionLabel(action, snapshot),
        enabled = taps.enabled(),
        onRun = { taps.dispatcher.dispatch(action) },
        modifier = Modifier.align(Alignment.BottomCenter),
      )
    } else {
      // No action to run: the edge button opens the history instead. A card with an action
      // reaches it by a long press, a tap it doesn't take or its more-info tap.
      EdgeButton(
        onClick = onOpenDetails,
        modifier = Modifier.align(Alignment.BottomCenter),
        buttonSize = EdgeButtonSize.ExtraSmall,
        colors = ButtonDefaults.filledTonalButtonColors(),
      ) {
        Text("History")
      }
    }
  }
}

/** Room the app's time text takes at the top of a page. */
private val TIME_TEXT_HEIGHT = 24.dp

/** Room an extra-small edge button takes at the bottom of a page. */
private val EDGE_BUTTON_HEIGHT = 50.dp

/**
 * The card's action as a button, and the page's one-handed gesture target: a double pinch presses
 * it, drawn as a press on the button through the shared interaction source. Disabled as a gesture
 * while the phone doesn't allow watch controls; the tap still says how to allow them.
 */
@Composable
private fun CardActionButton(
  label: String,
  enabled: Boolean,
  onRun: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val gesture =
    rememberOneHandedGestureConfiguration(
      action = OneHandedGestureAction.Primary,
      priority = OneHandedGesturePriority.Clickable,
    )
  val hint = remember(gesture) { OneHandedGestureClickIndicatorState() }
  val interactions = remember { MutableInteractionSource() }
  val scope = rememberCoroutineScope()
  val gestureModifier =
    rememberOneHandedGesture(
      gestureConfiguration = gesture,
      onGestureLabel = label,
      interactionSource = interactions,
      onGestureAvailable = { scope.launch { hint.showIndicator() } },
      onGesture = { onRun() },
    )
  EdgeButton(
    onClick = onRun,
    modifier = modifier.then(if (enabled) gestureModifier else Modifier),
    buttonSize = EdgeButtonSize.ExtraSmall,
    interactionSource = interactions,
  ) {
    OneHandedGestureClickIndicator(gesture, hint) {
      Text(label, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
  }
}

/**
 * The action the full-screen [card]'s button runs: the card's own whole-card action, from its local
 * config ([CardDesignTemplates.primaryAction] of its design card, the action a tap on the card
 * runs), when it is one the watch can run. Null for an action that only shows something (more-info,
 * navigate, a URL) and for an alarm panel, whose arm and disarm need the code typed on the card.
 *
 * High-risk actions (a lock, a garage door) need no exception here: the phone runs a watch's tap
 * only while its controls are unlocked, which is what lets the watch send one at all.
 */
internal fun wearCardPrimaryAction(
  card: CardConfig,
  snapshot: HaSnapshot,
  registry: CardRegistry,
): HaAction? =
  when (
    val action =
      CardDesignTemplates.primaryAction(CardDesignTemplates.designCard(card, snapshot, registry))
  ) {
    is HaAction.Toggle,
    is HaAction.CallService -> action
    else -> null
  }

/** The button's label for [action] in [snapshot]: "Turn off" for a light that is on. */
internal fun wearCardActionLabel(action: HaAction, snapshot: HaSnapshot): String =
  when (action) {
    is HaAction.Toggle ->
      when (snapshot.states[action.entityId]?.state) {
        "on",
        "open",
        "unlocked" -> "Turn off"
        "off",
        "closed",
        "locked" -> "Turn on"
        else -> "Toggle"
      }
    is HaAction.CallService ->
      when (action.service) {
        "turn_on" -> "Turn on"
        "turn_off" -> "Turn off"
        "toggle" -> "Toggle"
        "unlock" -> "Unlock"
        "lock" -> "Lock"
        "open_cover" -> "Open"
        "close_cover" -> "Close"
        "press" -> "Press"
        else -> action.service.replace('_', ' ').replaceFirstChar { it.uppercase() }
      }
    else -> "Run"
  }
