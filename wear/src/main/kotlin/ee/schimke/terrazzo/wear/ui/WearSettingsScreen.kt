package ee.schimke.terrazzo.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import ee.schimke.terrazzo.shared.PUBLIC_FEEDBACK_WARNING
import ee.schimke.terrazzo.shared.PUBLIC_ISSUE_TRACKER
import ee.schimke.terrazzo.shared.feedbackIssueUrl
import ee.schimke.terrazzo.wearsync.proto.WearSettings

/**
 * Wear settings: which phone the watch follows, and a bug report. Nothing is configured here: what
 * the watch shows and how it looks are chosen on the phone ([onOpenOnPhone]).
 */
@Composable
fun WearSettingsScreen(
  settings: WearSettings,
  modifier: Modifier = Modifier,
  onOpenOnPhone: (() -> Unit)? = null,
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
      spacing = 8.dp,
    ) { spec ->
      item {
        ListHeader(
          modifier = wearHeaderModifier(spec),
          transformation = SurfaceTransformation(spec),
        ) {
          Text("PUBLIC issue")
        }
      }
      item {
        Text(
          PUBLIC_FEEDBACK_WARNING,
          style = MaterialTheme.typography.bodySmall,
          modifier = wearTextModifier(spec),
        )
      }
      item {
        Text(
          "Included: app version $version; Wear OS. No Home Assistant data or attachments. Review, edit and explicitly submit on GitHub (account required).",
          modifier = wearTextModifier(spec),
        )
      }
      if (failed) {
        item {
          Text(
            "Could not open GitHub. Open this address on your phone and choose New issue:",
            modifier = wearTextModifier(spec),
          )
        }
        item {
          SelectionContainer(modifier = wearTextModifier(spec)) { Text(PUBLIC_ISSUE_TRACKER) }
        }
      }
      item {
        Text(
          "PUBLIC on GitHub. Never include credentials or private Home Assistant details.",
          style = MaterialTheme.typography.bodySmall,
          modifier = wearTextModifier(spec),
        )
      }
      item {
        Button(
          onClick = {
            failed =
              runCatching { uriHandler.openUri(feedbackIssueUrl(version, "Wear OS")) }.isFailure
            if (!failed) reporting = false
          },
          modifier = wearButtonModifier(spec),
          transformation = SurfaceTransformation(spec),
        ) {
          Text(if (failed) "Retry PUBLIC draft" else "Review PUBLIC draft")
        }
      }
      item {
        Button(
          onClick = { reporting = false },
          modifier = wearButtonModifier(spec),
          transformation = SurfaceTransformation(spec),
        ) {
          Text("Cancel")
        }
      }
    }
    return
  }
  WearListScreen(modifier = modifier) { spec ->
    // The screen's title first; reporting a bug is a secondary action, so it sits at the end.
    item {
      ListHeader(
        modifier = wearHeaderModifier(spec),
        transformation = SurfaceTransformation(spec),
      ) {
        Text("Settings")
      }
    }
    item {
      Text(
        "What your watch shows, its theme and card designs are all chosen on your phone.",
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        modifier = wearTextModifier(spec).fillMaxWidth().padding(horizontal = 12.dp),
      )
    }
    onOpenOnPhone?.let { open ->
      item {
        OpenOnPhoneButton(
          open,
          modifier = wearButtonModifier(spec),
          transformation = SurfaceTransformation(spec),
        )
      }
    }

    item {
      ListHeader(
        modifier = wearHeaderModifier(spec),
        transformation = SurfaceTransformation(spec),
      ) {
        Text("Phone")
      }
    }
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
        modifier = wearTextModifier(spec).padding(horizontal = 12.dp, vertical = 4.dp),
      )
    }

    item {
      Button(
        onClick = {
          reporting = true
          failed = false
        },
        modifier = wearButtonModifier(spec),
        transformation = SurfaceTransformation(spec),
        colors = ButtonDefaults.filledTonalButtonColors(),
      ) {
        Text("Report a bug")
      }
    }
  }
}
