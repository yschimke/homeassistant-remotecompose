@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards.shutter

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaType
import ee.schimke.ha.rc.components.cardChrome
import ee.schimke.ha.rc.components.cardTitle
import ee.schimke.ha.rc.components.currentRemoteHaTheme
import ee.schimke.ha.rc.components.entityName

/**
 * One cover row of a shutter card.
 *
 * @property entity the cover's state now: it seeds the row's bindings, so the first frame is
 *   current. Every later change arrives through the bindings ([CoverLive]).
 * @property name the row's `name`, else the entity's `friendly_name`: `<id>.name` in a template.
 * @property hide the row's `button_*_hide_states` (enhanced shutter card only).
 * @property garage draw a garage door rather than a window shutter.
 * @property unknownFraction how closed a cover in an unknown state is drawn.
 */
data class CoverEntryData(
  val entityId: String?,
  val entity: EntityState?,
  val name: String,
  val showNameOnTop: Boolean,
  val hide: CoverHideStates = CoverHideStates.None,
  val garage: Boolean = true,
  val unknownFraction: Float = 1f,
)

/** A shutter card: its title, then one row per cover, each drawn by [drawing]. */
@Composable
@RemoteComposable
internal fun CoverCard(
  title: String?,
  entries: List<CoverEntryData>,
  modifier: RemoteModifier,
  drawing: @Composable (CoverEntryData, CoverLive) -> Unit,
) {
  val theme = currentRemoteHaTheme()
  // One set of named values per cover, however many rows show it.
  val lives = HashMap<String, CoverLive>()
  RemoteBox(
    modifier =
      modifier
        .fillMaxWidth()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 12.rdp, vertical = 10.rdp)
  ) {
    RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(10.rdp)) {
      if (title != null) {
        RemoteText(
          text = cardTitle(title),
          color = theme.primaryText,
          fontSize = HaType.Title,
          fontWeight = FontWeight.Medium,
          style = RemoteTextStyle.Default,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      entries.forEach { entry ->
        val live =
          entry.entityId?.let { id ->
            lives.getOrPut(id) { CoverLive.of(id, entry.entity, entry.unknownFraction) }
          } ?: CoverLive.of(null, null, entry.unknownFraction)
        CoverRow(entry, live, drawing)
      }
    }
  }
}

@Composable
@RemoteComposable
private fun CoverRow(
  entry: CoverEntryData,
  live: CoverLive,
  drawing: @Composable (CoverEntryData, CoverLive) -> Unit,
) {
  val theme = currentRemoteHaTheme()
  RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(4.rdp)) {
    if (entry.showNameOnTop) {
      RemoteText(
        text = entityName(entry.entityId, entry.name),
        color = theme.primaryText,
        fontSize = HaType.Body,
        fontWeight = FontWeight.Medium,
        style = RemoteTextStyle.Default,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    RemoteRow(
      verticalAlignment = RemoteAlignment.CenterVertically,
      horizontalArrangement = RemoteArrangement.spacedBy(12.rdp),
    ) {
      drawing(entry, live)
      CoverButtonColumn(
        live.controls(entry.hide),
        coverControls(entry.entity, entry.hide),
        open = coverService("open_cover", entry.entityId),
        stop = coverService("stop_cover", entry.entityId),
        close = coverService("close_cover", entry.entityId),
      )
    }
    RemoteText(
      text = live.label,
      color = theme.secondaryText,
      fontSize = HaType.Caption,
      style = RemoteTextStyle.Default,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/** `cover.<service>` on [entityId]; nothing for a row without one. */
internal fun coverService(service: String, entityId: String?): HaAction =
  entityId?.let { HaAction.CallService(domain = "cover", service = service, entityId = it) }
    ?: HaAction.None
