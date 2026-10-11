@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.runtime.Composable
import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.toTyped
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.CardWidthClass
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.LocalCardTemplateCapture
import ee.schimke.ha.rc.RemoteSizeBreakpoint
import ee.schimke.ha.rc.components.HaTileData
import ee.schimke.ha.rc.components.HaToggleAccent
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.RemoteHaIconChip
import ee.schimke.ha.rc.components.RemoteHaTile
import ee.schimke.ha.rc.defaultTapActionFor
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.rc.icons.HaIconMap
import ee.schimke.ha.rc.parseHaAction
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `tile` card — builds [HaTileData] from config + snapshot.
 *
 * In [CardSizeMode.Wrap] the card always renders the full HA-style tile. In [CardSizeMode.Fixed]
 * (launcher / wear widgets) the converter wraps its output in a [RemoteSizeBreakpoint] icon-+-state
 * ladder (adaptive-card-layouts.md §"Icon + state row/tile"):
 *
 * * **Narrow** (`w < 96 dp`, e.g. a `1×1` launcher cell) → [RemoteHaIconChip], the tinted state
 *   icon centred over a single state line. Keeps the icon identity at the smallest size instead of
 *   dropping to a bare text chip (Principle 1).
 * * **Full** (otherwise) → the HA-reference [RemoteHaTile], wrapped in [CenteredCell] so a tall
 *   cell (`3×2`) centres the row rather than gluing it to the top over a blank half (Principles
 *   7–8).
 */
class TileCardConverter : CardConverter {
  companion object {
    /** Domains whose plain tiles render from card designs. */
    val TEMPLATE_DOMAINS: List<String> = listOf("light", "switch", "sensor", "binary_sensor")

    /** Config keys a templated tile may carry; anything else renders through the converter. */
    private val TEMPLATE_CONFIG_KEYS = setOf("type", "entity", "name")

    /** Domains whose `device_class` changes the tile's icon or colour. */
    private val DEVICE_CLASS_DOMAINS = setOf("sensor", "binary_sensor")

    const val DOMAIN = "domain"
    const val DEVICE_CLASS = "device_class"
    const val ICON = "icon"
  }

  override val cardType: String = CardTypes.TILE

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 43

  override fun naturalWidthClass(card: CardConfig, snapshot: HaSnapshot): CardWidthClass =
    CardWidthClass.Compact

  /**
   * A plain tile (`entity` plus optional `name`) renders from a card design. Its generation
   * properties are what [tileData] derives structure from: the domain (icon family, toggle chrome),
   * the `device_class` of sensors and binary sensors (icon, colour) and an `icon` attribute. The
   * name and the state are bindings. An entity not in [snapshot] yet has no properties, so the
   * converter renders that state.
   */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? {
    if (!TEMPLATE_CONFIG_KEYS.containsAll(card.raw.keys)) return null
    val entityId = card.raw["entity"]?.jsonPrimitive?.contentOrNull ?: return null
    val domain = CardDesignSlots.domainOf(entityId)
    if (domain !in TEMPLATE_DOMAINS) return null
    val state = snapshot.states[entityId] ?: return null
    return DesignProperties.build {
      string(DOMAIN, domain)
      if (domain in DEVICE_CLASS_DOMAINS) {
        string(DEVICE_CLASS, state.attributes[DEVICE_CLASS]?.jsonPrimitive?.contentOrNull)
      }
      string(ICON, state.attributes[ICON]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase())
    }
  }

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? {
    val domain = properties[DOMAIN] ?: return null
    val slot = CardDesignSlots.slotId(domain, 0)
    val card =
      CardConfig(
        type = cardType,
        raw =
          buildJsonObject {
            put("type", cardType)
            put("entity", slot)
          },
      )
    // `off` is a state every templated domain models, so toggle chrome is captured with its
    // binding (an `unknown` placeholder would make tileData leave the toggle out).
    val entity =
      CardDesignSlots.templateEntity(
        slot,
        placeholderState = "off",
        attributes = properties.values.filterKeys { it != DOMAIN },
      )
    return DesignTemplateInput(card, HaSnapshot(states = mapOf(slot to entity)))
  }

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    when (LocalCardSizeMode.current) {
      CardSizeMode.Wrap -> FullTile(card, snapshot, modifier)
      CardSizeMode.Fixed ->
        RemoteSizeBreakpoint(thresholdsDp = intArrayOf(96), modifier = modifier.fillMaxSize()) {
          tier ->
          when (tier) {
            0 -> RemoteHaIconChip(tileData(card, snapshot), RemoteModifier.fillMaxSize())
            else -> CenteredCell { FullTile(card, snapshot, RemoteModifier.fillMaxWidth()) }
          }
        }
    }
  }

  @Composable
  private fun FullTile(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    RemoteHaTile(tileData(card, snapshot), modifier = modifier)
  }

  @Composable
  private fun tileData(card: CardConfig, snapshot: HaSnapshot): HaTileData {
    val entityId = card.raw["entity"]?.jsonPrimitive?.content
    val entity = entityId?.let { snapshot.states[it] }
    val name =
      card.raw["name"]?.jsonPrimitive?.content
        ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
        ?: entityId
        ?: "(no entity)"
    val tapCfg = card.raw["tap_action"]?.jsonObject
    val tapAction =
      if (tapCfg != null) parseHaAction(tapCfg, entityId) else defaultTapActionFor(entityId)
    val isActive = entity?.toTyped()?.isActive

    return HaTileData(
      entityId = entityId,
      name = name,
      state = LiveValues.state(entityId, formatState(entity)),
      icon = HaIconMap.resolve(card.raw["icon"]?.jsonPrimitive?.content, entity),
      accent =
        HaToggleAccent(
          activeAccent = HaStateColor.activeFor(entity).rc,
          inactiveAccent = HaStateColor.inactiveFor(entity).rc,
          initiallyOn = isActive ?: false,
          toggleable = isActive != null,
        ),
      tapAction = tapAction,
      // Design templates bind the name so the client can fill it in; live captures bake it.
      nameBinding =
        if (LocalCardTemplateCapture.current) LiveValues.named(entityId, "name", name) else null,
    )
  }
}
