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
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meta.spatial.uiset.button.BorderlessButton
import com.meta.spatial.uiset.control.SpatialSwitch
import com.meta.spatial.uiset.theme.SpatialTheme
import com.meta.spatial.uiset.theme.darkSpatialColorScheme
import ee.schimke.ha.model.HaLiveBindings
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.room.RoomEntity
import ee.schimke.terrazzo.room.LostPins
import ee.schimke.terrazzo.room.RoomContent
import ee.schimke.terrazzo.room.RoomHeader
import ee.schimke.terrazzo.room.RoomPin
import ee.schimke.terrazzo.room.TOGGLE_DOMAINS

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
  modifier: Modifier = Modifier,
) {
  Box(
    modifier.fillMaxSize().background(SpatialTheme.colorScheme.panel, RoundedCornerShape(24.dp)),
    contentAlignment = Alignment.Center,
  ) {
    QuestEntityCard(entity, snapshot, onToggle, onPin = null, onUnpin = onUnpin)
  }
}

/**
 * The room panel: the room's name and where it came from, the rooms to switch to, then each control
 * ([RoomContent.entityCard]), the dashboards' first. The pins the headset couldn't find again are
 * listed at the end, to remove.
 */
@Composable
fun QuestRoomPanel(
  content: RoomContent,
  lostPins: List<RoomPin> = emptyList(),
  onRemoveLost: (RoomPin) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val controls = content.state?.controls
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
        RoomHeader(content)
      }
    }
    if (controls != null) {
      items(controls.primary + controls.more, key = { it.entityId }) {
        content.entityCard(it, Modifier)
      }
    }
    if (lostPins.isNotEmpty()) {
      item(span = { GridItemSpan(maxLineSpan) }) {
        LostPins(lostPins, content.snapshot, onRemoveLost)
      }
    }
  }
}

/**
 * One control on Quest: its name and current state, a switch for one that turns on and off, and
 * "Pin here" to pin it where the user looks ([onPin], null where pinning isn't available) or
 * "Unpin" on a pinned panel ([onUnpin]).
 */
@Composable
fun QuestEntityCard(
  entity: RoomEntity,
  snapshot: HaSnapshot?,
  onToggle: (entityId: String) -> Unit,
  onPin: ((entityId: String) -> Unit)?,
  modifier: Modifier = Modifier,
  onUnpin: (() -> Unit)? = null,
) {
  val state = snapshot?.states?.get(entity.entityId)
  Column(
    modifier.fillMaxWidth().background(QuestCard, RoundedCornerShape(24.dp)).padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(
          entity.name,
          style = SpatialTheme.typography.body1Strong,
          color = QuestText,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          state?.let { formatState(it, snapshot) } ?: "Unavailable",
          style = SpatialTheme.typography.body2,
          color = QuestTextSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (state != null && entity.domain in TOGGLE_DOMAINS) {
        SpatialSwitch(
          HaLiveBindings.isOn(entity.entityId, state.state),
          { onToggle(entity.entityId) },
          Modifier.semantics { contentDescription = entity.name },
        )
      }
    }
    onPin?.let { pin ->
      NamedButton("Pin here", "Pin ${entity.name} here") { pin(entity.entityId) }
    }
    onUnpin?.let { NamedButton("Unpin", "Unpin ${entity.name}", it) }
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
    }
  ) {
    BorderlessButton(label, onClick)
  }
}

private val QuestCard = Color(0x26FFFFFF)
private val QuestText = Color(0xFFFFFFFF)
private val QuestTextSecondary = Color(0xB3FFFFFF)
