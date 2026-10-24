@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.RemoteTextUnit
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight

/**
 * HA `heading` card — section title inside a Sections view. Classic and Home Assistant match
 * `hui-heading-card.ts`; every other visual family draws its own heading ([EverydayHeading],
 * docs/design/everyday-cards.md §3). The text is the static `card.title`.
 */
@Composable
@RemoteComposable
fun RemoteHaHeading(
  data: HaHeadingData,
  modifier: RemoteModifier = RemoteModifier,
  textSize: RemoteTextUnit = data.style.textSize,
) {
  EverydayHeading(cardTitle(data.title), textSize, data.style.weight, modifier)
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
