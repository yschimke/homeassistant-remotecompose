package ee.schimke.terrazzo.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.DashboardSummary

/**
 * Top-app-bar title slot for the dashboard view. Renders the active dashboard's name and, when
 * there are 2+ dashboards, a chevron that opens a [DropdownMenu] with the other dashboards as 1-tap
 * switch targets.
 *
 * This is the chrome the persona-2 (2–3 favourites) user pays per switch: 2 taps total — chevron +
 * favourite. Persona 1 (single dashboard) sees plain text, no chevron, no dropdown — chrome scales
 * with what's available.
 *
 * Settings / widgets / sign-out live behind a separate overflow menu in the top-bar's actions slot
 * ([TopBarOverflowMenu]) so the dashboards dropdown stays focused on dashboard switching.
 */
@Composable
fun DashboardSwitcher(
  dashboards: List<DashboardSummary>,
  currentUrlPath: String?,
  onSwitch: (urlPath: String?) -> Unit,
  modifier: Modifier = Modifier,
) {
  val current = dashboards.firstOrNull { it.urlPath == currentUrlPath }
  val title = current?.title ?: "Dashboard"
  val canSwitch = dashboards.size >= 2

  if (!canSwitch) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleLarge,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 1,
      softWrap = false,
      overflow = TextOverflow.Ellipsis,
      modifier = modifier,
    )
    return
  }

  var expanded by remember { mutableStateOf(false) }
  Box(modifier = modifier) {
    // The top bar's actions leave the title a phone-width remainder, so the name ellipsizes on one
    // line and gives up width before the chevron does (`weight(fill = false)` measures the text
    // after the icon has claimed its width). The chevron is 18 dp and sits tight to the text, so it
    // costs the title as little as possible; the whole button stays the click target.
    TextButton(
      onClick = { expanded = true },
      contentPadding = PaddingValues(start = 4.dp, end = 0.dp),
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
      )
      Icon(
        imageVector = Icons.Filled.ArrowDropDown,
        contentDescription = "Switch dashboard",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(18.dp),
      )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      dashboards.forEach { d ->
        DropdownMenuItem(
          text = { Text(d.title) },
          leadingIcon = {
            if (d.urlPath == currentUrlPath) {
              Icon(Icons.Filled.Check, contentDescription = "Currently open")
            } else {
              // 24 dp keeps the labels left-aligned to a
              // consistent column whether or not the row
              // has a leading checkmark.
              Spacer(Modifier.size(24.dp))
            }
          },
          onClick = {
            expanded = false
            if (d.urlPath != currentUrlPath) onSwitch(d.urlPath)
          },
        )
      }
    }
  }
}
