@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.shapes.RemoteCircleShape
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/**
 * `calendar` card — title + chronological list of upcoming events.
 * Rendered as a list rather than a month-grid because the list survives
 * watch/phone-widget squeezes and conveys the same data in less space.
 */
@Composable
@RemoteComposable
fun RemoteHaCalendar(data: HaCalendarData, modifier: RemoteModifier = RemoteModifier) {
    val theme = haTheme()
    RemoteBox(
        modifier = modifier
            .fillMaxWidth()
            .then(cardChrome(theme.cardBackground, theme.divider))
            .padding(horizontal = 14.rdp, vertical = 12.rdp),
    ) {
        RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(8.rdp)) {
            RemoteRow(
                modifier = RemoteModifier.fillMaxWidth(),
                horizontalArrangement = RemoteArrangement.SpaceBetween,
                verticalAlignment = RemoteAlignment.CenterVertically,
            ) {
                RemoteText(
                    text = data.title.rs,
                    color = theme.primaryText.rc,
                    fontSize = 16.rsp,
                    fontWeight = FontWeight.Medium,
                    style = RemoteTextStyle.Default,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                RemoteText(
                    text = data.rangeLabel.rs,
                    color = theme.secondaryText.rc,
                    fontSize = 11.rsp,
                    style = RemoteTextStyle.Default,
                    maxLines = 1,
                )
            }
            if (data.events.isEmpty()) {
                RemoteText(
                    text = "No upcoming events".rs,
                    color = theme.secondaryText.rc,
                    fontSize = 12.rsp,
                    style = RemoteTextStyle.Default,
                )
            } else {
                data.events.forEach { Event(it, theme) }
            }
        }
    }
}

/**
 * Identity tier for the `calendar` family — the next event only: its
 * accent dot, when-label and summary, with the calendar title above.
 * The smallest cell that still answers "what's next"; drops the rest of
 * the agenda (P5) but keeps the P1 identity (next event). Used by the
 * Fixed-mode converter at narrow launcher / Wear cells; see
 * docs/architecture/adaptive-card-layouts.md §"Bulk / time-series".
 */
@Composable
@RemoteComposable
fun RemoteHaCalendarIdentity(data: HaCalendarData, modifier: RemoteModifier = RemoteModifier) {
    val theme = haTheme()
    val next = data.events.firstOrNull()
    RemoteBox(
        modifier = modifier
            .fillMaxSize()
            .then(cardChrome(theme.cardBackground, theme.divider))
            .padding(horizontal = 14.rdp, vertical = 12.rdp),
        contentAlignment = RemoteAlignment.Center,
    ) {
        RemoteColumn(
            modifier = RemoteModifier.fillMaxWidth(),
            verticalArrangement = RemoteArrangement.spacedBy(3.rdp),
        ) {
            RemoteText(
                text = data.title.rs,
                color = theme.secondaryText.rc,
                fontSize = 11.rsp,
                style = RemoteTextStyle.Default,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (next != null) {
                RemoteRow(verticalAlignment = RemoteAlignment.CenterVertically) {
                    RemoteBox(
                        modifier = RemoteModifier
                            .size(8.rdp)
                            .clip(RemoteCircleShape)
                            .background(next.accent.rc),
                    )
                    RemoteText(
                        text = next.whenLabel.rs,
                        color = theme.secondaryText.rc,
                        fontSize = 12.rsp,
                        style = RemoteTextStyle.Default,
                        maxLines = 1,
                        modifier = RemoteModifier.padding(start = 8.rdp),
                    )
                }
                RemoteText(
                    text = next.summary.rs,
                    color = theme.primaryText.rc,
                    fontSize = 17.rsp,
                    fontWeight = FontWeight.SemiBold,
                    style = RemoteTextStyle.Default,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                RemoteText(
                    text = "No upcoming events".rs,
                    color = theme.secondaryText.rc,
                    fontSize = 13.rsp,
                    style = RemoteTextStyle.Default,
                )
            }
        }
    }
}

@Composable
private fun Event(event: HaCalendarEvent, theme: HaTheme) {
    RemoteRow(
        modifier = RemoteModifier.fillMaxWidth().padding(vertical = 2.rdp),
        verticalAlignment = RemoteAlignment.CenterVertically,
    ) {
        RemoteBox(
            modifier = RemoteModifier
                .size(8.rdp)
                .clip(RemoteCircleShape)
                .background(event.accent.rc),
        )
        RemoteColumn(modifier = RemoteModifier.padding(start = 10.rdp)) {
            RemoteText(
                text = event.whenLabel.rs,
                color = theme.secondaryText.rc,
                fontSize = 11.rsp,
                style = RemoteTextStyle.Default,
            )
            RemoteText(
                text = event.summary.rs,
                color = theme.primaryText.rc,
                fontSize = 13.rsp,
                fontWeight = FontWeight.Medium,
                style = RemoteTextStyle.Default,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
