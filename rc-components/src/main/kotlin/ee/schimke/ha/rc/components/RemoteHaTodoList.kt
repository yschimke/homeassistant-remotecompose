@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.remote.creation.compose.capture.toRemoteImageVector
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.modifier.width
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.remote.material3.RemoteIcon

/**
 * `todo-list` card — title + active items section + completed items section. Items are rendered as
 * static rows; toggling a checkbox inside the .rc document needs the todo-list service-call channel
 * (alpha08-compatible follow-up).
 *
 * [compactRows] is the short form for a Wear Large slot (about 88 dp of content), too short for a
 * section caption and 48 dp rows under the title: the title over at most that many items in shorter
 * rows, the open ones first, and no section captions (they repeat what the checkboxes say), so no
 * row is half-drawn at the bottom of the slot.
 */
@Composable
@RemoteComposable
fun RemoteHaTodoList(
  data: HaTodoListData,
  modifier: RemoteModifier = RemoteModifier,
  compactRows: Int? = null,
) {
  val theme = haTheme()
  if (compactRows != null) {
    CompactTodoList(data, compactRows, modifier, theme)
    return
  }
  RemoteBox(
    modifier =
      modifier
        .fillMaxWidth()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 14.rdp, vertical = 12.rdp)
  ) {
    RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(8.rdp)) {
      RemoteText(
        text = cardTitle(data.title),
        color = theme.primaryText,
        fontSize = HaType.Title,
        fontWeight = FontWeight.Medium,
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )

      if (data.activeItems.isNotEmpty()) {
        Section("Active", theme)
        // Item rows are full touch targets stacked edge to edge (HA's 48px list rows), so the
        // column's 8dp gap sits between sections, not between items.
        RemoteColumn {
          data.activeItems.forEachIndexed { i, item ->
            Row("active.$i", item, completed = false, theme = theme)
          }
        }
      }
      if (data.completedItems.isNotEmpty()) {
        Section("Completed", theme)
        RemoteColumn {
          data.completedItems.forEachIndexed { i, item ->
            Row("done.$i", item, completed = true, theme = theme)
          }
        }
      }
      // Note: an empty stub when both sections empty.
      if (data.activeItems.isEmpty() && data.completedItems.isEmpty()) {
        if (visualFamily() == HaVisualFamily.Console)
          ConsoleTapeLabel("No items".rs, compact = false)
        else
          RemoteText(
            text = "No items".rs,
            color = theme.secondaryText,
            fontSize = HaType.Label,
            style = haTextStyle(),
          )
      }
    }
  }
}

/**
 * Identity tier for the `todo-list` family — a big "N left" counter + the list title. The smallest
 * cell that still says "this is a to-do list and here's how much is outstanding"; drops the item
 * rows (P5) but keeps the P1 identity (the count). Used by the Fixed-mode converter at narrow
 * launcher / Wear cells; see docs/architecture/adaptive-card-layouts.md §"Bulk / time-series".
 */
@Composable
@RemoteComposable
fun RemoteHaTodoListIdentity(data: HaTodoListData, modifier: RemoteModifier = RemoteModifier) {
  val theme = haTheme()
  val remaining = data.activeItems.size
  RemoteBox(
    modifier =
      modifier
        .fillMaxSize()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 14.rdp, vertical = 12.rdp),
    contentAlignment = RemoteAlignment.Center,
  ) {
    RemoteColumn(
      modifier = RemoteModifier.fillMaxWidth(),
      verticalArrangement = RemoteArrangement.spacedBy(2.rdp),
    ) {
      RemoteText(
        text = data.title.rs,
        color = theme.secondaryText,
        fontSize = HaType.Label,
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      RemoteText(
        text = (if (remaining == 0) "All done" else "$remaining left").rs,
        color = theme.primaryText,
        fontSize = HaType.Value,
        fontWeight = haValueWeight(),
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun CompactTodoList(
  data: HaTodoListData,
  rows: Int,
  modifier: RemoteModifier,
  theme: RemoteHaTheme,
) {
  // The same `active.<i>` / `done.<i>` keys as the full list, so a template binds the same rows.
  val shown =
    data.activeItems.mapIndexed { i, item -> Triple("active.$i", item, false) } +
      data.completedItems.mapIndexed { i, item -> Triple("done.$i", item, true) }
  RemoteBox(
    modifier =
      modifier
        .fillMaxWidth()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 14.rdp, vertical = 6.rdp)
  ) {
    RemoteColumn {
      RemoteText(
        text = cardTitle(data.title),
        color = theme.primaryText,
        fontSize = HaType.Title,
        fontWeight = FontWeight.Medium,
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      shown.take(rows).forEach { (key, item, completed) ->
        Row(key, item, completed, theme, heightDp = CompactRowDp)
      }
      if (shown.isEmpty())
        RemoteText(
          text = "No items".rs,
          color = theme.secondaryText,
          fontSize = HaType.Label,
          style = haTextStyle(),
        )
    }
  }
}

/** A compact list's row: the checkbox and a `Body` line with a little air, not a 48 dp target. */
private const val CompactRowDp = 30

@Composable
private fun Section(label: String, theme: RemoteHaTheme) {
  RemoteText(
    text = label.rs,
    color = theme.secondaryText,
    fontSize = HaType.Caption,
    fontWeight = FontWeight.Medium,
    style = haTextStyle(),
  )
}

@Composable
private fun Row(
  key: String,
  item: HaTodoItem,
  completed: Boolean,
  theme: RemoteHaTheme,
  heightDp: Int = MinTouchTargetDp,
) {
  val summary = cardText("$key.summary", item.summary)
  val click =
    item.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
  // The player's tap area excludes padding, so the row takes its 48dp touch-target height from a
  // zero-width strut rather than from padding.
  RemoteRow(
    modifier = RemoteModifier.then(click).fillMaxWidth(),
    verticalAlignment = RemoteAlignment.CenterVertically,
  ) {
    RemoteBox(modifier = RemoteModifier.width(0.rdp).height(heightDp.rdp))
    RemoteBox(
      modifier =
        RemoteModifier.size((if (visualFamily() == HaVisualFamily.Console) 22 else 18).rdp)
          .then(consoleKeyChrome()),
      contentAlignment = RemoteAlignment.Center,
    ) {
      RemoteIcon(
        imageVector =
          (if (completed) Icons.Filled.CheckBox else Icons.Outlined.CheckBoxOutlineBlank)
            .toRemoteImageVector(),
        contentDescription = summary,
        modifier = RemoteModifier.size(18.rdp),
        tint = if (completed) theme.placeholderAccent else theme.secondaryText,
      )
    }

    RemoteBox(modifier = RemoteModifier.padding(start = 10.rdp)) {
      RemoteText(
        text = summary,
        color = if (completed) theme.secondaryText else theme.primaryText,
        fontSize = HaType.Body,
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}
