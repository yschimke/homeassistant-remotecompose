package ee.schimke.terrazzo.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import ee.schimke.terrazzo.shared.PUBLIC_FEEDBACK_WARNING
import ee.schimke.terrazzo.shared.PUBLIC_ISSUE_TRACKER
import ee.schimke.terrazzo.shared.feedbackIssueUrl
import ee.schimke.terrazzo.wearsync.proto.WearSettings

/** Wear settings: widget cards and phone connection details. Appearance follows mobile. */
@Composable
fun WearSettingsScreen(
  settings: WearSettings,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  onOpenWidgets: (() -> Unit)? = null,
) {
  var reporting by remember { mutableStateOf(false) }
  var failed by remember { mutableStateOf(false) }
  val uriHandler = LocalUriHandler.current
  val context = LocalContext.current
  androidx.activity.compose.BackHandler(enabled = reporting) { reporting = false }
  if (reporting) {
    val version =
      remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "Unknown"
      }
    WearListScreen(
      modifier = modifier,
      state = rememberScalingLazyListState(initialCenterItemIndex = 0),
      spacing = 8.dp,
    ) {
      item { ListHeader { Text("PUBLIC issue") } }
      item { Text(PUBLIC_FEEDBACK_WARNING, style = MaterialTheme.typography.bodySmall) }
      item {
        Text(
          "Included: app version $version; Wear OS. No Home Assistant data or attachments. Review, edit and explicitly submit on GitHub (account required)."
        )
      }
      if (failed) {
        item {
          Text("Could not open GitHub. Open this address on your phone and choose New issue:")
        }
        item { SelectionContainer { Text(PUBLIC_ISSUE_TRACKER) } }
      }
      item {
        Text(
          "PUBLIC on GitHub. Never include credentials or private Home Assistant details.",
          style = MaterialTheme.typography.bodySmall,
        )
      }
      item {
        Button(
          onClick = {
            failed =
              runCatching { uriHandler.openUri(feedbackIssueUrl(version, "Wear OS")) }.isFailure
            if (!failed) reporting = false
          }
        ) {
          Text(if (failed) "Retry PUBLIC draft" else "Review PUBLIC draft")
        }
      }
      item { Button(onClick = { reporting = false }) { Text("Cancel") } }
    }
    return
  }
  WearListScreen(modifier = modifier) {
    // The screen's title first; reporting a bug is a secondary action, so it sits at the end.
    item { ListHeader { Text("Settings") } }
    // Choosing each placed widget's card: on watches that show Glance Wear widgets.
    onOpenWidgets?.let { open ->
      item {
        Button(
          onClick = open,
          modifier = Modifier.fillMaxWidth(),
          colors = ButtonDefaults.filledTonalButtonColors(),
        ) {
          Text("Widgets")
        }
      }
    }
    item {
      Text("Theme and card designs follow your phone.", style = MaterialTheme.typography.bodySmall)
    }

    item { ListHeader { Text("Phone") } }
    item {
      Text(
        text =
          if (settings.demoMode) {
            "Demo mode (offline)"
          } else if (settings.baseUrl.isNotEmpty()) {
            settings.baseUrl
          } else {
            "Not connected"
          },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
      )
    }

    item {
      Button(
        onClick = {
          reporting = true
          failed = false
        },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(),
      ) {
        Text("Report a bug")
      }
    }
    item {
      Button(
        onClick = onBack,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(),
      ) {
        Text("Back")
      }
    }
  }
}
