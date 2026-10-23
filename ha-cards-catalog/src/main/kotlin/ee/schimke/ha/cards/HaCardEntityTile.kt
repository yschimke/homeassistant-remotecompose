@file:Suppress("RestrictedApi")

package ee.schimke.ha.cards

import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.FramedCard
import ee.schimke.ha.rc.cards.TileCardConverter
import ee.schimke.ha.rc.framedDesignTemplate

/**
 * The design key's card and placeholder snapshot while a card design is captured for that key
 * (server-owned-card-designs.md): what a binding-shaped component such as [HaCardEntityTile] draws
 * when its own structural properties are left unset. Null outside a capture, in UI Builder's canvas
 * and stickers, where the component's properties decide.
 */
val LocalCardDesignInput = staticCompositionLocalOf<DesignTemplateInput?> { null }

/**
 * HA `tile` card for one slot entity, drawn exactly as the tile converter draws it on a dashboard:
 * the icon, accent, meter glyph and unavailable look all come from the entity's structural
 * properties, and its name and state are the slot's named bindings (`<entityId>.name`,
 * `<entityId>.state`), so a card design that places it binds what the library's tile template does.
 *
 * The properties are the design key's (`TileCardConverter.DOMAIN` and its siblings): [domain],
 * [deviceClass], the entity's own `icon` attribute ([iconAttribute]), the card's configured `icon:` ([iconConfig]) and a sensor's [unit].
 * All unset, a captured design takes them from the key it is captured for ([LocalCardDesignInput]),
 * so one design covers every tile key.
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
    converter.WrapTile(input.card, input.snapshot, it)
  }
}
