@file:Suppress("RestrictedApi")

package ee.schimke.ha.cards

import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.rc.FramedCard
import ee.schimke.ha.rc.cards.TileCardConverter
import ee.schimke.ha.rc.design.LocalCardInput
import ee.schimke.ha.rc.framedDesignTemplate

/**
 * The card being drawn while a card design draws it ([LocalCardInput]): what a binding-shaped
 * component such as [HaCardEntityTile] draws when its own structural properties are left unset.
 */
val LocalCardDesignInput = LocalCardInput

/**
 * HA `tile` card for one slot entity, drawn exactly as the tile converter draws it on the surface
 * it is captured for (a dashboard's wrap layout; a launcher cell's or Wear container's fixed
 * tiers): the icon, accent, meter glyph and unavailable look all come from the entity's structural
 * properties, and its name and state are the slot's named bindings (`<entityId>.name`,
 * `<entityId>.state`), so a card design that places it binds what the library's tile template does.
 *
 * The properties are the design key's (`TileCardConverter.DOMAIN` and its siblings): [domain],
 * [deviceClass], the entity's own `icon` attribute ([iconAttribute]), the card's configured `icon:`
 * ([iconConfig]) and a sensor's [unit]. All unset, a captured design takes them from the key it is
 * captured for ([LocalCardDesignInput]), so one design covers every tile key.
 */
@Composable
@RemoteComposable
fun HaCardEntityTile(
  modifier: RemoteModifier = RemoteModifier,
  domain: String = "",
  deviceClass: String = "",
  iconAttribute: String = "",
  iconConfig: String = "",
  unit: String = "",
) {
  val converter = remember { TileCardConverter() }
  val own = listOf(domain, deviceClass, iconAttribute, iconConfig, unit).any { it.isNotBlank() }
  val input =
    LocalCardDesignInput.current?.takeIf { !own }
      ?: converter.framedDesignTemplate(
        DesignProperties.build {
          string(TileCardConverter.DOMAIN, domain.ifBlank { "light" })
          string(TileCardConverter.DEVICE_CLASS, deviceClass.ifBlank { null })
          string(TileCardConverter.ICON, iconAttribute.ifBlank { null })
          string(TileCardConverter.CARD_ICON, iconConfig.ifBlank { null })
          string(TileCardConverter.UNIT, unit.ifBlank { null })
        }
      )
      ?: return
  FramedCard(input.card, input.snapshot, modifier) {
    converter.Render(input.card, input.snapshot, it)
  }
}
