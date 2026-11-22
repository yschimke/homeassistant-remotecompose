package ee.schimke.ha.designs

import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxWidth
import ee.schimke.ha.cards.CardDesignDocument
import ee.schimke.ha.cards.DevicePacks
import ee.schimke.ha.design.BuiltDesign
import ee.schimke.ha.design.CardBindingRecipe
import ee.schimke.ha.design.CardBindingRecipes
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DeviceMatch
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CaptureHost
import ee.schimke.ha.rc.CardDesignTemplates
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The device packs (docs/design/device-packs.md) as designs in the card design library, so a
 * dashboard can draw a garage door as the door itself: each pack's **controls** designs, drawn as a
 * [cardType] of the pack's entity (a tile, a thermostat) in every theme the library keys it by.
 *
 * - **Its own design.** Each is a library design of its own ([designId]: `pack:<designId>`, titled
 *   as its pack names it), keyed by its own variant ([variant]), so the tile's library design stays
 *   the automatic one and a card draws a pack design only when it chooses it (`design_id`).
 * - **For its class.** A pack for a `device_class` (every garage door) is a [DeviceMatch] on it:
 *   the design server's suggestion index lists it, and the card picker and the dashboard agent
 *   offer it on any entity of that class. A pack for a whole domain (a thermostat) has no match: it
 *   is chosen by id, not suggested on every climate entity.
 * - **The card's own entity.** The design is drawn with its example entity named as the contract's
 *   slot ([DevicePacks.forSlots]), the names a host pushes a card's entity under.
 *
 * Alerts, toolbar items and real-world views are other placements than a dashboard card (and an
 * alert is shown only by its rule), so they stay out until those placements exist.
 */
object DevicePackLibrary {
  /**
   * The pack designs the library publishes: each pack's controls whose every value a host can push
   * ([recipe]). The thermostat overlay reads its readings as formatted text
   * (`climate.s0.attributes.current_temperature_label`), which no recipe can produce yet, so it
   * stays out rather than draw its example's numbers.
   */
  val published: List<DevicePacks.PackDesign>
    get() =
      DevicePacks.designs.filter {
        it.kind == CONTROLS && it.designId !in NOT_BINDABLE && it.domain !in UNIT_BOUND
      }

  /**
   * The values [design] reads beyond the slot bindings, as the recipe its template carries: a
   * cover's position, a thermostat's two temperatures. Every host evaluates it, so the design
   * follows the entity's attributes live rather than drawing the example's.
   */
  fun recipe(design: DevicePacks.PackDesign): CardBindingRecipe =
    when (design.domain) {
      "climate" -> CardBindingRecipes.CLIMATE
      else -> CardBindingRecipes.COVER
    }

  /** The library design id of [design]: one id across every theme key it is captured for. */
  fun designId(design: DevicePacks.PackDesign): String = "$PREFIX${design.designId}"

  /**
   * The variant [design]'s keys carry: its own, so it never shares a key with the tile's design.
   */
  fun variant(design: DevicePacks.PackDesign): String = "pack-${design.designId}"

  /** Who [design] is suggested for: its pack's `device_class`, or null for a whole domain. */
  fun device(design: DevicePacks.PackDesign): DeviceMatch? =
    design.deviceClass?.let { DeviceMatch(deviceClass = it) }

  /**
   * A tile of [design]'s example entity, with the state its class gives: what the library keys the
   * design by (its themes and shape), and its catalog example.
   */
  fun example(design: DevicePacks.PackDesign): CardDesignLibrary.Example {
    val entity = "${design.domain}.${design.pack.replace('-', '_')}"
    val attributes = buildJsonObject {
      put("friendly_name", design.title.substringBefore(" · "))
      design.deviceClass?.let { put("device_class", it) }
      // A thermostat with a set point, as most are: the shape the library keys it by.
      if (design.domain == "climate") {
        put("temperature", 21)
        put("current_temperature", 19.5)
      }
    }
    val type = cardType(design)
    val card =
      CardConfig(
        type = type,
        raw =
          buildJsonObject {
            put("type", type)
            put("entity", entity)
          },
      )
    val state = EntityState(entity, EXAMPLE_STATE[design.domain] ?: "on", attributes)
    return CardDesignLibrary.Example(
      "$PREFIX${design.designId}",
      card,
      HaSnapshot(states = mapOf(entity to state)),
    )
  }

  /** [design]'s library keys: the tile's, each with the design's own [variant]. */
  fun keys(design: DevicePacks.PackDesign): List<DesignKey> {
    val example = example(design)
    return CardDesignLibrary.keysOf(example.card, example.snapshot)
      .map { it.copy(variant = variant(design)) }
      .distinct()
  }

  /**
   * [design] captured for [key], drawn by [CardDesignDocument] with its example entity as slot 0.
   */
  fun capture(host: CaptureHost, key: DesignKey, design: DevicePacks.PackDesign): BuiltDesign {
    val document = CardDesignDocument.parse(DevicePacks.forSlots(design))
    // The box carries the recipe's declaration: the document's own root may not draw the modifier.
    val built =
      CardDesignTemplates.captureWithRecipe(host, key, recipe(design)) { _, _, modifier ->
        RemoteBox(modifier) { document.Render(RemoteModifier.fillMaxWidth()) }
      }
    return BuiltDesign(
      key = built.key,
      slots = built.slots,
      bytes = built.bytes,
      designId = designId(design),
      title = design.title,
      device = device(design),
    )
  }

  /**
   * The Home Assistant card [design] is a design of: what a dashboard card names and the library
   * keys it by. A thermostat is the climate card (the library keys no climate tile); the rest are
   * tiles.
   */
  fun cardType(design: DevicePacks.PackDesign): String = CARD_TYPES[design.domain] ?: "tile"

  /** The pack design [key] is one of, or null for a key that isn't a pack's. */
  fun designFor(key: DesignKey): DevicePacks.PackDesign? =
    if (!key.variant.startsWith("pack-")) null
    else published.firstOrNull { variant(it) == key.variant }

  private const val PREFIX = "pack:"
  private const val CONTROLS = "controls"
  private val NOT_BINDABLE = setOf("thermostat-controls-overlay")

  /**
   * Domains whose readings carry a unit the designs bake (a thermostat's `°C`, its dial's 7–30
   * range): no binding pushes the entity's own unit or range yet, so a °F thermostat would read
   * wrong. Published once the unit and range are bindable.
   */
  private val UNIT_BOUND = setOf("climate")
  private val CARD_TYPES = mapOf("climate" to "thermostat")
  private val EXAMPLE_STATE = mapOf("cover" to "closed", "climate" to "heat")
}
