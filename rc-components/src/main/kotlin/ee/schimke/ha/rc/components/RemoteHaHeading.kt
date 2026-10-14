@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.state.RemoteTextUnit
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight

/** HA `heading` card — section title inside a Sections view. Matches `hui-heading-card.ts`. */
@Composable
@RemoteComposable
fun RemoteHaHeading(data: HaHeadingData, modifier: RemoteModifier = RemoteModifier) {
  val theme = haTheme()
  RemoteText(
    text = cardTitle(data.title),
    modifier = modifier.padding(vertical = 8.rdp),
    color = theme.primaryText,
    fontSize = data.style.textSize,
    fontWeight = data.style.weight,
    style = haTextStyle(),
  )
}

enum class HaHeadingStyle(val sizeSp: Int, val weight: FontWeight) {
  Title(22, FontWeight.Medium),
  Subtitle(16, FontWeight.Medium);

  /** The [HaType] role at [sizeSp]: a title is a [HaType.Headline], a subtitle a [HaType.Title]. */
  val textSize: RemoteTextUnit
    get() =
      when (this) {
        Title -> HaType.Headline
        Subtitle -> HaType.Title
      }
}
