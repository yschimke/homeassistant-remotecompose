@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.runtime.Composable
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.state.RemoteTextUnit
import ee.schimke.composeai.rccreation.compose.text.RemoteFontWeight

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
  textSurfaces().Heading(cardTitle(data.title), textSize, data.style.weight, modifier)
}

enum class HaHeadingStyle(val sizeSp: Int, val weight: RemoteFontWeight) {
  Title(22, RemoteFontWeight.Medium),
  Subtitle(16, RemoteFontWeight.Medium);

  /** The [HaType] role at [sizeSp]: a title is a [HaType.Headline], a subtitle a [HaType.Title]. */
  val textSize: RemoteTextUnit
    get() =
      when (this) {
        Title -> HaType.Headline
        Subtitle -> HaType.Title
      }
}
