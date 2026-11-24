package ee.schimke.terrazzo.shared

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
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
    entryProvider = entryProvider { entry<AppScreen> { content(it) } },
  )
}
