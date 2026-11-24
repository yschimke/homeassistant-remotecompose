package ee.schimke.terrazzo.shared

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay

/** Android-only destinations remain host-provided slots in the common navigation layer. */
enum class AppScreen : NavKey {
  Dashboards,
  Settings,
  Widgets,
  SyncDiagnostics,
  Logs,
  ChooseDashboards,
  Room,
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TerrazzoNavigation(
  backStack: List<AppScreen>,
  onBack: () -> Unit,
  content: @Composable (AppScreen) -> Unit,
) {
  val motionScheme = MaterialTheme.motionScheme
  NavDisplay(
    backStack = backStack,
    onBack = onBack,
    transitionSpec = {
      slideInHorizontally(motionScheme.defaultSpatialSpec()) { it / 6 } +
        fadeIn(motionScheme.defaultEffectsSpec()) togetherWith
        slideOutHorizontally(motionScheme.defaultSpatialSpec()) { -it / 12 } +
          fadeOut(motionScheme.fastEffectsSpec())
    },
    popTransitionSpec = {
      slideInHorizontally(motionScheme.defaultSpatialSpec()) { -it / 12 } +
        fadeIn(motionScheme.defaultEffectsSpec()) togetherWith
        slideOutHorizontally(motionScheme.defaultSpatialSpec()) { it / 6 } +
          fadeOut(motionScheme.fastEffectsSpec())
    },
    predictivePopTransitionSpec = {
      slideInHorizontally(motionScheme.defaultSpatialSpec()) { -it / 12 } +
        fadeIn(motionScheme.defaultEffectsSpec()) togetherWith
        scaleOut(motionScheme.defaultSpatialSpec(), targetScale = 0.92f) +
          fadeOut(motionScheme.fastEffectsSpec())
    },
    entryProvider = entryProvider { entry<AppScreen> { HiddenWhileLeaving { content(it) } } },
  )
}

/**
 * A [NavDisplay] entry, hidden from accessibility (and UI tests) while its transition takes it off
 * screen. The outgoing and incoming entries are composed together for the length of the fade, so
 * without this a screen reader, or a test looking for "Switch dashboard", meets both screens'
 * copies of every control at once.
 */
@Composable
fun HiddenWhileLeaving(content: @Composable () -> Unit) {
  val leaving =
    LocalNavAnimatedContentScope.current.transition.targetState != EnterExitState.Visible
  Box(
    modifier = if (leaving) Modifier.clearAndSetSemantics {} else Modifier,
    propagateMinConstraints = true,
  ) {
    content()
  }
}
