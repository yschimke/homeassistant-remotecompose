package ee.schimke.terrazzo.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.dp
import io.ktor.http.encodeURLParameter

const val PUBLIC_ISSUE_TRACKER = "https://github.com/yschimke/homeassistant-remotecompose/issues"

const val PUBLIC_FEEDBACK_WARNING =
  "This issue and anything you include will be PUBLIC on GitHub. " +
    "Do not include credentials, tokens, server URLs, entity IDs or names, or private Home Assistant details. " +
    "Only attach files or screenshots you explicitly choose and review for private information."

// Deliberately accepts no session, configuration, logs, or Home Assistant state.
fun feedbackIssueUrl(version: String, platform: String): String {
  val body =
    """
    |## Bug description
    |
    |## Steps to reproduce (optional)
    |1.
    |
    |## Expected behavior
    |
    |## Actual behavior
    |
    |## App context (review and edit)
    |App version: $version
    |Platform: $platform
    |
    |## Privacy reminder
    |$PUBLIC_FEEDBACK_WARNING
    |No Home Assistant data, logs, or attachments were automatically included.
  """
      .trimMargin()
  return "$PUBLIC_ISSUE_TRACKER/new?body=${body.encodeURLParameter()}"
}

internal expect val feedbackPlatform: String

/** Returns false when the host cannot open a browser (including blocked web popups). */
internal expect fun openFeedbackUrl(url: String, uriHandler: UriHandler): Boolean

/** Opening a draft never submits it; GitHub supplies the editable review and submit step. */
@Composable
fun ReportFeedbackButton(
  version: String = FeedbackBuildInfo.VERSION,
  platform: String = feedbackPlatform,
) {
  var reviewing by remember { mutableStateOf(false) }
  var failed by remember { mutableStateOf(false) }
  val uriHandler = LocalUriHandler.current
  TextButton(
    onClick = {
      reviewing = true
      failed = false
    }
  ) {
    Text("Report a bug")
  }
  if (reviewing) {
    AlertDialog(
      onDismissRequest = { reviewing = false },
      title = { Text("Report a PUBLIC issue") },
      text = {
        Column(
          Modifier.verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          if (failed) {
            Text(
              "Could not open GitHub. Allow popups or copy this address into a browser, then choose New issue:",
              color = MaterialTheme.colorScheme.error,
            )
            SelectionContainer { Text(PUBLIC_ISSUE_TRACKER) }
          }
          Text(PUBLIC_FEEDBACK_WARNING)
          Text(
            "Included: app version $version; platform $platform. No Home Assistant data or attachments are included."
          )
          Text(
            "Continue to GitHub to edit the draft, optionally add reproduction steps and expected/actual behavior, and explicitly submit it. A GitHub account is required."
          )
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            failed = !openFeedbackUrl(feedbackIssueUrl(version, platform), uriHandler)
            if (!failed) reviewing = false
          }
        ) {
          Text(if (failed) "Try GitHub again" else "Review on GitHub")
        }
      },
      dismissButton = { TextButton(onClick = { reviewing = false }) { Text("Cancel") } },
    )
  }
}
