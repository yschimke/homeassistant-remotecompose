package ee.schimke.ha.designserver

import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignSurface
import ee.schimke.ha.model.EntityState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Which library design draws a card, and what to push into it: `POST /api/cards/resolve`.
 *
 * Give it a card's Lovelace config (and optionally its entities' states, a card style and light or
 * dark), and it answers with the design's `.rc` and the values to push into it, so a player that
 * has no card converters (UI Builder's canvas, the `<ha-card>` embed, a web page) can draw the card
 * as the app would.
 *
 * The server can't run the app's converters (they need Android), so it can't compute a card's
 * generation properties and with them its exact design key. It picks the closest library design
 * instead: same card type, same slots, the requested card style and mode, the default variant. When
 * several shapes fit, it takes the first and says so ([CardMatch.exact] false). The values are the
 * card's slot bindings computed from its states, plus the converter-only ones with the library
 * example's values ([CardSchema.fromCard]).
 */
@Serializable
data class CardMatch(
  /** The library design's id (`/designs/<id>`). */
  val design: String,
  val sha256: String,
  /** Where its `.rc` is served. */
  val blob: String,
  val key: DesignKey,
  /** False when the card's shape couldn't be told and another design may fit it better. */
  val exact: Boolean,
  /**
   * Every named value to push, by name: the card's own where known, else the example's. Null is a
   * value the card has but can't be written in JSON, such as an unavailable entity's number.
   */
  val bindings: Map<String, JsonPrimitive>,
  val notes: List<String> = emptyList(),
)

/** A resolve request: the card, its states, and the look wanted. */
data class ResolveRequest(
  val card: JsonObject,
  val states: Map<String, EntityState>,
  /** An `HaUberTheme` id; `classic` draws in an app palette. */
  val cardStyle: String = DesignKey.CLASSIC_STYLE,
  val dark: Boolean = false,
  /** For `classic`: the app palette (`baked:<palette>:<mode>`); null takes the library's first. */
  val palette: String? = null,
) {
  companion object {
    /** [CardSchema.parseRequest]'s body, plus optional `cardStyle`, `dark` and `palette`. */
    fun parse(body: JsonElement): ResolveRequest {
      val (card, states) = CardSchema.parseRequest(body)
      val obj = body.jsonObject
      fun text(name: String) = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
      return ResolveRequest(
        card = card,
        states = states,
        cardStyle = text("cardStyle") ?: DesignKey.CLASSIC_STYLE,
        dark = (obj["dark"] as? JsonPrimitive)?.booleanOrNull ?: false,
        palette = text("palette"),
      )
    }
  }
}

object CardResolve {
  /**
   * The value pushed for each of [names]: the card's [own] where it has the binding, even one with
   * no JSON form (an unavailable entity's `numeric_state` is NaN), which is null, never the
   * example's reading. Only bindings the card lacks, those its converter would add, take the
   * library [example]'s value.
   */
  internal fun pushedValues(
    names: List<String>,
    own: List<BindingSchema>,
    example: Map<String, JsonPrimitive>,
  ): Map<String, JsonPrimitive> {
    val byName = own.associateBy { it.name }
    return buildMap {
      for (name in names) {
        val value = if (name in byName) byName.getValue(name).example ?: JsonNull else example[name]
        if (value != null) put(name, value)
      }
    }
  }

  /** The design [request]'s card renders as, or null when no library design has its slots. */
  fun resolve(library: DesignLibrary, request: ResolveRequest): CardMatch? {
    val schema = CardSchema.fromCard(request.card, request.states, library)
    val candidates =
      CardSchema.templatesWithSlots(library, schema.cardType, schema.slots.map { it.slot }).filter {
        it.key.surface == DesignSurface.MobileApp && !it.entry.revoked
      }
    if (candidates.isEmpty()) return null

    val mode = if (request.dark) "dark" else "light"
    val inStyle =
      if (request.cardStyle == DesignKey.CLASSIC_STYLE) {
        val baked = candidates.filter { it.key.theme.startsWith("baked:") }
        val palette = request.palette ?: baked.firstOrNull()?.key?.theme?.split(':')?.getOrNull(1)
        baked.filter { it.key.theme == DesignKey.bakedTheme(palette.orEmpty(), request.dark) }
      } else {
        val styled = candidates.filter { it.key.cardStyle == request.cardStyle }
        // A style with one mode (Trinitron, Nixie and Console are dark only) uses it either way.
        styled.filter { it.key.theme.endsWith(":$mode") }.ifEmpty { styled }
      }
    if (inStyle.isEmpty()) return null
    val variant = inStyle.filter { it.key.variant == DesignKey.DEFAULT_VARIANT }.ifEmpty { inStyle }
    val shapes = variant.map { it.key.shape }.distinct()
    val chosen = variant.first()

    val example = chosen.catalog?.example?.bindings.orEmpty()
    val bindings =
      pushedValues(library.decode(chosen).bindings.map { it.name }, schema.bindings, example)
    return CardMatch(
      design = chosen.id,
      sha256 = chosen.entry.sha256,
      blob = "$API_BASE/blobs/${chosen.entry.sha256}.rc",
      key = chosen.key,
      exact = shapes.size == 1,
      bindings = bindings,
      notes =
        buildList {
          if (shapes.size > 1)
            add(
              "${shapes.size} shapes of `${schema.cardType}` have these slots, and the card's " +
                "shape can't be told without its converter: this is the first of them."
            )
          addAll(schema.notes)
        },
    )
  }
}
