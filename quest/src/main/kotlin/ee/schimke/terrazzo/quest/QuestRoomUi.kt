package ee.schimke.terrazzo.quest

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meta.spatial.uiset.button.BorderlessButton
import com.meta.spatial.uiset.control.SpatialSwitch
import com.meta.spatial.uiset.theme.SpatialTheme
import com.meta.spatial.uiset.theme.darkSpatialColorScheme
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.room.RoomEntity
import ee.schimke.terrazzo.room.LostPins
import ee.schimke.terrazzo.room.RoomContent
import ee.schimke.terrazzo.room.RoomHeader
import ee.schimke.terrazzo.room.RoomPin

/**
 * The room panels in Meta's Horizon OS look: the Spatial SDK's UI set, dark. The shared room parts
 * (`RoomHeader`, `LostPins`) are Material 3, so a dark Material scheme keeps them legible on the
 * same dark panel.
 */
@Composable
fun QuestTheme(content: @Composable () -> Unit) {
  SpatialTheme(darkSpatialColorScheme()) { MaterialTheme(darkColorScheme()) { content() } }
}

/**
 * A pinned control on its own panel: the card on the panel's background, filling it, with **Unpin**
 * ([onUnpin], null to leave it out) to take the pin down.
 */
@Composable
fun QuestPinnedPanel(
  entity: RoomEntity,
  snapshot: HaSnapshot?,
  onToggle: (entityId: String) -> Unit,
  onUnpin: (() -> Unit)?,
  controlsLocked: Boolean = true,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier.fillMaxSize().background(SpatialTheme.colorScheme.panel, RoundedCornerShape(24.dp)),
    contentAlignment = Alignment.Center,
  ) {
    QuestDeviceCard(listOf(entity), snapshot, controlsLocked, onToggle, null, emptySet(), onUnpin)
  }
}

/** Device visibility switches and settings for the current room. */
@Composable
fun QuestRoomPanel(
  content: RoomContent,
  visibleDevices: Set<String> = emptySet(),
  onDisplayDevice: (String, Boolean) -> Unit = { _, _ -> },
  lostPins: List<RoomPin> = emptyList(),
  onRemoveLost: (RoomPin) -> Unit = {},
  onSignOut: () -> Unit = {},
  controlsLocked: Boolean = true,
  onControlsLocked: (Boolean) -> Unit = {},
  pinningHint: String? = null,
  modifier: Modifier = Modifier,
) {
  var settings by rememberSaveable { mutableStateOf(false) }
  val controls = content.state?.controls
  val groups = content.deviceGroups()
  LazyVerticalGrid(
    columns = GridCells.Adaptive(300.dp),
    modifier = modifier.fillMaxSize().background(SpatialTheme.colorScheme.panel),
    contentPadding = PaddingValues(32.dp),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    item(span = { GridItemSpan(maxLineSpan) }) {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
          controls?.area?.name ?: "This room",
          style = SpatialTheme.typography.headline1Strong,
          color = QuestText,
        )
        NamedButton(if (settings) "Close room settings" else "Room settings", "Room settings") {
          settings = !settings
        }
        if (settings) {
          RoomHeader(content)
          Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text("Controls", style = SpatialTheme.typography.body1Strong, color = QuestText)
              Text(
                if (controlsLocked) "Locked — view only" else "Unlocked — actions are live",
                style = SpatialTheme.typography.body2,
                color = QuestTextSecondary,
              )
            }
            SpatialSwitch(
              !controlsLocked,
              { enabled -> onControlsLocked(!enabled) },
              Modifier.semantics {
                contentDescription =
                  if (controlsLocked) "Unlock Home Assistant controls"
                  else "Lock Home Assistant controls"
              },
            )
          }
          pinningHint?.let { hint ->
            Text(
              hint,
              style = SpatialTheme.typography.body2,
              color = QuestTextSecondary,
            )
          }
          NamedButton("Sign out", "Sign out of Home Assistant", onSignOut)
        }
      }
    }
    items(groups.keys.toList(), key = { it }) { id ->
      val label = groups.getValue(id).first().name
      Row(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(label, modifier = Modifier.weight(1f), color = QuestText)
        SpatialSwitch(
          id in visibleDevices,
          { onDisplayDevice(id, it) },
          Modifier.semantics { contentDescription = "Display card for $label" },
        )
      }
    }
    if (groups.isEmpty()) item { Text("No devices in this room", color = QuestTextSecondary) }
    if (settings && lostPins.isNotEmpty()) {
      item(span = { GridItemSpan(maxLineSpan) }) {
        LostPins(lostPins, content.snapshot, onRemoveLost)
      }
    }
  }
}

/** First-run panel. OAuth itself runs in Home Assistant's browser page, never inside the app. */
@Composable
fun QuestSignInPanel(
  connection: QuestConnection,
  onSignIn: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var url by rememberSaveable { mutableStateOf("http://homeassistant.local:8123/") }
  Column(
    modifier.fillMaxSize().background(SpatialTheme.colorScheme.panel).padding(48.dp),
    verticalArrangement = Arrangement.spacedBy(20.dp),
  ) {
    Text("Connect Terrazzo", style = SpatialTheme.typography.headline1Strong, color = QuestText)
    Text(
      "Sign in to your Home Assistant to see and control the devices in your rooms.",
      style = SpatialTheme.typography.body1,
      color = QuestTextSecondary,
    )
    OutlinedTextField(
      value = url,
      onValueChange = { url = it },
      label = { Text("Home Assistant URL") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    when (connection) {
      QuestConnection.SigningIn ->
        Text("Waiting for sign-in in the browser…", color = QuestTextSecondary)
      QuestConnection.Connecting ->
        Text("Connecting to Home Assistant…", color = QuestTextSecondary)
      is QuestConnection.Error -> Text(connection.message, color = MaterialTheme.colorScheme.error)
      else -> Unit
    }
    NamedButton("Sign in", "Sign in to Home Assistant") { onSignIn(url) }
  }
}

/**
 * A [BorderlessButton] read out as [description]: each card's "Pin here" names its control, so a
 * screen reader can tell them apart. The UI set's button takes no modifier, so this replaces its
 * semantics from outside.
 */
@Composable
private fun NamedButton(label: String, description: String, onClick: () -> Unit) {
  Box(
    Modifier.clearAndSetSemantics {
      contentDescription = description
      role = Role.Button
      onClick {
        onClick()
        true
      }
    },
  ) {
    BorderlessButton(label, onClick)
  }
}

private val QuestText = Color.White
private val QuestTextSecondary = Color(0xB3FFFFFF)
