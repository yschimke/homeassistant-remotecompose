package ee.schimke.terrazzo.wearsync

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import ee.schimke.terrazzo.LocalTerrazzoGraph
import ee.schimke.terrazzo.TerrazzoApplication
import kotlinx.coroutines.launch

/** A one-time dashboard prompt toward the watch, keyed so each is offered once. */
private sealed interface WearPrompt {
  val key: String
  val message: String
  val action: String

  /** A connected watch doesn't have the watch app. Keyed per watch. */
  data class InstallApp(val watch: PairedWatch) : WearPrompt {
    override val key: String = "install:${watch.nodeId}"
    override val message: String = "Terrazzo is available for ${watch.displayName}"
    override val action: String = "Install"
  }

  /** The watch app supports Wear widgets, but no slot has a card yet. */
  data object AddWidget : WearPrompt {
    override val key: String = "widget"
    override val message: String = "Show a card as a widget on your watch"
    override val action: String = "Set up"
  }
}

/**
 * Offers the watch on the dashboard: a snackbar to install the watch app on a connected watch
 * without it ([WearAppInstallHelper]), or — once installed on a watch with Wear widget support and
 * no widget slot assigned — to set one up ([onOpenWearWidgets]). Each prompt shows once; dismissed
 * or acted on, it's recorded in [ee.schimke.terrazzo.core.prefs.PreferencesStore.wearPromptsShown].
 *
 * Returns the install action for the overflow menu's "Install on watch" while a connected watch
 * lacks the app, so the offer stays reachable after its snackbar is gone; null otherwise.
 */
@Composable
fun wearInstallPrompts(
  snackbars: SnackbarHostState,
  wearWidgetsSupported: Boolean,
  onOpenWearWidgets: () -> Unit,
): (() -> Unit)? {
  val context = LocalContext.current
  val app = remember(context) { context.applicationContext as TerrazzoApplication }
  val graph = LocalTerrazzoGraph.current
  val scope = rememberCoroutineScope()
  val watches by app.wearAppInstallHelper.watches.collectAsState(initial = emptyList())
  val slots by graph.wearWidgetSlotsStore.slots.collectAsState(initial = null)
  val shown by graph.preferencesStore.wearPromptsShown.collectAsState(initial = null)
  val missing = watches.firstOrNull { !it.appInstalled }

  val install: (PairedWatch) -> Unit = { watch ->
    scope.launch {
      val sent = app.wearAppInstallHelper.installOnWatch(watch.nodeId)
      snackbars.showSnackbar(
        if (sent) "Continue on ${watch.displayName} to install"
        else "Couldn't reach ${watch.displayName}"
      )
    }
  }

  val prompt: WearPrompt? = shown?.let { done ->
    when {
      missing != null -> WearPrompt.InstallApp(missing)
      wearWidgetsSupported && slots?.none { it.isAssigned } == true -> WearPrompt.AddWidget
      else -> null
    }?.takeIf { it.key !in done }
  }
  LaunchedEffect(prompt?.key) {
    val current = prompt ?: return@LaunchedEffect
    val result =
      snackbars.showSnackbar(
        message = current.message,
        actionLabel = current.action,
        withDismissAction = true,
        duration = SnackbarDuration.Long,
      )
    // Act before recording: the record flips `prompt`, which cancels this effect.
    if (result == SnackbarResult.ActionPerformed) {
      when (current) {
        is WearPrompt.InstallApp -> install(current.watch)
        WearPrompt.AddWidget -> onOpenWearWidgets()
      }
    }
    graph.preferencesStore.markWearPromptShown(current.key)
  }

  return missing?.let { watch -> { install(watch) } }
}
