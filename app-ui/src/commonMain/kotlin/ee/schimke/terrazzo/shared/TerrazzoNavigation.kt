package ee.schimke.terrazzo.shared

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay

/** Android-only destinations remain host-provided slots in the common navigation layer. */
enum class AppScreen : NavKey {
  Dashboards,
  Settings,
  Widgets,
  Pinned,
  WearWidgets,
  SyncDiagnostics,
  Logs,
  ChooseDashboards,
}

@Composable
fun TerrazzoNavigation(
  screen: AppScreen,
  onBack: () -> Unit,
  content: @Composable (AppScreen) -> Unit,
) {
  NavDisplay(
    backStack = listOf(screen),
    onBack = onBack,
    transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
    popTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
    predictivePopTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
    entryProvider = entryProvider { entry<AppScreen> { content(it) } },
  )
}
